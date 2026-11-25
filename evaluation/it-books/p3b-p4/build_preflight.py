#!/usr/bin/env python3
"""Build the P3b/P4 preflight manifest without running evaluation."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
A2 = ROOT / "evaluation/it-books/a2-v1"
OUT = ROOT / "evaluation/it-books/p3b-p4"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


a2_manifest = read(A2 / "manifest.json")
a2_annotations = read(A2 / "annotations.json")
p0_report = read(ROOT / "docs/implementation/it-books/p0/it-books.report.json")
p0_provenance = p0_report["provenance"]

p0_artifacts = {}
for name in ["it-books.spec.json", "it-books.template.json", "it-books.report.json"]:
    path = ROOT / "docs/implementation/it-books/p0" / name
    p0_artifacts[name] = {"path": str(path.relative_to(ROOT)), "sha256": sha256(path)}

a2_sources = []
for source in a2_manifest["sources"]:
    original = ROOT / source["originalPath"]
    excerpt = ROOT / source["excerptPath"]
    a2_sources.append({
        "sourceId": source["sourceId"],
        "title": source["title"],
        "edition": source["edition"],
        "authors": source["authors"],
        "originalPath": source["originalPath"],
        "originalSha256": sha256(original),
        "originalBytes": original.stat().st_size,
        "excerptPath": source["excerptPath"],
        "excerptSha256": sha256(excerpt),
        "excerptBytes": excerpt.stat().st_size,
        "license": source["license"],
        "licenseUrl": source["licenseUrl"],
        "sourceUrl": source["sourceUrl"],
        "rightsBasis": source["rightsBasis"],
    })

mentions = a2_annotations["mentions"]
relations = a2_annotations["relations"]
predicates = sorted({r["predicate"] for r in relations})
accepted = sum(1 for r in relations if r["judgment"] == "ACCEPTED")
rejected = sum(1 for r in relations if r["judgment"] == "REJECTED")
unjudged = sum(1 for r in relations if r["judgment"] == "UNJUDGED")
if accepted != 8 or rejected != 8 or unjudged != 1 or len(predicates) != 8:
    raise SystemExit("A2 annotation coverage changed; preflight refuses to pin it")

checks = {
    "a2FixturePresent": (A2 / "manifest.json").exists(),
    "a2SourceHashesMatch": all(s["originalSha256"] == sha256(ROOT / s["originalPath"])
                               and s["excerptSha256"] == sha256(ROOT / s["excerptPath"])
                               for s in a2_sources),
    "a2MentionCount": len(mentions),
    "a2RelationCount": len(relations),
    "a2PredicateCount": len(predicates),
    "a2AcceptCount": accepted,
    "a2RejectCount": rejected,
    "a2UnjudgedCount": unjudged,
    "p0ArtifactsPresent": all((ROOT / v["path"]).exists() for v in p0_artifacts.values()),
    "p0ReleaseMatchesA2": p0_provenance["releaseId"] == a2_manifest["compiler"]["releaseId"]
                          and p0_provenance["releaseDigest"] == a2_manifest["compiler"]["releaseDigest"],
    "p1ContractPresent": (ROOT / "docs/implementation/it-books/p1-structural-contract.md").exists(),
    "p3aContractPresent": (ROOT / "docs/architecture/p3a-evidence-binding-implementation.md").exists(),
    "extractionAuthorized": False,
    "evaluationStarted": False,
}

manifest = {
    "evaluationId": "it-books-p3b-p4-preflight-v1",
    "status": "PREFLIGHT_ONLY",
    "a2": {
        "fixtureVersion": a2_manifest["fixtureVersion"],
        "mergeCommit": "c790a2390404593f2807eaa53fa1ace04348bc17",
        "manifestPath": "evaluation/it-books/a2-v1/manifest.json",
        "manifestSha256": sha256(A2 / "manifest.json"),
        "annotationsPath": "evaluation/it-books/a2-v1/annotations.json",
        "annotationsSha256": sha256(A2 / "annotations.json"),
        "mentions": len(mentions),
        "relations": len(relations),
        "accepted": accepted,
        "rejected": rejected,
        "unjudged": unjudged,
        "predicates": predicates,
        "sources": a2_sources,
        "coordinateContract": a2_manifest["coordinateContract"],
        "limitations": [
            "Markdown excerpts have geometry NOT_APPLICABLE; PDF AVAILABLE-bbox claims must use P1's PDF fixture.",
            "sourcePage references are manually reviewed, not validator-enforced.",
            "Manifest does not yet include mediaType, pageCount, or a structural-artifact digest."
        ],
    },
    "p0": {
        "mergeCommit": "5a781ef8e33ad294421fe3c2155b0afc01a9730d",
        "releaseId": p0_provenance["releaseId"],
        "releaseDigest": p0_provenance["releaseDigest"],
        "compilerVersion": p0_provenance["compilerVersion"],
        "exportProfile": p0_provenance["exportProfile"],
        "schemaVersion": p0_provenance["schemaVersion"],
        "specDigest": p0_report["digests"]["specDigest"],
        "templateDigest": p0_report["digests"]["templateDigest"],
        "compilationId": p0_report["digests"]["compilationId"],
        "artifacts": p0_artifacts,
    },
    "p1": {
        "status": "accepted",
        "structuralSliceMerge": "1b1a90e7",
        "extractionIntegrationMerge": "15c88641",
        "formalConformanceMerge": "5fa1df9d",
        "pinnedEvidenceMerge": "99df0da7",
        "contractPath": "docs/implementation/it-books/p1-structural-contract.md",
        "normalization": ["NFC_COLLAPSE_WHITESPACE_UTF16_V1", "NFC_UTF16_V1"],
        "chunking": ["pdf-page-v1", "markdown-heading-v1"],
        "a2Adapter": "Synanton Markdown Adapter",
    },
    "p3a": {
        "contractVersion": "p3a-evidence-binding-v1",
        "mergeCommit": "66afc878d467f95cc997eeb0e9201aa263f9cc1c",
        "contractPath": "docs/architecture/p3a-evidence-binding-implementation.md",
        "spaces": ["STRUCTURAL_DOCUMENT", "STRUCTURAL_CHUNK", "EXTRACTION_INPUT"],
    },
    "extraction": {
        "status": "NOT_CONFIGURED",
        "authorized": False,
        "backend": None,
        "provider": None,
        "modelRevision": None,
        "configurationDigest": None,
        "gpuGatewayRequiredIfUsed": "synanton.gpu.v1",
        "directVllmOrTeiCallsAllowed": False,
        "automaticRegexFallbackAllowed": False,
    },
    "evaluation": {
        "status": "NOT_RUN",
        "results": [],
        "started": False,
        "reason": "Preflight manifest requires review and an explicitly authorized extractor path before evaluation."
    },
    "isolation": {
        "productionIndexes": False,
        "customerData": False,
        "supplyChainGenerations": False,
        "graphPromotion": False,
        "doclingDependency": False,
    },
    "checks": checks,
}

(OUT / "preflight.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(json.dumps(checks, indent=2))
