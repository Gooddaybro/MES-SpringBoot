"""Opt-in real FastAPI/LangGraph business smoke server without provider keys or a PDF index."""

import argparse
from pathlib import Path

import uvicorn

from app.config import Settings
from app.main import create_app


class NoKnowledgeInBusinessSmoke:
    def search(self, *args, **kwargs):
        raise AssertionError("The business smoke must not invoke PDF retrieval")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--secret-file", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8002)
    args = parser.parse_args()
    settings = Settings(
        _env_file=None,
        shared_secret=args.secret_file.read_text(encoding="utf-8").strip(),
        llm_api_key="",
        jina_api_key="",
    )
    application = create_app(settings, retriever=NoKnowledgeInBusinessSmoke())
    uvicorn.run(application, host="127.0.0.1", port=args.port)


if __name__ == "__main__":
    main()
