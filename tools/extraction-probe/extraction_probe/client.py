"""ExtractSync client - calls the real synanton.extraction.v1 contract.

Guesses a reasonable media_type from the file extension if the caller doesn't
supply one; the actual routing/validation happens server-side per the contract,
this is just a sensible CLI default.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import grpc

from .config import ExtractionGatewayConfig
from .objectstore import UploadedObject
from .stubs import ensure_stubs

_MEDIA_TYPE_BY_SUFFIX = {
    ".pdf": "application/pdf",
    ".txt": "text/plain",
    ".md": "text/markdown",
    ".html": "text/html",
    ".htm": "text/html",
    ".csv": "text/csv",
    ".epub": "application/epub+zip",
}


def guess_media_type(file_path: Path) -> str:
    return _MEDIA_TYPE_BY_SUFFIX.get(file_path.suffix.lower(), "application/octet-stream")


@dataclass(frozen=True)
class ExtractionOutcome:
    status: str
    flattened_text: str
    feature_states: dict[str, str]
    error_code: str | None
    error_diagnostic: str | None
    element_count: int


def extract_sync(
    gateway: ExtractionGatewayConfig,
    proto_source_dir: Path,
    uploaded: UploadedObject,
    media_type: str,
    tenant_id: str = "extraction-probe",
    timeout_seconds: float = 60.0,
) -> ExtractionOutcome:
    stubs = ensure_stubs(proto_source_dir)
    svc = stubs.service_pb2
    payload_pb2 = stubs.payload_pb2
    svc_grpc = stubs.service_grpc

    item = svc.ExtractionRequestItem(
        content_ref_id=uploaded.key,
        source=svc.ObjectReference(
            bucket=uploaded.bucket,
            key=uploaded.key,
            sha256=uploaded.sha256,
            size_bytes=uploaded.size_bytes,
        ),
        media_type=media_type,
        options=svc.ExtractionOptions(layout=True, tables=True, embedded_images=True),
    )
    request = svc.SubmitExtractionRequest(
        tenant_id=tenant_id,
        idempotency_key=f"extraction-probe-{uploaded.key}",
        item=item,
        priority_class=svc.PriorityClass.PRIORITY_NORMAL,
    )

    channel = grpc.insecure_channel(gateway.target)
    stub = svc_grpc.ExtractionServiceStub(channel)
    result = stub.ExtractSync(request, timeout=timeout_seconds)

    status = svc.ExtractionStatus.Name(result.status)
    feature_states = {
        k: svc.FeatureState.Name(v) for k, v in result.feature_states.items()
    }

    element_count = 0
    if result.HasField("payload") and result.payload.HasField("inline_content"):
        try:
            document = payload_pb2.DocumentPayload.FromString(result.payload.inline_content)
            element_count = len(document.elements)
        except Exception:
            element_count = 0

    error_code = None
    error_diagnostic = None
    if result.HasField("error"):
        error_code = svc.ExtractionErrorCode.Name(result.error.code)
        error_diagnostic = result.error.diagnostic

    return ExtractionOutcome(
        status=status,
        flattened_text=result.flattened_text,
        feature_states=feature_states,
        error_code=error_code,
        error_diagnostic=error_diagnostic,
        element_count=element_count,
    )
