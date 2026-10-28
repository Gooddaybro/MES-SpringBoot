"""Private passage-vector cache to resume rate-limited indexing without re-billing successes."""

import hashlib
import json
import sqlite3
from pathlib import Path


class PassageCache:
    def __init__(self, path: Path, identity: str):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path, self.identity = path, identity
        with sqlite3.connect(path) as connection:
            connection.execute(
                "CREATE TABLE IF NOT EXISTS vectors (key TEXT PRIMARY KEY, vector TEXT NOT NULL)"
            )

    def _key(self, text: str) -> str:
        return hashlib.sha256((self.identity + "\nretrieval.passage\n" + text).encode()).hexdigest()

    def get_many(self, texts: list[str]) -> list[list[float] | None]:
        with sqlite3.connect(self.path) as connection:
            values = []
            for text in texts:
                row = connection.execute(
                    "SELECT vector FROM vectors WHERE key=?", (self._key(text),)
                ).fetchone()
                values.append(json.loads(row[0]) if row else None)
            return values

    def put_many(self, texts: list[str], vectors: list[list[float]]):
        with sqlite3.connect(self.path) as connection:
            connection.executemany(
                "INSERT OR REPLACE INTO vectors(key,vector) VALUES (?,?)",
                [(self._key(text), json.dumps(vector)) for text, vector in zip(texts, vectors)],
            )
