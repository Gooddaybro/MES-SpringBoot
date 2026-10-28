"""Real Jina + persistent Chroma evaluation, explicitly excluding chat-model accuracy."""

import argparse
import asyncio
import hashlib
import json
import re
import statistics
import time
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from app.config import AGENT_ROOT, Settings
from app.graph import KnowledgeWorkflow
from app.rag.loader import load_pages, verified_path
from app.rag.models import DocumentSpec
from app.runtime import create_retriever
from app.schemas import AgentRequest


def normalized(value):
    return re.sub(r"\s+", "", value)


async def evaluate(dataset: Path, output: Path):
    settings = Settings(llm_api_key="")  # User explicitly deferred all real chat calls.
    retriever = create_retriever(settings)
    workflow = KnowledgeWorkflow(settings, retriever)
    cases = [
        json.loads(line)
        for line in dataset.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    manifest = json.loads(settings.manifest_path.read_text(encoding="utf-8"))
    specs = {d["documentId"]: DocumentSpec.model_validate(d) for d in manifest["documents"]}
    page_cache = {}
    records = []
    try:
        for case in cases:
            if case.get("documentId"):
                doc = specs[case["documentId"]]
                if doc.documentId not in page_cache:
                    page_cache[doc.documentId] = load_pages(verified_path(settings.pdf_dir, doc))
                truth = "\n".join(
                    page_cache[doc.documentId][p - 1].text for p in case["pageNumbers"]
                )
                if not all(normalized(e) in normalized(truth) for e in case["evidence"]):
                    raise ValueError(f"GROUND_TRUTH_NOT_IN_PDF:{case['id']}")
            now = int(time.time())
            request = AgentRequest.model_validate(
                {
                    "requestId": str(uuid4()),
                    "question": case["question"],
                    "messages": [],
                    "intentHint": "KNOWLEDGE",
                    "providedToolResults": [],
                    "authorizedContext": {
                        "subject": "local-evaluation",
                        "scopes": case.get("scopes", ["public"]),
                        "allowedTools": ["search_sop_knowledge"],
                        "issuedAt": now,
                        "expiresAt": now + 60,
                    },
                }
            )
            response = await workflow.run(request)
            document_hit = any(c.documentId == case.get("documentId") for c in response.citations)
            page_hit = any(
                c.documentId == case.get("documentId")
                and c.pageNumber in case.get("pageNumbers", [])
                for c in response.citations
            )
            combined = normalized(
                "\n".join(
                    c.quote
                    for c in response.citations
                    if c.documentId == case.get("documentId")
                    and c.pageNumber in case.get("pageNumbers", [])
                )
            )
            evidence_hit = bool(case.get("evidence")) and all(
                normalized(e) in combined for e in case.get("evidence", [])
            )
            exact = True
            for citation in response.citations:
                doc = specs[citation.documentId]
                if doc.documentId not in page_cache:
                    page_cache[doc.documentId] = load_pages(verified_path(settings.pdf_dir, doc))
                exact = (
                    exact
                    and citation.quote in page_cache[doc.documentId][citation.pageNumber - 1].text
                )
            distinct_pages = len(
                {
                    c.pageNumber
                    for c in response.citations
                    if c.documentId == case.get("documentId")
                    and c.pageNumber in case.get("pageNumbers", [])
                }
            )
            passed = (
                distinct_pages >= case.get("minimumDistinctPages", 0)
                and response.status == case["expectedStatus"]
            ) and (
                document_hit and page_hit and evidence_hit
                if case.get("documentId")
                else not response.citations
            )
            records.append(
                {
                    "id": case["id"],
                    "question": case["question"],
                    "status": response.status,
                    "expectedStatus": case["expectedStatus"],
                    "passed": passed,
                    "documentHitAt5": document_hit,
                    "pageHitAt5": page_hit,
                    "expectedEvidencePresent": evidence_hit,
                    "expectedDistinctPagesRetrieved": distinct_pages,
                    "allQuotesExactInPhysicalPdfPage": exact,
                    "elapsedMs": response.elapsedMs,
                    "toolCalls": [c.model_dump() for c in response.toolCalls],
                    "modelAttempted": response.modelAttempted,
                    "modelConnected": response.modelConnected,
                    "citations": [
                        {**c.model_dump(), "quote": c.quote[:180]} for c in response.citations
                    ],
                }
            )
            print(
                json.dumps(
                    {
                        "id": case["id"],
                        "passed": passed,
                        "status": response.status,
                        "pageHitAt5": page_hit,
                        "elapsedMs": response.elapsedMs,
                    }
                ),
                flush=True,
            )
        positive = [r for r, c in zip(records, cases) if c.get("documentId")]
        negative = [r for r, c in zip(records, cases) if not c.get("documentId")]
        times = sorted(r["elapsedMs"] for r in records)
        summary = {
            "caseCount": len(records),
            "answerableCases": len(positive),
            "boundaryCases": len(negative),
            "passed": sum(r["passed"] for r in records),
            "documentRecallAt5": sum(r["documentHitAt5"] for r in positive) / len(positive),
            "physicalPageRecallAt5": sum(r["pageHitAt5"] for r in positive) / len(positive),
            "expectedEvidenceCoverageAt5": sum(r["expectedEvidencePresent"] for r in positive)
            / len(positive),
            "boundaryStatusAccuracy": sum(r["passed"] for r in negative) / len(negative)
            if negative
            else None,
            "allReturnedQuotesMatchPdfPages": all(
                r["allQuotesExactInPhysicalPdfPage"] for r in records
            ),
            "wrongToolCalls": sum(
                t["name"] != "search_sop_knowledge" for r in records for t in r["toolCalls"]
            ),
            "chatModelCalls": sum(r["modelAttempted"] for r in records),
            "chatModelAccuracy": None,
            "embeddingRequests": retriever.embeddings.request_count,
            "medianElapsedMs": statistics.median(times),
            "p95ElapsedMs": times[max(0, int(len(times) * 0.95) - 1)],
            "totalElapsedMs": sum(times),
            "vectorCount": retriever.store.count(),
        }
        report = {
            "measuredAt": datetime.now(UTC).isoformat(),
            "datasetSha256": hashlib.sha256(dataset.read_bytes()).hexdigest(),
            "mode": "REAL_JINA_CHROMA_EXTRACTIVE_NO_CHAT_MODEL",
            "reranker": "LOCAL_BM25_TOP30_COSINE_0.65_BM25_0.35",
            "embeddingModel": settings.embedding_model,
            "dimensions": settings.embedding_dimensions,
            "topK": settings.top_k,
            "minSimilarity": settings.min_similarity,
            "summary": summary,
            "results": records,
        }
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False))
    finally:
        retriever.embeddings.close()
        await workflow.model.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=AGENT_ROOT / "evals" / "rag-eval.jsonl")
    parser.add_argument(
        "--output", type=Path, default=AGENT_ROOT.parent / "docs" / "evals" / "R004-rag-real.json"
    )
    args = parser.parse_args()
    asyncio.run(evaluate(args.dataset, args.output))


if __name__ == "__main__":
    main()
