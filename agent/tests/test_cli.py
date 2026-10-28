import json

from app import cli
from app.config import Settings
from app.rag.loader import Page
from app.rag.retriever import KnowledgeRetriever
from app.rag.splitter import split_pages
from app.rag.store import VectorStore
from test_rag import TestEmbeddings, spec


def test_targeted_ingest_revokes_previously_approved_document(tmp_path, monkeypatch):
    embedding = TestEmbeddings()
    embedding.model = "test-only"
    embedding.request_count = 0
    embedding.usage_tokens = 0
    embedding.close = lambda: None
    store = VectorStore(tmp_path / "index", embedding.identity)
    document = spec()
    chunks = split_pages([Page(1, "quality authorized source")], document)
    store.replace_document(document, chunks, embedding.embed_documents([c.text for c in chunks]))
    retriever = KnowledgeRetriever(store, embedding)
    assert retriever.search("quality", ["public"]).status == "SUCCESS"
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"documents": [{**document.model_dump(), "approved": False}]}))
    settings = Settings(_env_file=None, manifest_path=manifest, data_dir=tmp_path / "index")
    monkeypatch.setattr(cli, "create_retriever", lambda _: retriever)
    report = cli.ingest(settings, only=document.documentId)
    assert report["documents"][0]["status"] == "NOT_APPROVED"
    assert document.documentId not in store.catalog()
    assert store.count() == 0
    assert retriever.search("quality", ["public"]).status == "NOT_INDEXED"
