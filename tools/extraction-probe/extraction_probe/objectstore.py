"""Uploads a local file to the object store extraction-gateway reads from.

Uses boto3 (S3-compatible) against MinIO, mirroring how content_extractor's own
MinioSourceObjectReader talks to the same endpoint - the gateway never sees raw
bytes over gRPC, only an ObjectReference (bucket/key/sha256/size), matching the
synanton.extraction.v1 contract's "no source bytes over the wire" rule.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

import boto3
from botocore.client import Config as BotoConfig
from botocore.exceptions import ClientError

from .config import ObjectStoreConfig


@dataclass(frozen=True)
class UploadedObject:
    bucket: str
    key: str
    sha256: str
    size_bytes: int


def _client(cfg: ObjectStoreConfig):
    return boto3.client(
        "s3",
        endpoint_url=cfg.endpoint,
        aws_access_key_id=cfg.access_key,
        aws_secret_access_key=cfg.secret_key,
        region_name=cfg.region,
        config=BotoConfig(s3={"addressing_style": "path"}),
    )


def ensure_bucket(cfg: ObjectStoreConfig) -> None:
    client = _client(cfg)
    try:
        client.head_bucket(Bucket=cfg.bucket)
    except ClientError:
        client.create_bucket(Bucket=cfg.bucket)


def upload_file(cfg: ObjectStoreConfig, file_path: Path, key_prefix: str = "extraction-probe") -> UploadedObject:
    """Upload file_path to cfg.bucket, returning the reference extraction-gateway needs."""
    data = file_path.read_bytes()
    sha256 = hashlib.sha256(data).hexdigest()
    key = f"{key_prefix}/{file_path.name}"

    ensure_bucket(cfg)
    client = _client(cfg)
    client.put_object(Bucket=cfg.bucket, Key=key, Body=data)

    return UploadedObject(bucket=cfg.bucket, key=key, sha256=sha256, size_bytes=len(data))
