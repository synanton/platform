"""Arm-aware request construction and exact-submission provenance hashes."""
from __future__ import annotations
import hashlib, json
from dataclasses import dataclass

@dataclass(frozen=True)
class RequestProvenance:
    arm_id: str
    source_input_hash: str
    ontology_block_hash: str | None
    serialized_context_hash: str
    ontology_snapshot_hash: str | None
    ontology_object_ids: tuple[str, ...]
    serialized_payload: str

def _sha(text: str) -> str:
    return hashlib.sha256(text.encode('utf-8')).hexdigest()

def build_arm_request(*, arm_id, chunk_text, system_prompt, model, settings,
                      ontology_snapshot=None, ontology_provider=None):
    """Build serialized request and hashes at the final submission boundary.

    Arm A never calls ontology_provider. Arm B requires a pinned snapshot.
    """
    if arm_id not in ('A','B'):
        raise ValueError('arm_id must be A or B')
    if arm_id == 'A':
        if ontology_provider is not None:
            # Deliberately do not call it; this makes Arm A isolation explicit.
            pass
        system = system_prompt
        block = ''
        ontology_hash = None
        object_ids = ()
    else:
        if ontology_snapshot is None:
            raise ValueError('Arm B requires a pinned ontology snapshot')
        if not getattr(ontology_snapshot, 'context', None):
            block = ''
        else:
            block = 'ONTOLOGY CONTEXT (semantic guidance only; never source evidence):\n' + ontology_snapshot.context
        if 'TEXT:' in system_prompt:
            system = system_prompt.replace('TEXT:', block + '\n\nTEXT:', 1)
        else:
            system = system_prompt + '\n\n' + block
        ontology_hash = _sha(block)
        object_ids = tuple(ontology_snapshot.object_ids)
    payload = {
        'model': model,
        'messages': [
            {'role': 'system', 'content': system},
            {'role': 'user', 'content': chunk_text},
        ],
        'temperature': settings['temperature'],
        'top_p': settings['top_p'],
        'seed': settings['seed'],
        'max_tokens': settings['max_tokens'],
        'stream': False,
    }
    serialized = json.dumps(payload, sort_keys=True, separators=(',', ':'))
    snapshot_hash = getattr(ontology_snapshot, 'context_sha256', None) if arm_id == 'B' else None
    return RequestProvenance(arm_id, _sha(chunk_text), ontology_hash, _sha(serialized), snapshot_hash, object_ids, serialized)

class PinnedArmRequestBuilder:
    """One immutable ontology snapshot for every Arm B request in a run."""
    def __init__(self, *, system_prompt, model, settings, ontology_snapshot=None):
        self.system_prompt=system_prompt; self.model=model; self.settings=settings; self.ontology_snapshot=ontology_snapshot
    def build(self, *, arm_id, chunk_text):
        return build_arm_request(arm_id=arm_id, chunk_text=chunk_text,
                                 system_prompt=self.system_prompt, model=self.model,
                                 settings=self.settings, ontology_snapshot=self.ontology_snapshot)
