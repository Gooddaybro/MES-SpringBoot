from dataclasses import dataclass
from pathlib import PurePath

from pydantic import Field, field_validator

from app.schemas import Scope, StrictModel


class DocumentSpec(StrictModel):
    documentId: str = Field(min_length=1, max_length=80)
    filename: str = Field(min_length=1, max_length=255)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    scope: Scope
    version: str = Field(default="unknown", max_length=80)
    approved: bool = False
    category: str = "manufacturing-reference"

    @field_validator("filename")
    @classmethod
    def filename_only(cls, value: str) -> str:
        if (
            "/" in value
            or "\\" in value
            or PurePath(value).name != value
            or not value.lower().endswith(".pdf")
        ):
            raise ValueError("MANIFEST_FILENAME_INVALID")
        return value


@dataclass(frozen=True)
class Chunk:
    chunkId: str
    documentId: str
    filename: str
    documentVersion: str
    contentHash: str
    pageNumber: int
    scope: str
    text: str


@dataclass(frozen=True)
class RetrievedDocument:
    chunkId: str
    documentId: str
    filename: str
    documentVersion: str
    pageNumber: int
    scope: str
    text: str
    score: float


@dataclass(frozen=True)
class SearchResult:
    status: str
    documents: list[RetrievedDocument]
