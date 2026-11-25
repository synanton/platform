#!/usr/bin/env python3
"""Build the deterministic A2 excerpt fixture and annotations.

Run from the repository root:
  python3 evaluation/it-books/a2-v1/build_annotations.py
"""
from __future__ import annotations

import hashlib
import json
import re
import unicodedata
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "evaluation/it-books/a2-v1"
SOURCES = OUT / "sources"
VERSION = "it-books-a2-v1"

SOURCE_DEFS = {
    "think-python-2e": {
        "title": "Think Python: How to Think Like a Computer Scientist",
        "edition": "2nd Edition, Version 2.4.1",
        "authors": ["Allen B. Downey"],
        "originalPath": "samples/it-books/books/think-python-2e.pdf",
        "excerptPath": "evaluation/it-books/a2-v1/sources/think-python-2e-excerpt.md",
        "license": "CC-BY-NC-3.0",
        "licenseUrl": "https://creativecommons.org/licenses/by-nc/3.0/",
        "restrictions": "Attribution required; non-commercial use only.",
        "sourceUrl": "https://greenteapress.com/wp/think-python-2e/",
    },
    "computer-networks-6.1": {
        "title": "Computer Networks: A Systems Approach",
        "edition": "6th Edition, Version 6.1",
        "authors": ["Larry Peterson", "Bruce Davie"],
        "originalPath": "samples/it-books/books/computer-networks-systems-approach-6.1.pdf",
        "excerptPath": "evaluation/it-books/a2-v1/sources/computer-networks-6.1-excerpt.md",
        "license": "CC-BY-4.0",
        "licenseUrl": "https://creativecommons.org/licenses/by/4.0/",
        "restrictions": "Attribution required.",
        "sourceUrl": "https://github.com/SystemsApproach/book/releases/tag/v6.1",
    },
}

# (id, source, ontology type, quote, context, source page)
MENTIONS = [
    ("m-tp-book", "think-python-2e", "Book", "Think Python: How to Think Like a Computer Scientist", None, 1),
    ("m-tp-author", "think-python-2e", "Author", "Allen Downey", "Author:", 1),
    ("m-tp-ch1", "think-python-2e", "Chapter", "Chapter 1: The way of the program", None, 1),
    ("m-tp-s11", "think-python-2e", "Section", "1.1 What is a program?", None, 1),
    ("m-tp-program", "think-python-2e", "Concept", "program", "A program is a sequence", 1),
    ("m-tp-ch7", "think-python-2e", "Chapter", "Chapter 7: Iteration", None, 67),
    ("m-tp-s76", "think-python-2e", "Section", "7.6 Algorithms", None, 67),
    ("m-tp-newton", "think-python-2e", "Algorithm", "Newton's method", "Newton's method is an example", 67),
    ("m-tp-algorithm", "think-python-2e", "Concept", "algorithm", "Newton's method is an example", 67),
    ("m-tp-topic", "think-python-2e", "Topic", "computer science", "central part of computer science", 68),
    ("m-tp-definition", "think-python-2e", "Definition", "algorithm: A general process for solving a category of problems.", None, 68),
    ("m-cn-book", "computer-networks-6.1", "Book", "Computer Networks: A Systems Approach", None, 1),
    ("m-cn-author-peterson", "computer-networks-6.1", "Author", "Larry Peterson", "Authors:", 1),
    ("m-cn-author-davie", "computer-networks-6.1", "Author", "Bruce Davie", "Authors:", 1),
    ("m-cn-ch1", "computer-networks-6.1", "Chapter", "Chapter 1: Foundation", None, 23),
    ("m-cn-s13", "computer-networks-6.1", "Section", "1.3 Architecture", None, 23),
    ("m-cn-protocols", "computer-networks-6.1", "Concept", "protocols", "called protocols", 23),
    ("m-cn-rrp", "computer-networks-6.1", "Concept", "RRP", "protocols RRP", 23),
    ("m-cn-hhp", "computer-networks-6.1", "Concept", "HHP", "Host-to-Host Protocol (HHP)", 23),
    ("m-cn-arch", "computer-networks-6.1", "Concept", "network architecture", "We call the set", 23),
    ("m-cn-routing", "computer-networks-6.1", "Algorithm", "routing algorithm", "requires some sort", 23),
    ("m-cn-topic", "computer-networks-6.1", "Topic", "Computer Networks", None, 1),
]

# (id, predicate, subject mention, object mention, judgment, reason)
RELATIONS = [
    ("r-authored-valid", "AUTHORED_BY", "m-tp-book", "m-tp-author", "ACCEPTED", "Book is authored by an Author."),
    ("r-authored-invalid", "AUTHORED_BY", "m-tp-author", "m-tp-book", "REJECTED", "Author-to-Book reverses the valid domain/range."),
    ("r-has-chapter-valid", "HAS_CHAPTER", "m-tp-book", "m-tp-ch1", "ACCEPTED", "Book has a Chapter."),
    ("r-has-chapter-invalid", "HAS_CHAPTER", "m-tp-ch1", "m-tp-book", "REJECTED", "Chapter-to-Book reverses the domain/range."),
    ("r-has-section-valid", "HAS_SECTION", "m-tp-ch1", "m-tp-s11", "ACCEPTED", "Chapter has a Section."),
    ("r-has-section-invalid", "HAS_SECTION", "m-tp-book", "m-tp-s11", "REJECTED", "Book is not a valid HAS_SECTION domain."),
    ("r-covers-topic-valid", "COVERS_TOPIC", "m-cn-book", "m-cn-topic", "ACCEPTED", "Book covers a Topic."),
    ("r-covers-topic-invalid", "COVERS_TOPIC", "m-cn-topic", "m-cn-book", "REJECTED", "Topic-to-Book reverses the domain/range."),
    ("r-defines-valid", "DEFINES", "m-tp-s11", "m-tp-program", "ACCEPTED", "Section defines a Concept."),
    ("r-defines-invalid", "DEFINES", "m-tp-book", "m-tp-program", "REJECTED", "Book is not a valid DEFINES domain."),
    ("r-uses-valid", "USES", "m-tp-ch7", "m-tp-newton", "ACCEPTED", "Chapter uses an Algorithm."),
    ("r-uses-invalid", "USES", "m-tp-author", "m-tp-newton", "REJECTED", "Author is not a valid USES subject."),
    ("r-depends-valid", "DEPENDS_ON", "m-cn-rrp", "m-cn-hhp", "ACCEPTED", "RRP depends on HHP in the source text."),
    ("r-depends-invalid", "DEPENDS_ON", "m-tp-author", "m-tp-algorithm", "REJECTED", "Author is not a valid DEPENDS_ON subject."),
    ("r-part-of-valid", "PART_OF", "m-tp-s11", "m-tp-ch1", "ACCEPTED", "Section is part of a Chapter."),
    ("r-part-of-invalid", "PART_OF", "m-tp-book", "m-tp-ch1", "REJECTED", "Book-to-Chapter is not the intended PART_OF direction."),
    ("r-unjudged-uses", "USES", "m-tp-ch1", "m-tp-algorithm", "UNJUDGED", "Plausible but intentionally left unjudged."),
]


def norm(text: str) -> str:
    return unicodedata.normalize("NFC", text).strip()


def utf16_len(text: str) -> int:
    """Number of UTF-16 code units in a Python (code-point) string."""
    return len(text.encode("utf-16-le")) // 2


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def java_uuid(name: str) -> str:
    digest = bytearray(hashlib.md5(name.encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def parse_markdown(source_id: str, text: str) -> dict:
    raw = unicodedata.normalize("NFC", text).replace("\r\n", "\n").replace("\r", "\n")
    lines = raw.split("\n")
    elements = []
    chunks = []
    section_headings = [None] * 6
    paragraph = []
    chunk_ordinal = 0
    chunk_key = "section-0"
    section_path = ""
    heading_re = re.compile(r"^(#{1,6})\s+(.*)$")

    def add(element_type, value):
        nonlocal elements
        if value is None or not norm(value):
            return
        elements.append({"type": element_type, "text": norm(value), "chunkKey": chunk_key,
                         "sectionPath": section_path})

    def flush():
        nonlocal paragraph
        if paragraph:
            add("PARAGRAPH", " ".join(paragraph))
            paragraph = []

    for raw_line in lines:
        line = raw_line.rstrip()
        stripped = line.strip()
        if not stripped:
            flush()
            continue
        match = heading_re.match(stripped)
        if match:
            flush()
            level = len(match.group(1))
            section_headings[level - 1] = norm(match.group(2))
            for i in range(level, 6):
                section_headings[i] = None
            section_path = " > ".join(x for x in section_headings if x)
            chunk_ordinal += 1
            chunk_key = f"section-{chunk_ordinal}"
            add("HEADING", section_headings[level - 1])
            continue
        paragraph.append(norm(stripped))
    flush()

    doc_text = "\n".join(e["text"] for e in elements)
    cursor = 0
    for i, e in enumerate(elements):
        e["ordinal"] = i
        e["elementId"] = java_uuid(f"a2-{source_id}|element|{i}")
        start = doc_text.find(e["text"], cursor)
        if start < 0:
            raise RuntimeError(f"cannot locate element text: {e['text'][:40]}")
        cp_end = start + len(e["text"])
        e["cpStart"] = start
        e["cpEnd"] = cp_end
        e["documentStartOffset"] = utf16_len(doc_text[:start])
        e["documentEndOffset"] = utf16_len(doc_text[:cp_end])
        cursor = cp_end + (1 if cp_end < len(doc_text) else 0)

    chunk_start = 0
    while chunk_start < len(elements):
        chunk_end = chunk_start + 1
        while chunk_end < len(elements) and elements[chunk_end]["chunkKey"] == elements[chunk_start]["chunkKey"]:
            chunk_end += 1
        ordinal = len(chunks)
        chunk_id = java_uuid(f"a2-{source_id}|chunk|{ordinal}")
        doc_start = elements[chunk_start]["documentStartOffset"]
        doc_end = elements[chunk_end - 1]["documentEndOffset"]
        cp_start = elements[chunk_start]["cpStart"]
        cp_end = elements[chunk_end - 1]["cpEnd"]
        chunk = {"chunkId": chunk_id, "ordinal": ordinal,
                 "sectionPath": elements[chunk_start]["sectionPath"],
                 "documentStartOffset": doc_start, "documentEndOffset": doc_end,
                 "text": doc_text[cp_start:cp_end],
                 "elementIds": [e["elementId"] for e in elements[chunk_start:chunk_end]]}
        chunks.append(chunk)
        for e in elements[chunk_start:chunk_end]:
            e["chunkId"] = chunk_id
            e["chunkStartOffset"] = e["documentStartOffset"] - doc_start
            e["chunkEndOffset"] = e["documentEndOffset"] - doc_start
        chunk_start = chunk_end

    return {"text": doc_text, "elements": elements, "chunks": chunks}


def main():
    structures = {}
    sources = []
    for source_id, definition in SOURCE_DEFS.items():
        original = ROOT / definition["originalPath"]
        excerpt = ROOT / definition["excerptPath"]
        structures[source_id] = parse_markdown(source_id, excerpt.read_text(encoding="utf-8"))
        sources.append({
            "sourceId": source_id,
            "title": definition["title"],
            "edition": definition["edition"],
            "authors": definition["authors"],
            "originalPath": definition["originalPath"],
            "originalSha256": sha256(original),
            "originalBytes": original.stat().st_size,
            "excerptPath": definition["excerptPath"],
            "excerptSha256": sha256(excerpt),
            "excerptBytes": excerpt.stat().st_size,
            "license": definition["license"],
            "licenseUrl": definition["licenseUrl"],
            "sourceUrl": definition["sourceUrl"],
            "rightsBasis": definition["restrictions"],
        })

    mentions = []
    by_id = {}
    for mention_id, source_id, ontology_type, quote, context, page in MENTIONS:
        structure = structures[source_id]
        element = None
        for candidate in structure["elements"]:
            if (context is None or context in candidate["text"]) and quote in candidate["text"]:
                element = candidate
                break
        if element is None:
            raise RuntimeError(f"mention not found: {mention_id} {quote!r} in {source_id}")
        offset = element["text"].find(quote)
        if offset < 0:
            raise RuntimeError(f"surface not found in element: {mention_id}")
        mention = {
            "id": mention_id,
            "sourceId": source_id,
            "type": ontology_type,
            "surface": quote,
            "space": "STRUCTURAL_DOCUMENT",
            "elementId": element["elementId"],
            "chunkId": element["chunkId"],
            "documentStartOffset": element["documentStartOffset"] + utf16_len(element["text"][:offset]),
            "documentEndOffset": element["documentStartOffset"] + utf16_len(element["text"][:offset + len(quote)]),
            "chunkStartOffset": element["chunkStartOffset"] + utf16_len(element["text"][:offset]),
            "chunkEndOffset": element["chunkStartOffset"] + utf16_len(element["text"][:offset + len(quote)]),
            "pageNumber": None,
            "sourcePage": page,
            "geometryStatus": "NOT_APPLICABLE",
            "boundingBox": None,
        }
        mentions.append(mention)
        by_id[mention_id] = mention

    relations = []
    for relation_id, predicate, subject, obj, judgment, reason in RELATIONS:
        relations.append({
            "id": relation_id,
            "predicate": predicate,
            "subjectMentionId": subject,
            "objectMentionId": obj,
            "sourceId": by_id[subject]["sourceId"] if by_id[subject]["sourceId"] == by_id[obj]["sourceId"] else "cross-source",
            "judgment": judgment,
            "judgedNegative": judgment == "REJECTED",
            "reason": reason,
            "evidenceSpace": "STRUCTURAL_DOCUMENT",
            "evidenceMentionIds": [subject, obj],
        })

    manifest = {
        "fixtureVersion": VERSION,
        "artifactType": "A2 annotated IT Books evaluation fixture",
        "status": "draft-for-review",
        "coordinateContract": {
            "spaces": ["STRUCTURAL_DOCUMENT", "STRUCTURAL_CHUNK", "EXTRACTION_INPUT"],
            "normalization": "NFC_UTF16_V1",
            "offsetUnit": "UTF-16 code units",
            "geometryStates": ["AVAILABLE", "UNAVAILABLE", "NOT_APPLICABLE"],
        },
        "compiler": {
            "ontologyId": "it-books",
            "releaseId": "47fe38f8-9f72-481b-826a-6c5b12a6027a",
            "releaseDigest": "3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c",
            "specDigest": "8834d45a276792babbdf59ff6da43bf11ba5a8bdd43ca9755cfb159559694455",
            "templateDigest": "333a900292eebabda75b4ed67ec909ecc5db450702e05ae23d108ed2cf2fb37b",
            "compilationId": "cea21d1a4cebf9e45e2e6ebba9ae4c3899dfc336b2b098a959db6dd5a06f7e0b",
        },
        "evidenceContract": "p3a-evidence-binding-v1",
        "sources": sources,
        "annotationFile": "annotations.json",
        "judgmentSemantics": {
            "ACCEPTED": "Expected valid relation under the A1 intended domain/range.",
            "REJECTED": "Judged-negative relation; explicitly not accepted.",
            "UNJUDGED": "Candidate intentionally excluded from accepted/rejected judgment.",
        },
    }
    annotations = {"fixtureVersion": VERSION, "mentions": mentions, "relations": relations}
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (OUT / "annotations.json").write_text(json.dumps(annotations, indent=2) + "\n", encoding="utf-8")
    checksums = []
    for path in [OUT / "manifest.json", OUT / "annotations.json",
                 OUT / "sources/think-python-2e-excerpt.md",
                 OUT / "sources/computer-networks-6.1-excerpt.md"]:
        checksums.append(f"{sha256(path)}  {path.relative_to(OUT)}")
    (OUT / "SHA256SUMS").write_text("\n".join(checksums) + "\n", encoding="utf-8")
    print(f"wrote {VERSION}: {len(mentions)} mentions, {len(relations)} relations")


if __name__ == "__main__":
    main()
