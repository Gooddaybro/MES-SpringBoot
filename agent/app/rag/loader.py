import hashlib
from dataclasses import dataclass
from pathlib import Path

from pypdf import PdfReader

from app.rag.models import DocumentSpec


@dataclass(frozen=True)
class Page:
    pageNumber: int
    text: str


def verified_path(root: Path, document: DocumentSpec) -> Path:
    if not document.approved:
        raise ValueError("DOCUMENT_NOT_APPROVED")
    root = root.resolve(strict=True)
    path = (root / document.filename).resolve(strict=True)
    if path.parent != root:
        raise ValueError("DOCUMENT_OUTSIDE_ROOT")
    if path.stat().st_size > 128 * 1024 * 1024:
        raise ValueError("PDF_TOO_LARGE")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != document.sha256:
        raise ValueError("HASH_MISMATCH")
    return path


def load_pages(path: Path) -> list[Page]:
    with path.open("rb") as stream:
        pdf = PdfReader(stream)
        if pdf.is_encrypted:
            raise ValueError("PDF_ENCRYPTED")
        if len(pdf.pages) > 2000:
            raise ValueError("PDF_TOO_MANY_PAGES")
        return [
            Page(
                i + 1, (page.extract_text() or "").replace("\x00", "").replace("\r\n", "\n").strip()
            )
            for i, page in enumerate(pdf.pages)
        ]
