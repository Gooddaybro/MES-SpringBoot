"""Bounded local BM25 reranking over ACL-filtered vector candidates, no external calls."""

import math
import re
from collections import Counter

from app.rag.models import RetrievedDocument


def terms(text: str) -> list[str]:
    tokens = re.findall(r"[a-z0-9]+", text.lower())
    for sequence in re.findall(r"[\u3400-\u9fff]+", text):
        tokens.extend(sequence[i : i + 2] for i in range(len(sequence) - 1))
    return tokens


def rerank(query: str, candidates: list[RetrievedDocument]) -> list[RetrievedDocument]:
    if not candidates:
        return []
    counts = [Counter(terms(d.filename + " " + d.text)) for d in candidates]
    lengths = [sum(c.values()) for c in counts]
    average = max(1, sum(lengths) / len(lengths))
    query_terms = set(terms(query))
    frequencies = {term: sum(term in count for count in counts) for term in query_terms}
    scores = []
    for count, length in zip(counts, lengths):
        score = 0.0
        for term in query_terms:
            frequency = count[term]
            idf = math.log(1 + (len(counts) - frequencies[term] + 0.5) / (frequencies[term] + 0.5))
            score += idf * frequency * 2.2 / (frequency + 1.2 * (0.25 + 0.75 * length / average))
        scores.append(score)
    maximum = max(scores, default=0) or 1
    combined = [0.65 * doc.score + 0.35 * score / maximum for doc, score in zip(candidates, scores)]
    return [
        doc for _, doc in sorted(zip(combined, candidates), key=lambda item: item[0], reverse=True)
    ]
