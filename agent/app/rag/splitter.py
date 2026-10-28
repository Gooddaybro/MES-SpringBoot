from langchain_text_splitters import RecursiveCharacterTextSplitter

from app.rag.loader import Page
from app.rag.models import Chunk, DocumentSpec

CHUNKER_VERSION = "physical-page-recursive-800-120-v1"


def split_pages(
    pages: list[Page], document: DocumentSpec, *, chunk_size: int = 800, overlap: int = 120
) -> list[Chunk]:
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size,
        chunk_overlap=overlap,
        separators=["\n\n", "\n", "。", "；", "！", "？", ". ", "; ", " ", ""],
        keep_separator="end",
    )
    chunks = []
    for page in pages:
        for i, text in enumerate(splitter.split_text(page.text)):
            if not text.strip():
                continue
            if text not in page.text:
                raise ValueError("CHUNK_NOT_EXACT_SOURCE")
            chunks.append(
                Chunk(
                    chunkId=f"{document.documentId}:{document.sha256[:16]}:p{page.pageNumber}:c{i}",
                    documentId=document.documentId,
                    filename=document.filename,
                    documentVersion=document.version,
                    contentHash=document.sha256,
                    pageNumber=page.pageNumber,
                    scope=document.scope,
                    text=text,
                )
            )
    return chunks
