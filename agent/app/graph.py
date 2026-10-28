import asyncio
import re
import time
from typing import TypedDict

from langchain.tools import tool
from langgraph.graph import END, START, StateGraph
from langsmith import tracing_context

from app.config import Settings
from app.llm import DeepSeekClient, ModelError
from app.rag.models import RetrievedDocument, SearchResult
from app.schemas import AgentRequest, AgentResponse, Citation, ToolCall


class AgentState(TypedDict, total=False):
    requestId: str
    question: str
    messages: list
    intent: str
    authorizedContext: dict
    request: AgentRequest
    toolResults: list
    retrievedDocuments: list[RetrievedDocument]
    answer: str
    citations: list[Citation]
    error: str | None
    response: AgentResponse


ERROR_TEXT = {
    "NO_KNOWLEDGE": "当前可访问资料中未检索到相关依据。请补充文档名称或更具体的问题；无法据此推断生产参数。",
    "DOCUMENT_FORBIDDEN": "当前授权范围内没有可检索的文档。",
    "NO_INDEX": "知识库尚无可检索的文字索引。扫描件需要另行完成文字识别。",
    "RETRIEVAL_ERROR": "知识检索暂时失败，本次没有生成知识答案。请稍后重试。",
    "TOOL_ERROR": "本次工艺查询的授权或原文编码校验未通过，未执行该工具。",
    "UNSUPPORTED": "当前助手只提供受控查询和资料检索，无法执行写入、删除或绕过权限。",
    "NEEDS_INPUT": "请补充要查询的问题。",
    "TIMEOUT": "助手处理超时，本次没有生成答案。请稍后重试。",
}


class KnowledgeWorkflow:
    def __init__(self, settings: Settings, retriever, *, model=None):
        self.settings, self.retriever = settings, retriever
        self.model = model or DeepSeekClient(settings)
        builder = StateGraph(AgentState)
        for name in [
            "classify_intent",
            "execute_mes_tool",
            "retrieve_knowledge",
            "generate_answer",
            "validate_answer",
            "handle_error",
        ]:
            builder.add_node(name, getattr(self, name))
        builder.add_edge(START, "classify_intent")
        builder.add_conditional_edges(
            "classify_intent",
            self._after_classify,
            {
                "error": "handle_error",
                "composite": "execute_mes_tool",
                "knowledge": "retrieve_knowledge",
            },
        )
        builder.add_conditional_edges(
            "execute_mes_tool",
            lambda s: "error" if s.get("error") else "search",
            {"error": "handle_error", "search": "retrieve_knowledge"},
        )
        builder.add_conditional_edges(
            "retrieve_knowledge",
            lambda s: "error" if s.get("error") else "answer",
            {"error": "handle_error", "answer": "generate_answer"},
        )
        builder.add_edge("generate_answer", "validate_answer")
        builder.add_edge("validate_answer", END)
        builder.add_edge("handle_error", END)
        self.graph = builder.compile()

    @staticmethod
    def _after_classify(state):
        return (
            "error"
            if state.get("error")
            else "composite"
            if state["intent"] == "COMPOSITE"
            else "knowledge"
        )

    @staticmethod
    def _visit(state, name):
        state["response"].executedNodes.append(name)

    def classify_intent(self, state: AgentState):
        self._visit(state, "classify_intent")
        request = state["request"]
        error = None
        if not request.question.strip():
            error = "NEEDS_INPUT"
        elif re.search(
            r"忽略.{0,12}(指令|权限)|绕过.{0,8}(权限|验证)|(?:执行|运行).{0,8}SQL|"
            r"删除.{0,10}(数据|工单|物料)|(?:drop|truncate)\s+table|"
            r"(?:新增|修改|写入).{0,8}(工单|数据库|物料)|报工|数据库密码|API\s*Key|Cookie",
            request.question,
            re.IGNORECASE,
        ):
            error = "UNSUPPORTED"
        elif (
            "search_sop_knowledge" not in request.authorizedContext.allowedTools
            or request.intentHint == "COMPOSITE"
            and (
                "get_material_routing" not in request.authorizedContext.allowedTools
                or len(request.providedToolResults) != 1
            )
        ):
            error = "TOOL_ERROR"
        state["response"].mode = "hybrid" if request.intentHint == "COMPOSITE" else "rag"
        return {"intent": request.intentHint, "error": error}

    async def execute_mes_tool(self, state: AgentState):
        self._visit(state, "execute_mes_tool")
        supplied = state["request"].providedToolResults[0]
        code = supplied.materialCode
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]{0,254}", code) or not re.search(
            r"(?<![A-Za-z0-9._/-])" + re.escape(code) + r"(?![A-Za-z0-9._/-])", state["question"]
        ):
            return {"error": "TOOL_ERROR"}

        @tool
        def get_material_routing() -> dict:
            """Read the Java-authorized pre-executed material routing result. Never access a database."""
            return supplied.result

        started = time.perf_counter()
        result = await get_material_routing.ainvoke({})
        response = state["response"]
        response.toolCalls.append(
            ToolCall(
                name="get_material_routing",
                status=("SUCCESS" if result.get("status") == "SUCCESS" else "NO_RESULTS"),
                elapsedMs=round((time.perf_counter() - started) * 1000),
            )
        )
        # Only Java-whitelisted display fields; no IDs, account data, SQL, passwords or cookies.
        response.material = result.get("material")
        response.routing = result.get("routing")
        response.steps = result.get("steps") or []
        return {"toolResults": [{"tool": "get_material_routing", "result": result}]}

    def _query(self, request: AgentRequest) -> str:
        if request.messages and re.match(
            r"^(它|这|那|还有|其中|上述|那么|继续|再|what about|and |it\b|these)",
            request.question,
            re.IGNORECASE,
        ):
            return "\n".join([m.content for m in request.messages[-2:]] + [request.question])[:3000]
        return request.question

    async def retrieve_knowledge(self, state: AgentState):
        self._visit(state, "retrieve_knowledge")
        scopes = list(state["request"].authorizedContext.scopes)

        @tool
        def search_sop_knowledge(query: str) -> SearchResult:
            """Search only the document scopes signed by Java; return page-grounded source snippets."""
            return self.retriever.search(query, scopes)

        started = time.perf_counter()
        query = self._query(state["request"])
        for supplied in state["request"].providedToolResults:
            query = query.replace(supplied.materialCode, "")
        result = await search_sop_knowledge.ainvoke({"query": query})
        response = state["response"]
        response.retrievalStatus = result.status
        response.toolCalls.append(
            ToolCall(
                name="search_sop_knowledge",
                status=(
                    "SUCCESS"
                    if result.status == "SUCCESS"
                    else "DENIED"
                    if result.status == "FORBIDDEN"
                    else "ERROR"
                    if result.status == "ERROR"
                    else "NO_RESULTS"
                ),
                elapsedMs=round((time.perf_counter() - started) * 1000),
            )
        )
        errors = {
            "NO_MATCH": "NO_KNOWLEDGE",
            "FORBIDDEN": "DOCUMENT_FORBIDDEN",
            "NOT_INDEXED": "NO_INDEX",
            "ERROR": "RETRIEVAL_ERROR",
        }
        return {"retrievedDocuments": result.documents, "error": errors.get(result.status)}

    @staticmethod
    def _citation(doc: RetrievedDocument, quote: str) -> Citation:
        return Citation(
            documentId=doc.documentId,
            filename=doc.filename,
            documentVersion=doc.documentVersion,
            pageNumber=doc.pageNumber,
            chunkId=doc.chunkId,
            quote=quote,
            score=doc.score,
            scope=doc.scope,
        )

    @staticmethod
    def _java_text(state) -> str:
        if not state.get("toolResults"):
            return ""
        result = state["toolResults"][0]["result"]
        if result.get("status") != "SUCCESS":
            return "Java查询未返回可用工艺路线。\n\n"
        material, routing = result.get("material") or {}, result.get("routing") or {}
        return (
            f"Java数据库查询：物料 {material.get('code', '')} {material.get('name', '')}；"
            f"路线 {routing.get('code', '')} {routing.get('name', '')}；"
            f"共 {len(result.get('steps') or [])} 道工序。\n\n"
        )

    def _extractive(self, state, fallback=False):
        response = state["response"]
        response.citations = [
            self._citation(d, d.text[:1200]) for d in state["retrievedDocuments"][:5]
        ]
        response.answerMode = "EXTRACTIVE_FALLBACK" if fallback else "EXTRACTIVE_NO_MODEL"
        prefix = (
            "模型处理失败，以下保留真实检索原文。"
            if fallback
            else "未配置聊天模型，以下为真实检索原文，尚未生成总结。"
        )
        response.answer = (
            self._java_text(state)
            + prefix
            + "未在引用中出现的结论或参数不能据此推断。\n\n"
            + "\n\n".join(f"[{i}] {c.quote}" for i, c in enumerate(response.citations, 1))
        )

    async def generate_answer(self, state: AgentState):
        self._visit(state, "generate_answer")
        response = state["response"]
        if not self.model.configured:
            self._extractive(state)
        else:
            response.modelAttempted = True
            try:
                chosen = await self.model.select_evidence(
                    self._query(state["request"]), state["retrievedDocuments"]
                )
                source = {d.chunkId: d for d in state["retrievedDocuments"]}
                if not isinstance(chosen, list) or len(chosen) > 5:
                    raise ModelError("LLM_INVALID_RESPONSE")
                citations = []
                for item in chosen:
                    doc = source.get(item.get("chunkId"))
                    quote = item.get("quote")
                    if (
                        not doc
                        or not isinstance(quote, str)
                        or not quote.strip()
                        or quote not in doc.text
                    ):
                        raise ModelError("LLM_UNGROUNDED")
                    citations.append(self._citation(doc, quote))
                response.citations = citations
                response.modelConnected = True
                response.recognitionSource = "MODEL"
                response.answerMode = "GROUNDED_MODEL"
                response.answer = self._java_text(state) + (
                    "根据本次检索，模型选择了以下原文依据：\n\n"
                    + "\n\n".join(f"[{i}] {c.quote}" for i, c in enumerate(citations, 1))
                    if citations
                    else ERROR_TEXT["NO_KNOWLEDGE"]
                )
                if not citations:
                    response.status = "NO_KNOWLEDGE"
            except ModelError as error:
                response.recognitionSource = "RULE_FALLBACK"
                response.fallbackReason = str(error)
                self._extractive(state, fallback=True)
        return {"answer": response.answer, "citations": response.citations}

    def validate_answer(self, state: AgentState):
        self._visit(state, "validate_answer")
        response = state["response"]
        sources = {d.chunkId: d for d in state["retrievedDocuments"]}
        scopes = state["request"].authorizedContext.scopes
        for citation in response.citations:
            doc = sources.get(citation.chunkId)
            if (
                not doc
                or citation.quote not in doc.text
                or citation.scope not in scopes
                or citation.documentId != doc.documentId
                or citation.filename != doc.filename
                or citation.pageNumber != doc.pageNumber
                or citation.documentVersion != doc.documentVersion
            ):
                response.status, response.answerMode = "RETRIEVAL_ERROR", "NONE"
                response.answer, response.citations = ERROR_TEXT["RETRIEVAL_ERROR"], []
                return {}
        if (
            response.status == "SUCCESS"
            and state.get("toolResults")
            and state["toolResults"][0]["result"].get("status") != "SUCCESS"
        ):
            response.status = "PARTIAL_SUCCESS"
        return {}

    def handle_error(self, state: AgentState):
        self._visit(state, "handle_error")
        response = state["response"]
        response.status = state.get("error") or "RETRIEVAL_ERROR"
        response.answer = self._java_text(state) + ERROR_TEXT[response.status]
        response.answerMode = "NONE"
        return {"answer": response.answer, "citations": []}

    async def run(self, request: AgentRequest) -> AgentResponse:
        started = time.perf_counter()
        response = AgentResponse(
            requestId=request.requestId,
            status="SUCCESS",
            answer="",
            llmConfigured=self.model.configured,
        )
        initial = {
            "request": request,
            "requestId": request.requestId,
            "question": request.question,
            "messages": [m.model_dump() for m in request.messages],
            "intent": request.intentHint,
            "authorizedContext": request.authorizedContext.model_dump(),
            "response": response,
            "toolResults": [],
            "retrievedDocuments": [],
            "error": None,
        }
        try:
            with tracing_context(enabled=False):
                final = await asyncio.wait_for(
                    self.graph.ainvoke(initial, config={"recursion_limit": 12}),
                    timeout=self.settings.request_timeout_seconds,
                )
            response = final["response"]
        except TimeoutError:
            response.status, response.answer = "TIMEOUT", ERROR_TEXT["TIMEOUT"]
            response.answerMode, response.citations = "NONE", []
        response.elapsedMs = round((time.perf_counter() - started) * 1000)
        return response
