#!/usr/bin/env python3
"""Local-only OpenAI-compatible mock for the halcyon extraction fixture.

This is not a model. It lets the RAKG pipeline run end-to-end without a GPU by
returning the frozen LLM prediction for the halcyon-7-directive document.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

PREDICTIONS = os.environ.get("MOCK_PREDICTIONS", "/data/llm-predictions.json")
SOURCE = os.environ.get("MOCK_SOURCE", "/data/halcyon-7-directive.txt")
PREDICTION = json.load(open(PREDICTIONS))["halcyon-7-directive"]
TEXT = open(SOURCE).read()
BY_ID = {e["mentionId"]: e for e in PREDICTION["preEntities"]}


def raw_response():
    entities = [
        {
            "surfaceForm": e["surfaceForm"],
            "proposedType": e["proposedType"],
            "charStart": e["charStart"],
            "charEnd": e["charEnd"],
        }
        for e in PREDICTION["preEntities"]
    ]
    relations = []
    for rel in PREDICTION["relations"]:
        subject = BY_ID[rel["subjectMentionId"]]
        obj = BY_ID[rel["objectMentionId"]]
        start = rel.get("evidenceCharStart", 0)
        end = rel.get("evidenceCharEnd", 0)
        relations.append(
            {
                "subjectForm": subject["surfaceForm"],
                "predicate": rel["predicate"],
                "objectForm": obj["surfaceForm"],
                "evidenceQuote": TEXT[start:end],
            }
        )
    return {"preEntities": entities, "relations": relations}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"UP"}')
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        user = ""
        for message in body.get("messages", []):
            if message.get("role") == "user":
                user = message.get("content", "")
        content = raw_response() if "Halcyon-7" in user else {"preEntities": [], "relations": []}
        payload = {
            "choices": [
                {"message": {"role": "assistant", "content": json.dumps(content)}}
            ]
        }
        encoded = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, fmt, *args):
        return


HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
