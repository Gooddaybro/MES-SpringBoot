"""HMAC proof of Java authorization. Single worker; never browser authentication."""

import hashlib
import hmac
import re
import threading
import time
from collections.abc import Mapping
from uuid import UUID

from pydantic import ValidationError

from app.schemas import AgentRequest


class AuthError(Exception):
    def __init__(self):
        super().__init__("UNAUTHORIZED")


class RequestVerifier:
    def __init__(self, secret: str, *, max_nonces: int = 4096):
        self.secret = secret
        self.max_nonces = max_nonces
        self.nonces: dict[str, int] = {}
        self.lock = threading.Lock()

    def verify(
        self,
        body: bytes,
        headers: Mapping[str, str],
        *,
        method: str = "POST",
        path: str = "/api/agent/chat",
    ) -> AgentRequest:
        if len(self.secret) < 32 or len(body) > 65536:
            raise AuthError()
        values = {k.lower(): v for k, v in headers.items()}
        stamp = values.get("x-mes-timestamp", "")
        nonce = values.get("x-mes-nonce", "")
        signature = values.get("x-mes-signature", "")
        now = int(time.time())
        try:
            if not re.fullmatch(r"[0-9]{10,11}", stamp) or abs(now - int(stamp)) > 30:
                raise AuthError()
            if str(UUID(nonce)) != nonce or not re.fullmatch(r"[0-9a-f]{64}", signature):
                raise AuthError()
            canonical = f"{method}\n{path}\n{stamp}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"
            expected = hmac.new(
                self.secret.encode(), canonical.encode(), hashlib.sha256
            ).hexdigest()
            if not hmac.compare_digest(expected, signature):
                raise AuthError()
            data = AgentRequest.model_validate_json(body)
            context = data.authorizedContext
            if (
                data.requestId != nonce
                or context.issuedAt != int(stamp)
                or not (context.issuedAt < context.expiresAt <= context.issuedAt + 60)
                or context.expiresAt <= now
            ):
                raise AuthError()
            with self.lock:
                self.nonces = {n: expiry for n, expiry in self.nonces.items() if expiry > now}
                if nonce in self.nonces or len(self.nonces) >= self.max_nonces:
                    raise AuthError()
                self.nonces[nonce] = context.expiresAt
            return data
        except (ValueError, ValidationError, TypeError):
            raise AuthError() from None
