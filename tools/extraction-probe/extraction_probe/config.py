"""Config loading for extraction-probe.

Reads tools/extraction-probe/config.yml and resolves the same ``${VAR:-default}``
interpolation style already used by tools/retrieval-eval/config.py and this repo's
own .env.example / compose.yaml, so overriding a connection setting is one
environment variable, not an edited file.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

import yaml

_VAR_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?\}")


def _resolve_env(value: str) -> str:
    def _sub(match: "re.Match[str]") -> str:
        var_name, _, default = match.groups()
        return os.environ.get(var_name, default if default is not None else "")

    return _VAR_PATTERN.sub(_sub, value)


def _resolve(node):
    if isinstance(node, str):
        return _resolve_env(node)
    if isinstance(node, dict):
        return {k: _resolve(v) for k, v in node.items()}
    if isinstance(node, list):
        return [_resolve(v) for v in node]
    return node


@dataclass(frozen=True)
class ExtractionGatewayConfig:
    host: str
    grpc_port: int

    @property
    def target(self) -> str:
        return f"{self.host}:{self.grpc_port}"


@dataclass(frozen=True)
class ObjectStoreConfig:
    endpoint: str
    access_key: str
    secret_key: str
    bucket: str
    region: str


@dataclass(frozen=True)
class ProbeConfig:
    extraction_gateway: ExtractionGatewayConfig
    objectstore: ObjectStoreConfig
    proto_source_dir: Path
    config_dir: Path


DEFAULT_CONFIG_PATH = Path(__file__).resolve().parent.parent / "config.yml"


def load_config(path: Path | str = DEFAULT_CONFIG_PATH) -> ProbeConfig:
    """Load and resolve config.yml into a ProbeConfig."""
    path = Path(path)
    if not path.is_file():
        raise FileNotFoundError(f"extraction-probe config not found: {path}")

    raw = yaml.safe_load(path.read_text())
    resolved = _resolve(raw)

    eg = resolved["extraction_gateway"]
    os_cfg = resolved["objectstore"]
    proto_dir = Path(resolved["proto"]["source_dir"])
    if not proto_dir.is_absolute():
        proto_dir = (path.parent / proto_dir).resolve()

    return ProbeConfig(
        extraction_gateway=ExtractionGatewayConfig(
            host=eg["host"], grpc_port=int(eg["grpc_port"])
        ),
        objectstore=ObjectStoreConfig(
            endpoint=os_cfg["endpoint"],
            access_key=os_cfg["access_key"],
            secret_key=os_cfg["secret_key"],
            bucket=os_cfg["bucket"],
            region=os_cfg["region"],
        ),
        proto_source_dir=proto_dir,
        config_dir=path.parent,
    )
