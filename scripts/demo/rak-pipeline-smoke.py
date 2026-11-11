#!/usr/bin/env python3
"""Local end-to-end RAKG smoke: Compose stack -> extraction -> graph -> resolution -> promotion -> Entity Graph."""
import argparse
import base64
import hashlib
import hmac
import json
import mimetypes
import os
import pathlib
import subprocess
import sys
import time
import uuid
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
TENANT = os.environ.get("LOCAL_REAL_DEMO_TENANT", "local-real-demo")
SECRET = os.environ.get("SYNANTON_JWT_SECRET", "local-real-demo-secret-at-least-32-bytes")
SYNFLUX = os.environ.get("SYNFLUX_URL", "http://localhost:18090")
RELIX = os.environ.get("RELIX_URL", "http://localhost:18084")
SYNQUEST = os.environ.get("SYNQUEST_URL", "http://localhost:18083")
NEO4J_HTTP = os.environ.get("NEO4J_HTTP_URL", "http://localhost:17474")
NEO4J_USER = os.environ.get("NEO4J_USER", "neo4j")
NEO4J_PASSWORD = os.environ.get("NEO4J_PASSWORD", "local-real-demo-neo4j")
SOURCE = os.environ.get("RAK_SMOKE_SOURCE_ID", "rak-halcyon-7")
DOC = ROOT / "evaluation/corpus/halcyon-7-directive.txt"


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def token() -> str:
    header = b64url(b'{"alg":"HS256","typ":"JWT"}')
    payload = b64url(json.dumps({
        "sub": "rak-pipeline-smoke",
        "tenant_id": TENANT,
        "exp": int(time.time()) + 3600,
    }, separators=(",", ":")).encode())
    sig = b64url(hmac.new(SECRET.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest())
    return f"{header}.{payload}.{sig}"


def request(method, url, body=None, headers=None):
    hdrs = {"Authorization": f"Bearer {token()}"}
    if headers:
        hdrs.update(headers)
    data = body
    if isinstance(body, dict):
        data = json.dumps(body).encode()
        hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            return response.status, json.loads(response.read().decode() or "{}")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def multipart(fields, file_path):
    boundary = "----rak-smoke-" + uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode())
    mime = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_path.name}\"\r\n"
        f"Content-Type: {mime}\r\n\r\n".encode()
    )
    parts.append(file_path.read_bytes())
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def compose(*args, **kwargs):
    command = [
        "docker", "compose", "--project-name", "synanton-local-real-demo",
        "-f", str(ROOT / "deployments/docker/compose.yaml"),
        "-f", str(ROOT / "deployments/docker/compose.local-real-demo.yaml"),
        "--profile", "local-real-demo", *args,
    ]
    return subprocess.run(command, check=True, **kwargs)


def wait_status():
    deadline = time.time() + 300
    while time.time() < deadline:
        status_code, body = request("GET", f"{SYNFLUX}/v1/sources/{SOURCE}/status")
        if status_code == 200:
            print("status:", json.dumps(body, sort_keys=True))
            if (body.get("extraction_status") == "KNOWLEDGE_READY"
                    and body.get("graph_status") == "GRAPH_COMMITTED"
                    and body.get("resolution_status") == "COMPLETED"):
                return body
        time.sleep(3)
    raise SystemExit("status did not reach KNOWLEDGE_READY / GRAPH_COMMITTED / COMPLETED in 300s")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--up", action="store_true", help="start the local-real-demo Compose stack first")
    args = parser.parse_args()
    if not DOC.is_file():
        raise SystemExit(f"missing fixture: {DOC}")
    if args.up:
        env = os.environ.copy()
        env.update({
            "EXTRACTION_PROVIDER": "llm",
            "LLM_EXTRACTION_URL": "http://mock-extraction:8000/v1",
            "EXTRACTION_CONSUMER_ENABLED": "true",
            "EXTRACTION_GRAPH_ENABLED": "true",
            "RESOLUTION_CONSUMER_ENABLED": "true",
            "PROMOTION_CONSUMER_ENABLED": "true",
            "PROMOTION_PRODUCER_ENABLED": "true",
            "PROMOTION_PRODUCER_TENANT": TENANT,
            "PROMOTION_PRODUCER_ONTOLOGY_VERSION": "active",
            "EXTRACTION_CONSUMER_TENANT": TENANT,
            "RESOLUTION_CONSUMER_TENANT": TENANT,
            "PROMOTION_CONSUMER_TENANT": TENANT,
            "RELIX_BASE_URL": "http://relix:8084",
            "SYNTOLOGY_BASE_URL": "http://syntology:8080",
            "SYNANTON_JWT_SECRET": SECRET,
            "LOCAL_REAL_DEMO_TENANT": TENANT,
            "NEO4J_PASSWORD": NEO4J_PASSWORD,
        })
        subprocess.run([str(ROOT / "scripts/run-local-real-demo.sh"), "up"], cwd=ROOT, env=env, check=True)

    # Seed canonical entities and project them into the Lucene entity index.
    compose("exec", "-T", "neo4j", "cypher-shell", "-u", "neo4j", "-p", NEO4J_PASSWORD,
            input=(ROOT / "demo-data/rak-halcyon-entities.cypher").read_text().encode())
    projection_env = os.environ.copy()
    projection_env.update({
        "NEO4J_HTTP_URL": NEO4J_HTTP,
        "NEO4J_DATABASE": "neo4j",
        "NEO4J_USER": NEO4J_USER,
        "NEO4J_PASSWORD": NEO4J_PASSWORD,
        "SYNQUEST_URL": SYNQUEST,
        "SYNANTON_JWT_SECRET": SECRET,
        "TENANT": TENANT,
    })
    subprocess.run([sys.executable, str(ROOT / "tools/entity-projection/project_entities.py")],
                   cwd=ROOT, env=projection_env, check=True)

    source_body = {
        "source_id": SOURCE,
        "connector_id": "manual-upload",
        "connector_type": "upload",
        "source_endpoint": "upload://manual-upload",
        "capability_flags": [],
    }
    code, body = request("POST", f"{SYNFLUX}/v1/sources", source_body)
    print("create source:", code, body)
    if code not in (200, 201):
        raise SystemExit(f"could not create source: {code} {body}")

    payload, content_type = multipart({
        "connector_id": "manual-upload",
        "source_id": SOURCE,
        "resource_id": "halcyon-7-directive.txt",
    }, DOC)
    code, body = request("POST", f"{SYNFLUX}/v1/ingestion-runs", payload, {
        "Content-Type": content_type,
        "Idempotency-Key": "rak-pipeline-smoke-" + SOURCE,
    })
    print("upload:", code, body)
    if code not in (200, 202):
        raise SystemExit(f"upload failed: {code} {body}")

    wait_status()
    code, body = request("GET", f"{RELIX}/v1/entities/entity-auxiliary-coolant-loop/neighbors")
    print("entity graph:", code, json.dumps(body, sort_keys=True))
    if code != 200:
        raise SystemExit(f"entity graph query failed: {code} {body}")
    if not body.get("neighbors"):
        raise SystemExit("entity graph has no neighbors: promotion/validation may not have run")
    if not body.get("evidence"):
        raise SystemExit("entity graph has no relation evidence")
    print("PASS: local RAKG pipeline reached a queryable entity graph with evidence")


if __name__ == "__main__":
    main()
