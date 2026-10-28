import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.requests import ClientDisconnect

from app.config import Settings
from app.graph import KnowledgeWorkflow
from app.runtime import create_retriever
from app.security import AuthError, RequestVerifier

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None, retriever=None) -> FastAPI:
    settings = settings or Settings()
    verifier = RequestVerifier(settings.shared_secret.get_secret_value())
    managed_retriever = retriever is None

    @asynccontextmanager
    async def lifespan(application):
        selected = create_retriever(settings) if managed_retriever else retriever
        workflow = KnowledgeWorkflow(settings, selected)
        application.state.workflow = workflow
        try:
            yield
        finally:
            await workflow.model.close()
            if managed_retriever:
                selected.embeddings.close()

    application = FastAPI(
        title="MES internal knowledge service",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    @application.get("/health")
    async def health():
        return {
            "status": "ok",
            "framework": "LANGGRAPH",
            "llmConfigured": settings.llm_configured,
            "embeddingConfigured": bool(settings.jina_api_key.get_secret_value()),
            "serviceAuthenticationConfigured": len(settings.shared_secret.get_secret_value()) >= 32,
        }

    async def read_body(request: Request):
        if int(request.headers.get("content-length", "0")) > 65536:
            raise AuthError()
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data) > 65536:
                raise AuthError()
        return bytes(data)

    @application.post("/api/agent/chat")
    async def chat(request: Request):
        try:
            body = await asyncio.wait_for(read_body(request), timeout=3)
            signed = verifier.verify(
                body, request.headers, method=request.method, path=request.url.path
            )
        except (AuthError, ValueError, ClientDisconnect, TimeoutError):
            return JSONResponse({"error": "UNAUTHORIZED"}, status_code=401)
        try:
            response = await application.state.workflow.run(signed)
            logger.info(
                "agent_request requestId=%s status=%s elapsedMs=%s modelAttempted=%s",
                signed.requestId,
                response.status,
                response.elapsedMs,
                response.modelAttempted,
            )
            return response.model_dump(exclude_none=False)
        except Exception:  # noqa: BLE001 - sanitize unexpected errors at the HTTP boundary.
            logger.error("agent_failed requestId=%s code=INTERNAL_ERROR", signed.requestId)
            return JSONResponse(
                {"error": "INTERNAL_ERROR", "requestId": signed.requestId}, status_code=500
            )

    return application


app = create_app()
