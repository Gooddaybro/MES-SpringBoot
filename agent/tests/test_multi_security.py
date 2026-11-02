import hashlib
import hmac
import json
import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from test_graph import Retriever
from test_multi_agent import payload
from test_security import SECRET, signed_request

from app.config import Settings
from app.main import create_app
from app.security import AuthError, RequestVerifier

PATH = "/api/multi-agent/chat"


def signed(data=None, *, path=PATH):
    data = data or payload()
    nonce = data["requestId"]
    stamp = str(data["authorizedContext"]["issuedAt"])
    body = json.dumps(data, ensure_ascii=False).encode()
    canonical = f"POST\n{path}\n{stamp}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"
    signature = hmac.new(SECRET.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return body, {"X-MES-Timestamp": stamp, "X-MES-Nonce": nonce, "X-MES-Signature": signature}


def test_verifier_supports_multi_model_without_relaxing_existing_default():
    from app.multi_schemas import MultiAgentRequest

    body, headers = signed()
    verifier = RequestVerifier(SECRET)
    with pytest.raises(AuthError):
        verifier.verify(body, headers, path=PATH)
    verified = verifier.verify(body, headers, path=PATH, request_model=MultiAgentRequest)
    assert isinstance(verified, MultiAgentRequest)
    with pytest.raises(AuthError):
        verifier.verify(body, headers, path=PATH, request_model=MultiAgentRequest)


def test_new_signed_endpoint_and_existing_single_endpoint_use_one_replay_cache():
    application = create_app(
        Settings(_env_file=None, shared_secret=SECRET, llm_api_key=""), Retriever()
    )
    with TestClient(application) as client:
        body, headers = signed()
        result = client.post(PATH, content=body, headers=headers)
        assert result.status_code == 200
        assert result.json()["agentFramework"] == "LANGGRAPH_MULTI_AGENT"
        assert len(result.json()["agentResults"]) == 4
        assert "businessResults" not in result.json()
        assert client.post(PATH, content=body, headers=headers).status_code == 401
        single, single_headers = signed_request(requestId=json.loads(body)["requestId"])
        # Resign with the consumed UUID so both endpoints have otherwise-valid signatures.
        data = json.loads(single)
        nonce = data["requestId"]
        stamp = str(data["authorizedContext"]["issuedAt"])
        canonical = f"POST\n/api/agent/chat\n{stamp}\n{nonce}\n{hashlib.sha256(single).hexdigest()}"
        single_headers["X-MES-Nonce"] = nonce
        single_headers["X-MES-Signature"] = hmac.new(
            SECRET.encode(), canonical.encode(), hashlib.sha256
        ).hexdigest()
        assert (
            client.post("/api/agent/chat", content=single, headers=single_headers).status_code
            == 401
        )
        original, original_headers = signed_request()
        assert (
            client.post("/api/agent/chat", content=original, headers=original_headers).status_code
            == 200
        )


@pytest.mark.parametrize("fault", ["wrong_path", "expired", "overlong", "tampered", "duplicate"])
def test_new_endpoint_fails_closed_on_bad_signed_inputs(fault):
    data = payload()
    if fault == "expired":
        data["authorizedContext"]["expiresAt"] = int(time.time()) - 1
    elif fault == "overlong":
        data["authorizedContext"]["expiresAt"] += 1
    elif fault == "duplicate":
        data["providedToolResults"].append(data["providedToolResults"][0])
    body, headers = signed(data, path="/api/agent/chat" if fault == "wrong_path" else PATH)
    if fault == "tampered":
        body = body.replace(b"MAT1", b"MAT2")
    application = create_app(
        Settings(_env_file=None, shared_secret=SECRET, llm_api_key=""), Retriever()
    )
    with TestClient(application) as client:
        response = client.post(PATH, content=body, headers=headers)
        assert response.status_code == 401 and response.json() == {"error": "UNAUTHORIZED"}


def test_request_uuid_must_match_nonce_for_multi_endpoint():
    from app.multi_schemas import MultiAgentRequest

    body, headers = signed()
    nonce = str(uuid4())
    headers["X-MES-Nonce"] = nonce
    stamp = headers["X-MES-Timestamp"]
    canonical = f"POST\n{PATH}\n{stamp}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"
    headers["X-MES-Signature"] = hmac.new(
        SECRET.encode(), canonical.encode(), hashlib.sha256
    ).hexdigest()
    with pytest.raises(AuthError):
        RequestVerifier(SECRET).verify(body, headers, path=PATH, request_model=MultiAgentRequest)


def test_new_endpoint_rejects_cookie_only_malformed_headers_and_large_body():
    application = create_app(
        Settings(_env_file=None, shared_secret=SECRET, llm_api_key=""), Retriever()
    )
    with TestClient(application) as client:
        body, headers = signed()
        bad_headers = [
            {"Cookie": "admin=true"},
            {**headers, "X-MES-Signature": "not-a-signature"},
            {**headers, "X-MES-Timestamp": "invalid"},
            {**headers, "X-MES-Nonce": "invalid"},
        ]
        for bad in bad_headers:
            result = client.post(PATH, content=body, headers=bad)
            assert result.status_code == 401 and result.json() == {"error": "UNAUTHORIZED"}
        assert client.post(PATH, content=b" " * 65537, headers=headers).status_code == 401


def test_application_lifespan_closes_both_model_clients():
    application = create_app(
        Settings(_env_file=None, shared_secret=SECRET, llm_api_key=""), Retriever()
    )
    with TestClient(application):
        single = application.state.workflow.model.client
        multi = application.state.multi_workflow.model.client
        assert not single.is_closed and not multi.is_closed
    assert single.is_closed and multi.is_closed
