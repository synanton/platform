"""remap-gold: carry gold chunk IDs by (source_uri, chunk_ordinal) only when chunk text is identical."""
import json

from retrieval_eval import cli, remap


def fake_cql(db):
    """db = {tenant: {ref: (uri, {ordinal: sha})}} → a CqlRunner answering the two SELECT JSON queries."""
    def run(cql):
        tenant = cql.split("tenant_id='")[1].split("'")[0]
        if "FROM ingestion_cache.manifest" in cql:
            return [{"content_ref_id": ref, "source_uri": uri} for ref, (uri, _) in db.get(tenant, {}).items()]
        ref = cql.split("content_ref_id=")[1].rstrip(";").strip()
        return [{"chunk_ordinal": o, "chunk_sha256": h} for o, h in db[tenant][ref][1].items()]
    return run


DB = {
    "rb-fixed": {"old-a": ("file:///a.md", {0: "h0", 1: "h1"}), "old-b": ("file:///b.md", {0: "hb"}),
                 "old-c": ("file:///c.md", {0: "hc"})},
    "rb-fixed-g5": {"new-a": ("file:///a.md", {0: "h0", 1: "CHANGED"}), "new-b": ("file:///b.md", {0: "hb"})},
}


def test_maps_identical_chunks_and_reports_the_rest():
    rows = [{"query_id": "q1", "gold_chunk_ids": ["old-a#0", "old-b#0"]},
            {"query_id": "q2", "gold_chunk_ids": ["old-a#1", "old-c#0", "missing#0"]},
            {"query_id": "q3", "gold_chunk_ids": []}]
    out, report = remap.remap_queries(rows, fake_cql(DB), "rb-fixed", "rb-fixed-g5")

    assert out[0]["gold_chunk_ids"] == ["new-a#0", "new-b#0"]
    assert out[1]["gold_chunk_ids"] == []
    assert out[2]["gold_chunk_ids"] == []
    assert report.mapped == 2
    reasons = " | ".join(report.unmapped)
    assert "old-a#1 (chunk text differs" in reasons       # never guessed
    assert "file:///c.md not ingested in rb-fixed-g5" in reasons
    assert "missing#0 (not in rb-fixed)" in reasons
    assert "remapped from tenant=rb-fixed" in out[0]["notes"]


def test_cli_writes_the_new_file_and_strict_fails_on_gaps(tmp_path, monkeypatch):
    monkeypatch.setattr(remap, "docker_cqlsh", lambda container: fake_cql(DB))
    src = tmp_path / "q.jsonl"
    src.write_text(json.dumps({"query_id": "q1", "gold_chunk_ids": ["old-b#0"]}) + "\n"
                   + json.dumps({"query_id": "q2", "gold_chunk_ids": ["old-a#1"]}) + "\n")
    out = tmp_path / "out.jsonl"
    args = ["remap-gold", "--from-tenant", "rb-fixed", "--to-tenant", "rb-fixed-g5", "--queries", str(src), "--out", str(out)]

    assert cli.main(args) == 0
    lines = [json.loads(l) for l in out.read_text().splitlines()]
    assert lines[0]["gold_chunk_ids"] == ["new-b#0"] and lines[1]["gold_chunk_ids"] == []
    assert cli.main(args + ["--strict"]) == 1
