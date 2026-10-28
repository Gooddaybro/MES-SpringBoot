import logging
import re

from app.rag.models import SearchResult
from app.rag.ranking import rerank
from app.rag.store import VectorStore

logger = logging.getLogger(__name__)


class KnowledgeRetriever:
    def __init__(
        self,
        store: VectorStore,
        embeddings,
        *,
        top_k: int = 5,
        min_similarity: float = 0.38,
        max_context_characters: int = 6000,
    ):
        self.store, self.embeddings = store, embeddings
        self.top_k, self.min_similarity = top_k, min_similarity
        self.max_context_characters = max_context_characters

    def search(self, query: str, scopes: list[str]) -> SearchResult:
        try:
            catalog = self.store.catalog()
            active = [
                d for d in catalog.values() if d.get("approved") and d.get("chunkCount", 0) > 0
            ]
            if not active:
                return SearchResult("NOT_INDEXED", [])
            if not scopes or not any(d["scope"] in scopes for d in active):
                return SearchResult("FORBIDDEN", [])
            vector = self.embeddings.embed_query(query[:6000])
            result = self.store.search(vector, scopes, top_k=30, min_similarity=self.min_similarity)
            # Specific mixed identifiers must have literal evidence; nearby generic SOPs cannot
            # establish facts about an invented product/model number.
            identifiers = re.findall(
                r"(?<![A-Za-z0-9._/-])(?=[A-Za-z0-9._/-]*[A-Za-z])"
                r"(?=[A-Za-z0-9._/-]*[0-9])[A-Za-z0-9][A-Za-z0-9._/-]{2,254}"
                r"(?![A-Za-z0-9._/-])",
                query,
            )
            if identifiers:
                result = [
                    d
                    for d in result
                    if all(code in d.text or code in d.filename for code in identifiers)
                ]
            bounded, used = [], 0
            for doc in rerank(query, result)[: self.top_k]:
                if used + len(doc.text) <= self.max_context_characters:
                    bounded.append(doc)
                    used += len(doc.text)
            return SearchResult("SUCCESS" if bounded else "NO_MATCH", bounded)
        except Exception:  # noqa: BLE001 - do not leak embedding bodies or private source text.
            # No upstream exception text: provider bodies can contain keys or private document text.
            logger.warning("retrieval_failed code=RETRIEVAL_ERROR")
            return SearchResult("ERROR", [])
