from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator

ToolName = Literal["search_sop_knowledge", "get_material_routing"]
Scope = Literal["public", "restricted"]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class UserMessage(StrictModel):
    role: Literal["user"] = "user"
    content: str = Field(min_length=1, max_length=1000)


class AuthorizedContext(StrictModel):
    subject: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_.:-]+$")
    scopes: list[Scope] = Field(default_factory=list, max_length=2)
    allowedTools: list[ToolName] = Field(default_factory=list, max_length=2)
    issuedAt: int = Field(ge=0)
    expiresAt: int = Field(ge=0)


class ProvidedToolResult(StrictModel):
    tool: Literal["get_material_routing"]
    materialCode: str = Field(min_length=1, max_length=255)
    result: dict[str, Any]


class AgentRequest(StrictModel):
    requestId: str = Field(min_length=36, max_length=36)
    question: str = Field(min_length=1, max_length=1000)
    intentHint: Literal["KNOWLEDGE", "COMPOSITE"] = "KNOWLEDGE"
    messages: list[UserMessage] = Field(default_factory=list, max_length=4)
    authorizedContext: AuthorizedContext
    providedToolResults: list[ProvidedToolResult] = Field(default_factory=list, max_length=1)

    @field_validator("requestId")
    @classmethod
    def valid_request_id(cls, value: str) -> str:
        UUID(value)
        return value


class Citation(StrictModel):
    documentId: str = Field(min_length=1, max_length=80)
    filename: str = Field(min_length=1, max_length=255)
    documentVersion: str = Field(default="unknown", max_length=80)
    pageNumber: int = Field(ge=1)
    chunkId: str = Field(min_length=1, max_length=160)
    quote: str = Field(min_length=1, max_length=1200)
    score: float = Field(ge=-1, le=1)
    scope: Scope


class ToolCall(StrictModel):
    name: ToolName
    status: Literal["SUCCESS", "NO_RESULTS", "DENIED", "ERROR"]
    elapsedMs: int = Field(ge=0)


class AgentResponse(StrictModel):
    requestId: str
    status: Literal[
        "SUCCESS",
        "PARTIAL_SUCCESS",
        "NO_KNOWLEDGE",
        "DOCUMENT_FORBIDDEN",
        "NO_INDEX",
        "RETRIEVAL_ERROR",
        "TOOL_ERROR",
        "UNSUPPORTED",
        "NEEDS_INPUT",
        "TIMEOUT",
    ]
    mode: Literal["rag", "hybrid", "agent"] = "rag"
    answer: str = Field(max_length=8000)
    citations: list[Citation] = Field(default_factory=list, max_length=5)
    agentExecuted: bool = True
    agentFramework: Literal["LANGGRAPH"] = "LANGGRAPH"
    executedNodes: list[str] = Field(default_factory=list, max_length=12)
    toolCalls: list[ToolCall] = Field(default_factory=list, max_length=2)
    retrievalStatus: Literal[
        "SUCCESS", "NO_MATCH", "FORBIDDEN", "NOT_INDEXED", "ERROR", "NOT_REQUESTED"
    ] = "NOT_REQUESTED"
    answerMode: Literal["EXTRACTIVE_NO_MODEL", "GROUNDED_MODEL", "EXTRACTIVE_FALLBACK", "NONE"] = (
        "NONE"
    )
    llmConfigured: bool = False
    recognitionSource: Literal["RULE", "MODEL", "RULE_FALLBACK"] = "RULE"
    modelAttempted: bool = False
    modelConnected: bool = False
    fallbackReason: str | None = None
    elapsedMs: int = Field(default=0, ge=0)
    material: dict[str, Any] | None = None
    routing: dict[str, Any] | None = None
    steps: list[dict[str, Any]] = Field(default_factory=list, max_length=200)
