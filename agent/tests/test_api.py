from app.config import Settings
from app.main import create_app
from fastapi.testclient import TestClient
from test_graph import Retriever
from test_security import SECRET, signed_request


def app():
    return create_app(Settings(_env_file=None, shared_secret=SECRET, llm_api_key=""), Retriever())


def test_health_reveals_no_secret_or_paths():
    with TestClient(app()) as client:
        result = client.get("/health")
        assert result.status_code == 200
        assert result.json()["framework"] == "LANGGRAPH"
        assert not result.json()["llmConfigured"]
        assert SECRET not in result.text and "data_dir" not in result.text


def test_signed_chat_and_replay_failure():
    with TestClient(app()) as client:
        body, headers = signed_request()
        result = client.post("/api/agent/chat", content=body, headers=headers)
        assert result.status_code == 200
        assert result.json()["citations"][0]["pageNumber"] == 3
        assert client.post("/api/agent/chat", content=body, headers=headers).status_code == 401


def test_browser_and_cookie_are_not_service_authorization():
    with TestClient(app()) as client:
        result = client.post(
            "/api/agent/chat", json={"question": "文档"}, headers={"Cookie": "admin=true"}
        )
        assert result.status_code == 401
        assert result.json() == {"error": "UNAUTHORIZED"}
        assert client.get("/admin/ingest").status_code == 404
