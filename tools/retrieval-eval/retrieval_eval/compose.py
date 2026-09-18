"""Helpers for talking to the existing docker compose stack.

Deliberately does not start a different stack than
scripts/run-extract-index-poc.sh already does (per
docs/research/retrieval-evaluation-benchmark-plan.md §2/§6 Phase B0) - this
module only resolves ports for a stack someone else already started, the
same way that script's own port-resolution lines do:

    docker compose -f "$COMPOSE_FILE" port synflux 8090 | cut -d: -f2
"""

from __future__ import annotations

import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_COMPOSE_FILE = REPO_ROOT / "deployment" / "docker" / "compose.yaml"

DEFAULT_PORTS = {
    "synflux": 8090,
    "synvault": 8091,
    "synquest": 8083,
}


def resolve_port(service: str, container_port: int, compose_file: Path = DEFAULT_COMPOSE_FILE) -> int:
    """Resolve a service's host-mapped port, falling back to the container port.

    Mirrors run-extract-index-poc.sh / run-ingestion-demo.sh's own
    `docker compose port <service> <port> | cut -d: -f2 || echo "<port>"`
    fallback exactly, so behavior matches whether or not compose is
    reachable from wherever the harness runs.
    """
    try:
        output = subprocess.run(
            ["docker", "compose", "-f", str(compose_file), "port", service, str(container_port)],
            capture_output=True,
            text=True,
            timeout=10,
            check=True,
        ).stdout.strip()
        if ":" in output:
            return int(output.rsplit(":", 1)[1])
    except (subprocess.SubprocessError, ValueError, OSError):
        pass
    return container_port


def synflux_base_url(compose_file: Path = DEFAULT_COMPOSE_FILE) -> str:
    port = resolve_port("synflux", DEFAULT_PORTS["synflux"], compose_file)
    return f"http://localhost:{port}"


def synquest_base_url(compose_file: Path = DEFAULT_COMPOSE_FILE) -> str:
    port = resolve_port("synquest", DEFAULT_PORTS["synquest"], compose_file)
    return f"http://localhost:{port}"


def synvault_base_url(compose_file: Path = DEFAULT_COMPOSE_FILE) -> str:
    port = resolve_port("synvault", DEFAULT_PORTS["synvault"], compose_file)
    return f"http://localhost:{port}"
