import hashlib
import json
from dataclasses import asdict
from importlib.metadata import version
from pathlib import Path

import chromadb
from chromadb.config import Settings as ChromaSettings

from app.rag.models import Chunk, DocumentSpec, RetrievedDocument
from app.rag.splitter import CHUNKER_VERSION

PARSER_VERSION = f"pypdf-{version('pypdf')}:fonttools-{version('fonttools')}"


class VectorStore:
    def __init__(self, data_dir: Path, embedding_identity: str):
        data_dir.mkdir(parents=True, exist_ok=True)
        fingerprint = hashlib.sha256(embedding_identity.encode()).hexdigest()[:16]
        self.catalog_path = data_dir / f"catalog-{fingerprint}.json"
        self.client = chromadb.PersistentClient(
            path=str(data_dir / "chroma"),
            settings=ChromaSettings(anonymized_telemetry=False, allow_reset=False),
        )
        self.collection = self.client.get_or_create_collection(
            name=f"mes_knowledge_{fingerprint}",
            metadata={"hnsw:space": "cosine"},
            embedding_function=None,
        )

    def catalog(self) -> dict:
        if not self.catalog_path.exists():
            return {}
        return json.loads(self.catalog_path.read_text(encoding="utf-8"))

    def _save_catalog(self, catalog: dict):
        temp = self.catalog_path.with_suffix(".tmp")
        temp.write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(self.catalog_path)

    def is_current(self, document: DocumentSpec) -> bool:
        current = self.catalog().get(document.documentId, {})
        return (
            all(current.get(k) == v for k, v in document.model_dump().items())
            and current.get("chunkerVersion") == CHUNKER_VERSION
            and current.get("parserVersion") == PARSER_VERSION
        )

    def replace_document(
        self,
        document: DocumentSpec,
        chunks: list[Chunk],
        vectors: list[list[float]],
        *,
        page_count: int = 0,
    ):
        if not document.approved or len(chunks) != len(vectors):
            raise ValueError("INVALID_INDEX_INPUT")
        if chunks:
            # Publish a new active hash only after all upserts have succeeded.
            for offset in range(0, len(chunks), 128):
                batch = chunks[offset : offset + 128]
                metadata = []
                for chunk in batch:
                    meta = asdict(chunk)
                    meta.pop("text")
                    meta["scope"] = document.scope
                    meta["documentVersion"] = document.version
                    metadata.append(meta)
                self.collection.upsert(
                    ids=[c.chunkId for c in batch],
                    documents=[c.text for c in batch],
                    embeddings=vectors[offset : offset + 128],
                    metadatas=metadata,
                )
        catalog = self.catalog()
        catalog[document.documentId] = {
            **document.model_dump(),
            "pageCount": page_count,
            "chunkCount": len(chunks),
            "indexStatus": "INDEXED" if chunks else "NO_EXTRACTABLE_TEXT",
            "chunkerVersion": CHUNKER_VERSION,
            "parserVersion": PARSER_VERSION,
        }
        self._save_catalog(catalog)
        if chunks:
            current_ids = {c.chunkId for c in chunks}
            stored = self.collection.get(where={"documentId": document.documentId}, include=[])[
                "ids"
            ]
            obsolete = [chunk_id for chunk_id in stored if chunk_id not in current_ids]
            if obsolete:
                self.collection.delete(ids=obsolete)
        else:
            self.collection.delete(where={"documentId": document.documentId})

    def delete_document(self, document_id: str):
        catalog = self.catalog()
        catalog.pop(document_id, None)
        # Revoke catalog access first. Failed cleanup cannot reveal a deleted document.
        self._save_catalog(catalog)
        self.collection.delete(where={"documentId": document_id})

    def count(self) -> int:
        return self.collection.count()

    def search(
        self,
        vector: list[float],
        scopes: list[str],
        *,
        top_k: int = 5,
        min_similarity: float = 0.38,
    ) -> list[RetrievedDocument]:
        allowed = [
            d
            for d in self.catalog().values()
            if d.get("approved") and d.get("scope") in scopes and d.get("chunkCount", 0) > 0
        ]
        if not allowed:
            return []
        versions = [
            {"$and": [{"documentId": d["documentId"]}, {"contentHash": d["sha256"]}]}
            for d in allowed
        ]
        active = versions[0] if len(versions) == 1 else {"$or": versions}
        where = {"$and": [{"scope": {"$in": list(set(scopes))}}, active]}
        result = self.collection.query(
            query_embeddings=[vector],
            n_results=min(top_k, self.count()),
            where=where,
            include=["documents", "metadatas", "distances"],
        )
        documents = []
        for text, meta, distance in zip(
            result["documents"][0], result["metadatas"][0], result["distances"][0]
        ):
            score = max(-1.0, min(1.0, 1.0 - float(distance)))
            if score >= min_similarity:
                documents.append(
                    RetrievedDocument(
                        chunkId=meta["chunkId"],
                        documentId=meta["documentId"],
                        filename=meta["filename"],
                        documentVersion=meta["documentVersion"],
                        pageNumber=int(meta["pageNumber"]),
                        scope=meta["scope"],
                        text=text,
                        score=score,
                    )
                )
        return documents
