import hashlib
import hmac
import json
import time
from uuid import uuid4

import pytest
from app.schemas import AgentRequest
from app.security import AuthError, RequestVerifier

SECRET = "test-service-secret-32-chars-minimum-only"


def signed_request(**changes):
    now = int(time.time())
    nonce = str(uuid4())
    payload = {
        "requestId": nonce,
        "question": "文档中质量管理数字化有哪些原则？",
        "intentHint": "KNOWLEDGE",
        "messages": [],
        "authorizedContext": {
            "subject": "pseudonymous-user",
            "scopes": ["public"],
            "allowedTools": ["search_sop_knowledge"],
            "issuedAt": now,
            "expiresAt": now + 60,
        },
        "providedToolResults": [],
    }
    payload.update(changes)
    body = json.dumps(payload, ensure_ascii=False).encode()
    canonical = f"POST\n/api/agent/chat\n{now}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"
    signature = hmac.new(SECRET.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return body, {"X-MES-Timestamp": str(now), "X-MES-Nonce": nonce, "X-MES-Signature": signature}


def verify(verifier, body, headers, **kwargs):
    return verifier.verify(body, headers, **kwargs)


def test_valid_signature_and_no_replay():
    verifier = RequestVerifier(SECRET)
    body, headers = signed_request()
    result = verify(verifier, body, headers)
    assert isinstance(result, AgentRequest)
    assert result.authorizedContext.scopes == ["public"]
    with pytest.raises(AuthError, match="UNAUTHORIZED"):
        verify(verifier, body, headers)


@pytest.mark.parametrize("mutation", ["body", "signature", "nonce", "timestamp", "path"])
def test_tampering_rejected(mutation):
    body, headers = signed_request()
    kwargs = {}
    if mutation == "body":
        body = body.replace(b"public", b"secret")
    elif mutation == "signature":
        headers["X-MES-Signature"] = "0" * 64
    elif mutation == "nonce":
        headers["X-MES-Nonce"] = str(uuid4())
    elif mutation == "timestamp":
        headers["X-MES-Timestamp"] = str(int(time.time()) - 61)
    else:
        kwargs["path"] = "/api/agent/other"
    with pytest.raises(AuthError):
        verify(RequestVerifier(SECRET), body, headers, **kwargs)


def test_expired_or_overlong_context_rejected():
    for delta in [-1, 61]:
        body, _ = signed_request()
        data = json.loads(body)
        now = int(time.time())
        data["authorizedContext"]["expiresAt"] = now + delta
        body, headers = signed_request(authorizedContext=data["authorizedContext"])
        with pytest.raises(AuthError):
            verify(RequestVerifier(SECRET), body, headers)


def test_nonce_and_request_id_must_match():
    body, headers = signed_request(requestId=str(uuid4()))
    with pytest.raises(AuthError):
        verify(RequestVerifier(SECRET), body, headers)


def test_unknown_tools_and_client_acl_fields_rejected():
    body, _ = signed_request()
    data = json.loads(body)
    data["authorizedContext"]["allowedTools"] = ["execute_sql"]
    body, headers = signed_request(authorizedContext=data["authorizedContext"])
    with pytest.raises(AuthError):
        verify(RequestVerifier(SECRET), body, headers)
    body, headers = signed_request(scopes=["restricted"])
    with pytest.raises(AuthError):
        verify(RequestVerifier(SECRET), body, headers)


def test_short_missing_secret_and_overlarge_body_fail_closed():
    body, headers = signed_request()
    for secret in ["", "short"]:
        with pytest.raises(AuthError):
            verify(RequestVerifier(secret), body, headers)
    with pytest.raises(AuthError):
        verify(RequestVerifier(SECRET), b" " * 65537, headers)


def test_replay_cache_is_bounded_and_expires():
    verifier = RequestVerifier(SECRET, max_nonces=1)
    first, h1 = signed_request()
    second, h2 = signed_request()
    verify(verifier, first, h1)
    with pytest.raises(AuthError):
        verify(verifier, second, h2)
    assert len(verifier.nonces) == 1
