"""Pre-run observability/reproducibility primitives; no model execution."""
from __future__ import annotations
import hashlib, json, time
from dataclasses import dataclass, asdict

REQUIRED_MANIFEST_FIELDS = (
 'run_id','model_revision','tokenizer_revision','runtime_version','prompt_hash',
 'chunk_plan_version','ontology_snapshot_hash','retrieval_algorithm_version',
 'serialized_context_hash','gold_version','metric_definition_version',
 'temperature','top_p','seed','max_tokens','arm_ids','dataset_ids')

@dataclass(frozen=True)
class IdentityChain:
    run_id: str
    manifest_hash: str
    request_id: str
    document_id: str
    chunk_id: str
    arm_id: str
    attempt_id: int
    output_id: str
    telemetry_record_id: str

@dataclass(frozen=True)
class TelemetryRecord:
    device_index: int
    device_name: str
    device_total_memory_bytes: int
    max_memory_allocated: int | None
    max_memory_reserved: int | None
    sampled_allocated_bytes: tuple[int, ...]
    sampled_reserved_bytes: tuple[int, ...]
    sampling_interval_ms: int
    capture_start_ts: float
    capture_end_ts: float

def _digest(obj) -> str:
    return hashlib.sha256(json.dumps(obj, sort_keys=True, separators=(',',':')).encode()).hexdigest()

def manifest_hash(manifest) -> str:
    return _digest(manifest)

def validate_manifest(manifest) -> list[str]:
    return [f'missing manifest field: {k}' for k in REQUIRED_MANIFEST_FIELDS if manifest.get(k) in (None,'',[])]

def mint_attempt(*, run_id, manifest, document_id, chunk_id, arm_id, attempt_id=1) -> IdentityChain:
    if attempt_id < 1: raise ValueError('attempt_id must be >= 1')
    mh = manifest_hash(manifest)
    base = {'run_id':run_id,'manifest_hash':mh,'document_id':document_id,'chunk_id':chunk_id,'arm_id':arm_id,'attempt_id':attempt_id}
    request_id = 'req-'+_digest({**base,'kind':'request'})[:24]
    output_id = 'out-'+_digest({**base,'kind':'output'})[:24]
    telemetry_id = 'tel-'+_digest({**base,'kind':'telemetry'})[:24]
    return IdentityChain(run_id,mh,request_id,document_id,chunk_id,arm_id,attempt_id,output_id,telemetry_id)

def validate_identity(chain: IdentityChain) -> list[str]:
    errors=[]
    if not all([chain.run_id,chain.manifest_hash,chain.request_id,chain.document_id,chain.chunk_id,chain.arm_id,chain.output_id,chain.telemetry_record_id]):
        errors.append('incomplete identity chain')
    if chain.attempt_id < 1: errors.append('invalid attempt_id')
    return errors

def validate_telemetry(record: TelemetryRecord) -> list[str]:
    errors=[]
    if record.max_memory_allocated is None: errors.append('missing max_memory_allocated')
    if record.max_memory_reserved is None: errors.append('missing max_memory_reserved')
    if not record.sampled_allocated_bytes or not record.sampled_reserved_bytes: errors.append('incomplete interval telemetry')
    if record.sampling_interval_ms <= 0: errors.append('invalid sampling interval')
    if record.capture_end_ts < record.capture_start_ts: errors.append('invalid capture boundaries')
    if record.device_total_memory_bytes <= 0: errors.append('missing device capacity')
    return errors

def capture_dry_telemetry(*, device_index, device_name, device_total_memory_bytes,
                          samples, sampling_interval_ms, started_at=None) -> TelemetryRecord:
    """Dry harness: derives peaks from supplied samples; no GPU call."""
    start = time.time() if started_at is None else started_at
    allocated=tuple(int(x[0]) for x in samples)
    reserved=tuple(int(x[1]) for x in samples)
    return TelemetryRecord(device_index,device_name,device_total_memory_bytes,
                           max(allocated) if allocated else None,
                           max(reserved) if reserved else None,
                           allocated,reserved,sampling_interval_ms,start,time.time())

def output_row(chain: IdentityChain, *, arm_id, payload_hash, eligible=True, reason=None):
    row = {**asdict(chain), 'arm_id':arm_id, 'payload_hash':payload_hash,
           'eligible':eligible, 'ineligibility_reason':reason}
    missing = [k for k in ('run_id','request_id','document_id','chunk_id','arm_id','attempt_id','output_id','telemetry_record_id','manifest_hash') if not row.get(k)]
    if missing: raise ValueError('output row missing '+','.join(missing))
    return row
