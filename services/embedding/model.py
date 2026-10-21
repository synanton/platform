"""Model loading, probe measurement, and native-dimension validation.

The service never truncates and never changes the vector shape: it reports the
dimension the loaded model actually produces. A mismatch with EMBED_DIM fails
startup, so a swapped model cannot silently write vectors of another size.
"""

from __future__ import annotations

import hashlib
import struct
from dataclasses import dataclass, field
from typing import Callable, Sequence

from config import Settings

ModelFactory = Callable[[Settings], object]
CudaAvailable = Callable[[], bool]
DeviceName = Callable[[str], str | None]


class DimensionMismatchError(RuntimeError):
    """The loaded model's measured dimension differs from EMBED_DIM."""


class DeviceUnavailableError(RuntimeError):
    """EMBED_DEVICE requests a device the process cannot use."""


def _default_cuda_available() -> bool:
    try:
        import torch

        return bool(torch.cuda.is_available())
    except Exception:
        return False


def _default_device_name(device: str) -> str | None:
    if not device.startswith("cuda"):
        return None
    import torch

    index = int(device.split(":", 1)[1]) if ":" in device else 0
    return torch.cuda.get_device_name(index)


def _default_factory(settings: Settings) -> object:
    from sentence_transformers import SentenceTransformer

    return SentenceTransformer(
        settings.model_path,
        device=settings.device,
        local_files_only=settings.local_files_only,
    )


def _vector_hash(vector: Sequence[float]) -> str:
    packed = struct.pack(f"<{len(vector)}f", *[float(value) for value in vector])
    return f"sha256:{hashlib.sha256(packed).hexdigest()}"


@dataclass(frozen=True)
class LoadedModel:
    name: str
    path: str
    device: str
    device_name: str | None
    dimension: int
    sample_vector_head: list[float]
    sample_vector_hash: str
    backend: str
    _encoder: object = field(repr=False, compare=False)

    def embed(self, texts: list[str]) -> list[list[float]]:
        vectors = self._encoder.encode(
            texts,
            normalize_embeddings=True,
            convert_to_numpy=True,
        )
        return [[float(value) for value in vector] for vector in vectors]


def load_model(
    settings: Settings,
    model_factory: ModelFactory | None = None,
    cuda_available: CudaAvailable | None = None,
    device_name: DeviceName | None = None,
) -> LoadedModel:
    is_cuda = settings.device.startswith("cuda")
    if is_cuda and not (cuda_available or _default_cuda_available)():
        raise DeviceUnavailableError(
            f"EMBED_DEVICE={settings.device} but CUDA is not available"
        )

    encoder = (model_factory or _default_factory)(settings)

    # The probe is the measurement: dimension comes from the model, not config.
    probe = encoder.encode(
        [settings.probe_text],
        normalize_embeddings=True,
        convert_to_numpy=True,
    )
    vector = probe[0]
    measured_dim = len(vector)
    if measured_dim != settings.expected_dim:
        raise DimensionMismatchError(
            f"model produces {measured_dim}-dim vectors but EMBED_DIM={settings.expected_dim}"
        )

    backend = settings.device.split(":", 1)[0]
    return LoadedModel(
        name=settings.model_name,
        path=settings.model_path,
        device=settings.device,
        device_name=(device_name or _default_device_name)(settings.device) if is_cuda else None,
        dimension=measured_dim,
        sample_vector_head=[float(value) for value in list(vector)[:5]],
        sample_vector_hash=_vector_hash(vector),
        backend=backend,
        _encoder=encoder,
    )
