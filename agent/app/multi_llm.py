"""Bounded DeepSeek-compatible model turns; tools never leave the worker's fact snapshot."""

import httpx

from app.config import Settings
from app.llm import ModelError


class MultiAgentModel:
    def __init__(self, settings: Settings, *, client: httpx.AsyncClient | None = None):
        self.settings = settings
        self.configured = settings.llm_configured
        self.client = client or httpx.AsyncClient(
            timeout=settings.llm_timeout_seconds, follow_redirects=False, trust_env=False
        )

    async def turn(self, messages: list[dict], tools: list[dict]) -> dict:
        if not self.configured:
            raise ModelError("LLM_NOT_CONFIGURED")
        try:
            response = await self.client.post(
                self.settings.llm_base_url.rstrip("/") + "/chat/completions",
                json={
                    "model": self.settings.llm_model,
                    "stream": False,
                    "max_tokens": 2000,
                    "thinking": {"type": "disabled"},
                    "messages": messages,
                    "tools": tools,
                    "tool_choice": "auto",
                },
                headers={"Authorization": "Bearer " + self.settings.llm_api_key.get_secret_value()},
            )
            if response.status_code != 200:
                raise ModelError("LLM_UNAVAILABLE")
            if len(response.content) > 65536:
                raise ModelError("LLM_INVALID_RESPONSE")
            message = response.json()["choices"][0]["message"]
            if not isinstance(message, dict):
                raise ModelError("LLM_INVALID_RESPONSE")
            return message
        except httpx.TimeoutException:
            raise ModelError("LLM_TIMEOUT") from None
        except httpx.HTTPError:
            raise ModelError("LLM_UNAVAILABLE") from None
        except (ValueError, KeyError, TypeError, IndexError):
            raise ModelError("LLM_INVALID_RESPONSE") from None

    async def close(self):
        await self.client.aclose()
