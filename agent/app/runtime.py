from app.config import Settings
from app.rag.embeddings import JinaEmbeddings
from app.rag.retriever import KnowledgeRetriever
from app.rag.store import VectorStore


def create_retriever(settings: Settings) -> KnowledgeRetriever:
    embeddings = JinaEmbeddings(
        settings.jina_api_key.get_secret_value(),
        model=settings.embedding_model,
        dimensions=settings.embedding_dimensions,
        url=settings.embedding_base_url,
        timeout=settings.embedding_timeout_seconds,
        batch_size=settings.embedding_batch_size,
        cache_path=settings.data_dir / "passage-cache.sqlite3",
    )
    store = VectorStore(settings.data_dir, embeddings.identity)
    return KnowledgeRetriever(
        store,
        embeddings,
        top_k=settings.top_k,
        min_similarity=settings.min_similarity,
        max_context_characters=settings.max_context_characters,
    )
