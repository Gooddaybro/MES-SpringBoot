"""Operator-only CLI; no public indexing/upload endpoints and no original PDF copies."""

import argparse
import json
import time
from datetime import UTC, datetime

from app.config import Settings
from app.rag.embeddings import EmbeddingError
from app.rag.loader import load_pages, verified_path
from app.rag.models import DocumentSpec
from app.rag.splitter import split_pages
from app.runtime import create_retriever


def ingest(settings: Settings, *, force: bool = False, only: str | None = None) -> dict:
    manifest = json.loads(settings.manifest_path.read_text(encoding="utf-8"))
    documents = [DocumentSpec.model_validate(d) for d in manifest["documents"]]
    retriever = create_retriever(settings)
    store, embeddings = retriever.store, retriever.embeddings
    embeddings.retry_attempts = 4  # Operator CLI only; API requests never wait through retries.
    if only and not any(d.documentId == only for d in documents):
        raise ValueError("UNKNOWN_DOCUMENT")
    approved = {d.documentId for d in documents if d.approved}
    if not only:
        for document_id in list(store.catalog()):
            if document_id not in approved:
                store.delete_document(document_id)
    report = {
        "startedAt": datetime.now(UTC).isoformat(),
        "provider": "Jina",
        "model": embeddings.model,
        "dimensions": embeddings.dimensions,
        "chatModelAttempted": False,
        "documents": [],
    }
    started = time.perf_counter()
    try:
        for document in documents:
            if only and document.documentId != only:
                continue
            item = {"documentId": document.documentId, "filename": document.filename}
            before = time.perf_counter()
            try:
                if not document.approved:
                    store.delete_document(document.documentId)
                    item["status"] = "NOT_APPROVED"
                else:
                    path = verified_path(settings.pdf_dir, document)
                    if not force and store.is_current(document):
                        item.update(
                            status="UNCHANGED",
                            chunkCount=store.catalog()[document.documentId]["chunkCount"],
                        )
                    else:
                        pages = load_pages(path)
                        chunks = split_pages(pages, document)
                        vectors = embeddings.embed_documents([c.text for c in chunks])
                        store.replace_document(document, chunks, vectors, page_count=len(pages))
                        item.update(
                            status="INDEXED" if chunks else "NO_EXTRACTABLE_TEXT",
                            pageCount=len(pages),
                            chunkCount=len(chunks),
                            textPages=sum(bool(p.text) for p in pages),
                        )
            except EmbeddingError as error:
                item.update(status="FAILED", reason=str(error))
            except ValueError as error:
                code = str(error)
                item.update(
                    status="FAILED",
                    reason=code
                    if code
                    in {
                        "DOCUMENT_NOT_APPROVED",
                        "HASH_MISMATCH",
                        "PDF_TOO_LARGE",
                        "DOCUMENT_OUTSIDE_ROOT",
                        "PDF_ENCRYPTED",
                        "PDF_TOO_MANY_PAGES",
                        "CHUNK_NOT_EXACT_SOURCE",
                    }
                    else "PDF_OR_INDEX_ERROR",
                )
            except Exception:  # noqa: BLE001 - third-party errors must not expose document data.
                item.update(status="FAILED", reason="PDF_OR_INDEX_ERROR")
            item["elapsedMs"] = round((time.perf_counter() - before) * 1000)
            report["documents"].append(item)
            print(json.dumps(item, ensure_ascii=False), flush=True)
    finally:
        report.update(
            elapsedMs=round((time.perf_counter() - started) * 1000),
            vectorCount=store.count(),
            embeddingRequests=embeddings.request_count,
            embeddingUsageTokens=embeddings.usage_tokens,
        )
        embeddings.close()
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    (settings.data_dir / "last-ingest.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return report


def main():
    parser = argparse.ArgumentParser(
        description="MES approved PDF vector index maintenance (stop API first)"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    index = subparsers.add_parser("ingest")
    index.add_argument("--force", action="store_true")
    index.add_argument("--document-id")
    subparsers.add_parser("status")
    delete = subparsers.add_parser("delete")
    delete.add_argument("document_id")
    arguments = parser.parse_args()
    settings = Settings()
    if arguments.command == "ingest":
        report = ingest(settings, force=arguments.force, only=arguments.document_id)
        failed = sum(d["status"] == "FAILED" for d in report["documents"])
        print(json.dumps({k: v for k, v in report.items() if k != "documents"}, ensure_ascii=False))
        raise SystemExit(1 if failed else 0)
    retriever = create_retriever(settings)
    try:
        if arguments.command == "delete":
            retriever.store.delete_document(arguments.document_id)
        print(
            json.dumps(
                {
                    "vectorCount": retriever.store.count(),
                    "registeredDocuments": len(retriever.store.catalog()),
                    "indexedDocuments": sum(
                        d.get("chunkCount", 0) > 0 for d in retriever.store.catalog().values()
                    ),
                }
            )
        )
    finally:
        retriever.embeddings.close()


if __name__ == "__main__":
    main()
