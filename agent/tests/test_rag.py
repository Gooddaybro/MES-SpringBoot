import hashlib
import math

import httpx
import pytest
from app.rag.embeddings import EmbeddingError, JinaEmbeddings
from app.rag.loader import load_pages
from app.rag.models import DocumentSpec
from app.rag.retriever import KnowledgeRetriever
from app.rag.splitter import split_pages
from app.rag.store import VectorStore
from reportlab.pdfgen import canvas


class TestEmbeddings:
    """Test-only vectors, never used by real indexing or recorded retrieval evaluation."""

    __test__ = False
    identity = "test-only-vectors-4"
    dimensions = 4

    def embed_documents(self, texts):
        return [self.vector(t) for t in texts]

    def embed_query(self, text):
        return self.vector(text)

    @staticmethod
    def vector(text):
        tokens = ["quality", "routing", "secret", "unrelated"]
        v = [float(text.lower().count(t)) for t in tokens]
        norm = math.sqrt(sum(x * x for x in v))
        return [x / norm for x in v] if norm else [0.0, 0.0, 0.0, 1.0]


def spec(name="quality.pdf", scope="public", digest="a" * 64):
    return DocumentSpec(
        documentId="doc-" + name.replace(".pdf", ""),
        filename=name,
        sha256=digest,
        scope=scope,
        version="unknown",
        approved=True,
    )


def test_real_pdf_page_numbers_and_scan_page(tmp_path):
    path = tmp_path / "sample.pdf"
    pdf = canvas.Canvas(str(path))
    pdf.drawString(40, 720, "Quality control on physical page one.")
    pdf.showPage()
    pdf.showPage()  # A genuinely empty page, no OCR text.
    pdf.drawString(40, 720, "Routing on physical page three.")
    pdf.save()
    pages = load_pages(path)
    assert len(pages) == 3
    assert pages[0].pageNumber == 1 and "Quality" in pages[0].text
    assert pages[1].pageNumber == 2 and pages[1].text == ""
    assert pages[2].pageNumber == 3 and "Routing" in pages[2].text


def test_chinese_split_preserves_source_page_and_bounded_overlap():
    from app.rag.loader import Page

    text = "第一章 质量。\n" + "质量数据应当完整。工序记录需要保留。\n" * 120
    pages = [Page(1, text), Page(2, "第二页独立证据。")]
    chunks = split_pages(pages, spec(), chunk_size=240, overlap=40)
    assert len(chunks) > 3
    assert all(len(c.text) <= 240 and c.text in pages[c.pageNumber - 1].text for c in chunks)
    assert len({c.chunkId for c in chunks}) == len(chunks)
    assert chunks[-1].pageNumber == 2
    assert split_pages(pages, spec(), chunk_size=240, overlap=40) == chunks


def test_chroma_idempotence_update_delete_and_acl(tmp_path):
    from app.rag.loader import Page

    embedder = TestEmbeddings()
    store = VectorStore(tmp_path, embedder.identity)
    public = spec()
    chunks = split_pages([Page(1, "quality quality public evidence")], public)
    store.replace_document(public, chunks, embedder.embed_documents([c.text for c in chunks]))
    store.replace_document(public, chunks, embedder.embed_documents([c.text for c in chunks]))
    assert store.count() == 1
    private = spec("secret.pdf", "restricted")
    pchunks = split_pages([Page(2, "quality secret restricted evidence")], private)
    store.replace_document(private, pchunks, embedder.embed_documents([c.text for c in pchunks]))
    retriever = KnowledgeRetriever(store, embedder, min_similarity=0.7)
    result = retriever.search("quality", ["public"])
    assert result.status == "SUCCESS" and len(result.documents) == 1
    assert result.documents[0].scope == "public"
    assert retriever.search("secret", []).status == "FORBIDDEN"
    assert retriever.search("unrelated", ["public"]).status == "NO_MATCH"
    # A metadata-only ACL change must not leave a publicly searchable old version.
    restricted = spec(scope="restricted")
    store.replace_document(restricted, chunks, embedder.embed_documents([c.text for c in chunks]))
    assert retriever.search("quality", ["public"]).status == "FORBIDDEN"
    # Changed content removes old chunks after publishing the new version.
    changed = spec(scope="restricted", digest="b" * 64)
    newer = split_pages([Page(3, "routing new version")], changed)
    store.replace_document(changed, newer, embedder.embed_documents([c.text for c in newer]))
    assert store.count() == 2
    newer_result = retriever.search("routing", ["restricted"])
    assert newer_result.documents[0].pageNumber == 3
    store.delete_document(public.documentId)
    assert store.count() == 1
    assert public.documentId not in store.catalog()


def test_empty_index_and_embedding_failure_are_different(tmp_path):
    from app.rag.loader import Page

    embedder = TestEmbeddings()
    store = VectorStore(tmp_path, embedder.identity)
    retriever = KnowledgeRetriever(store, embedder)
    assert retriever.search("quality", ["public"]).status == "NOT_INDEXED"
    doc = spec()
    chunks = split_pages([Page(1, "quality")], doc)
    store.replace_document(doc, chunks, embedder.embed_documents([c.text for c in chunks]))

    class Broken(TestEmbeddings):
        def embed_query(self, text):
            raise EmbeddingError("EMBEDDING_UNAVAILABLE")

    assert KnowledgeRetriever(store, Broken()).search("quality", ["public"]).status == "ERROR"


@pytest.mark.parametrize("bad", ["../escape.pdf", "D:/private.pdf", "dir\\secret.pdf"])
def test_manifest_cannot_escape_pdf_root(bad):
    with pytest.raises(ValueError):
        spec(bad)


def test_jina_uses_separate_tasks_and_validates_vectors():
    calls = []

    def handler(request):
        import json

        data = json.loads(request.content)
        calls.append(data)
        assert request.headers["authorization"] == "Bearer fake-test-key"
        return httpx.Response(
            200,
            json={
                "data": [
                    {"index": i, "embedding": [0.0, 0.0, 0.0, 1.0]}
                    for i, _ in enumerate(data["input"])
                ]
            },
        )

    client = httpx.Client(transport=httpx.MockTransport(handler))
    embedding = JinaEmbeddings("fake-test-key", dimensions=4, client=client)
    assert len(embedding.embed_documents(["a", "b"])) == 2
    assert embedding.embed_query("a") == [0.0, 0.0, 0.0, 1.0]
    assert [c["task"] for c in calls] == ["retrieval.passage", "retrieval.query"]
    assert all(c["dimensions"] == 4 for c in calls)


@pytest.mark.parametrize(
    "payload",
    [
        {"data": []},
        {"data": [{"index": 0, "embedding": [1.0, 2.0]}]},
        {"data": [{"index": 0, "embedding": [0.0, 0.0, 0.0, 0.0]}]},
        {"data": [{"index": 0, "embedding": [True, 0.0, 0.0, 1.0]}]},
    ],
)
def test_jina_invalid_response_is_not_fake_success(payload):
    client = httpx.Client(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=payload))
    )
    with pytest.raises(EmbeddingError):
        JinaEmbeddings("test-key", dimensions=4, client=client).embed_query("quality")


def test_pdf_hash_matches_actual_bytes(tmp_path):
    from app.rag.loader import verified_path

    path = tmp_path / "quality.pdf"
    path.write_bytes(b"not-a-real-pdf")
    good = spec(digest=hashlib.sha256(path.read_bytes()).hexdigest())
    assert verified_path(tmp_path, good) == path
    with pytest.raises(ValueError, match="HASH_MISMATCH"):
        verified_path(tmp_path, spec())


def test_specific_terms_rerank_generic_semantic_hits_without_changing_scopes(tmp_path):
    from app.rag.loader import Page

    embedder = TestEmbeddings()
    store = VectorStore(tmp_path, embedder.identity)
    for filename, text in [
        ("generic.pdf", "quality " * 20),
        ("precise.pdf", "quality calibration traceability requirements"),
    ]:
        document = spec(filename)
        chunks = split_pages([Page(1, text)], document)
        vectors = [[1.0, 0.0, 0.0, 0.0]] if filename == "generic.pdf" else [[0.98, 0.2, 0.0, 0.0]]
        store.replace_document(document, chunks, vectors)
    result = KnowledgeRetriever(store, embedder, top_k=1).search(
        "quality calibration traceability", ["public"]
    )
    assert result.documents[0].filename == "precise.pdf"


def test_same_pdf_reparse_removes_obsolete_chunks_and_cache_resumes_vectors(tmp_path):
    from app.rag.loader import Page

    embedder = TestEmbeddings()
    store = VectorStore(tmp_path / "store", embedder.identity)
    document = spec()
    chunks = split_pages([Page(1, "quality"), Page(2, "routing")], document)
    store.replace_document(document, chunks, embedder.embed_documents([c.text for c in chunks]))
    store.replace_document(document, chunks[:1], embedder.embed_documents([chunks[0].text]))
    assert store.count() == 1
    calls = []

    def handler(req):
        calls.append(req)
        return httpx.Response(200, json={"data": [{"index": 0, "embedding": [1.0, 0.0, 0.0, 0.0]}]})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    embeddings = JinaEmbeddings(
        "fake-test-key", dimensions=4, client=client, cache_path=tmp_path / "cache.sqlite3"
    )
    assert embeddings.embed_documents(["approved source"]) == embeddings.embed_documents(
        ["approved source"]
    )
    assert len(calls) == 1
