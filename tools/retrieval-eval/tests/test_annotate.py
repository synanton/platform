"""annotate-markers: objective, reproducible gold across differently chunked tenants."""
from retrieval_eval.annotate import annotate, normalise

CHUNKS = [
    {"ref": "r1", "uri": "file:///d/a.pdf", "ordinal": 0, "text": "The overall response rate\\nfor fiscal year 2007 was 78 percent."},
    {"ref": "r1", "uri": "file:///d/a.pdf", "ordinal": 1, "text": "Unrelated."},
    {"ref": "r2", "uri": "file:///d/b.md", "ordinal": 0, "text": "fiscal year 2007 was 78 percent (other doc)"},
]


def test_whitespace_and_escaped_newlines_are_normalised():
    assert normalise("a\\n  b\n\tc") == "a b c"


def test_markers_match_only_in_the_gold_source_and_rows_without_markers_are_kept():
    rows = [{"query_id": "p1", "gold_source": "a.pdf", "gold_markers": ["fiscal year 2007 was 78 percent"]},
            {"query_id": "q1", "gold_chunk_ids": ["keep#3"]},
            {"query_id": "p2", "gold_source": "a.pdf", "gold_markers": ["nowhere to be found"]}]
    out, report = annotate(rows, CHUNKS, "t")
    assert out[0]["gold_chunk_ids"] == ["r1#0"]          # not r2 (other source)
    assert out[1]["gold_chunk_ids"] == ["keep#3"]
    assert report.annotated == 2 and report.kept == 1 and report.unmatched == ["p2"]
