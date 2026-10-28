"""Jina handles vectors only. No credentials, bodies or error responses are logged."""

import math
import time
from pathlib import Path

import httpx
from langchain_core.embeddings import Embeddings

from app.rag.cache import PassageCache


class EmbeddingError(Exception):
    pass


class JinaEmbeddings(Embeddings):
    def __init__(
        self,
        api_key: str,
        *,
        model: str = "jina-embeddings-v3",
        dimensions: int = 512,
        url: str = "https://api.jina.ai/v1/embeddings",
        timeout: float = 20,
        batch_size: int = 24,
        client: httpx.Client | None = None,
        cache_path: Path | None = None,
        retry_attempts: int = 0,
    ):
        self.api_key = api_key
        self.model, self.dimensions, self.url = model, dimensions, url
        self.batch_size = batch_size
        self.identity = f"jina:{model}:{dimensions}:cosine:page800-overlap120-v1"
        self.cache = PassageCache(cache_path, self.identity) if cache_path else None
        self.retry_attempts = retry_attempts
        self.request_count = 0
        self.usage_tokens = 0
        self.client = client or httpx.Client(
            timeout=timeout, follow_redirects=False, trust_env=False
        )

    def _embed(self, texts: list[str], task: str) -> list[list[float]]:
        if not self.api_key.strip():
            raise EmbeddingError("EMBEDDING_NOT_CONFIGURED")
        try:
            for attempt in range(self.retry_attempts + 1):
                self.request_count += 1
                response = self.client.post(
                    self.url,
                    headers={"Authorization": f"Bearer {self.api_key}"},
                    json={
                        "model": self.model,
                        "task": task,
                        "dimensions": self.dimensions,
                        "normalized": True,
                        "embedding_type": "float",
                        "input": texts,
                    },
                )
                if response.status_code not in (429, 503) or attempt == self.retry_attempts:
                    break
                try:
                    retry_after = float(response.headers.get("Retry-After", "0"))
                except ValueError:
                    retry_after = 0
                time.sleep(min(30, max(2 ** (attempt + 1), retry_after)))
            if response.status_code != 200:
                raise EmbeddingError(f"EMBEDDING_HTTP_{response.status_code}")
            if len(response.content) > 8 * 1024 * 1024:
                raise EmbeddingError("EMBEDDING_RESPONSE_TOO_LARGE")
            payload = response.json()
            tokens = payload.get("usage", {}).get("total_tokens", 0)
            if type(tokens) is int and tokens >= 0:
                self.usage_tokens += tokens
            data = payload["data"]
            if len(data) != len(texts):
                raise ValueError()
            data = sorted(data, key=lambda item: item["index"])
            vectors = []
            for i, item in enumerate(data):
                vector = item["embedding"]
                if (
                    item["index"] != i
                    or len(vector) != self.dimensions
                    or any(type(x) not in (int, float) or not math.isfinite(x) for x in vector)
                ):
                    raise ValueError()
                norm = math.sqrt(sum(float(x) ** 2 for x in vector))
                if norm < 1e-8:
                    raise ValueError()
                vectors.append([float(x) / norm for x in vector])
            return vectors
        except httpx.TimeoutException:
            raise EmbeddingError("EMBEDDING_TIMEOUT") from None
        except httpx.HTTPError:
            raise EmbeddingError("EMBEDDING_UNAVAILABLE") from None
        except (ValueError, KeyError, TypeError, OverflowError):
            raise EmbeddingError("EMBEDDING_INVALID_RESPONSE") from None

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        vectors = []
        for offset in range(0, len(texts), self.batch_size):
            batch = texts[offset : offset + self.batch_size]
            cached = self.cache.get_many(batch) if self.cache else [None] * len(batch)
            missing = [text for text, vector in zip(batch, cached) if vector is None]
            fresh = self._embed(missing, "retrieval.passage") if missing else []
            if self.cache and missing:
                self.cache.put_many(missing, fresh)
            iterator = iter(fresh)
            vectors.extend(vector if vector is not None else next(iterator) for vector in cached)
        return vectors

    def embed_query(self, text: str) -> list[float]:
        return self._embed([text], "retrieval.query")[0]

    def close(self):
        self.client.close()
