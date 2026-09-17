"""Config loading for the retrieval evaluation harness.

Reads tools/retrieval-eval/config.yml (see docs/research/retrieval-evaluation-benchmark-plan.md
§4a) and resolves the same ``${VAR:-default}`` interpolation style already used
throughout this repo's .env.example / compose.yaml, so overriding the dataset
root is one environment variable, not an edited file.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

_VAR_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?\}")


def _resolve_env(value: str) -> str:
    """Resolve ``${VAR:-default}`` / ``${VAR}`` references against os.environ."""

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
class DatasetSpec:
    """One dataset entry from config.yml, resolved to an absolute path."""

    name: str
    root_relative_path: str
    extra: dict = field(default_factory=dict)

    def resolve(self, datasets_root: Path) -> Path:
        return datasets_root / self.root_relative_path


@dataclass(frozen=True)
class BenchmarkConfig:
    datasets_root: Path
    datasets: dict[str, DatasetSpec]

    def dataset(self, name: str) -> DatasetSpec:
        try:
            return self.datasets[name]
        except KeyError as exc:
            known = ", ".join(sorted(self.datasets)) or "(none configured)"
            raise KeyError(f"Unknown dataset '{name}'. Known datasets: {known}") from exc


DEFAULT_CONFIG_PATH = Path(__file__).resolve().parent.parent / "config.yml"


def load_config(path: Path | str = DEFAULT_CONFIG_PATH) -> BenchmarkConfig:
    """Load and resolve config.yml into a BenchmarkConfig.

    Raises FileNotFoundError if the config file itself is missing, and
    ValueError if the resolved dataset root does not exist on disk - a
    misconfigured RETRIEVAL_BENCH_DATASETS_DIR should fail loudly here,
    not silently later when a dataset path can't be found.
    """
    path = Path(path)
    if not path.is_file():
        raise FileNotFoundError(f"Benchmark config not found: {path}")

    raw = yaml.safe_load(path.read_text())
    resolved = _resolve(raw)

    datasets_section = resolved.get("datasets", {})
    root = Path(datasets_section.pop("root"))

    datasets: dict[str, DatasetSpec] = {}
    for name, entry in datasets_section.items():
        if not isinstance(entry, dict):
            continue
        rel_path = entry.get("path", name)
        extra = {k: v for k, v in entry.items() if k != "path"}
        datasets[name] = DatasetSpec(name=name, root_relative_path=rel_path, extra=extra)

    return BenchmarkConfig(datasets_root=root, datasets=datasets)


def check_dataset_root(config: BenchmarkConfig) -> list[str]:
    """Return a list of human-readable problems with the configured dataset root.

    Empty list means everything configured actually exists on disk. This is a
    startup sanity check, not a hard requirement to call - a dataset arm that
    isn't in use yet (e.g. OHR-Bench before Phase B4) doesn't need to exist.
    """
    problems: list[str] = []
    if not config.datasets_root.is_dir():
        problems.append(f"datasets root does not exist: {config.datasets_root}")
        return problems

    for name, spec in config.datasets.items():
        resolved = spec.resolve(config.datasets_root)
        if not resolved.exists():
            problems.append(f"dataset '{name}' path does not exist: {resolved}")
    return problems
