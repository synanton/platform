"""Auditable per-document records and deterministic paired A/B reporting."""
from __future__ import annotations
import json
from extraction_contract import summarize_rejections

METRIC_VERSION='paired-v1'
REQUIRED_RECORD_FIELDS=('run_id','request_id','document_id','chunk_id','arm_id','attempt_id','output_id','telemetry_record_id','manifest_hash','source_input_hash','serialized_context_hash','model_revision','runtime_version','entity_f1','relation_f1','schema_valid','rejections','latency_s','gpu_max_allocated','gpu_max_reserved','eligible')

def build_record(*, chain, provenance, validated, metrics, model_revision, runtime_version,
                 schema_valid, latency_s, gpu_max_allocated=None, gpu_max_reserved=None,
                 failure=None):
    counts={'ENDPOINT_ONLY_IN_ONTOLOGY':0,'ENDPOINT_PROPOSED_BUT_REJECTED':0,'ENDPOINT_UNRESOLVED':0}
    counts.update({x['reason']:x['count'] for x in summarize_rejections(validated)})
    rec={'run_id':chain.run_id,'request_id':chain.request_id,'document_id':chain.document_id,
         'chunk_id':chain.chunk_id,'arm_id':chain.arm_id,'attempt_id':chain.attempt_id,
         'output_id':chain.output_id,'telemetry_record_id':chain.telemetry_record_id,
         'manifest_hash':chain.manifest_hash,'source_input_hash':provenance.source_input_hash,
         'ontology_block_hash':provenance.ontology_block_hash,
         'serialized_context_hash':provenance.serialized_context_hash,
         'model_revision':model_revision,'runtime_version':runtime_version,
         'entity_f1':metrics.get('entity_f1'),'relation_f1':metrics.get('relation_f1'),
         'schema_valid':schema_valid,'rejections':counts,'latency_s':latency_s,
         'gpu_max_allocated':gpu_max_allocated,'gpu_max_reserved':gpu_max_reserved,
         'failure':failure,'metric_definition_version':METRIC_VERSION,
         'eligible': failure is None and gpu_max_allocated is not None and gpu_max_reserved is not None}
    rec['ineligibility_reason']=None if rec['eligible'] else ('missing_telemetry' if gpu_max_allocated is None or gpu_max_reserved is None else 'failure')
    return rec

def validate_record(rec):
    errors=[]
    for k in REQUIRED_RECORD_FIELDS:
        if k not in rec: errors.append('missing '+k)
    for r in ('ENDPOINT_ONLY_IN_ONTOLOGY','ENDPOINT_PROPOSED_BUT_REJECTED','ENDPOINT_UNRESOLVED'):
        if r not in rec.get('rejections',{}): errors.append('missing rejection '+r)
    return errors

def paired_report(records):
    groups={}
    for r in records: groups.setdefault((r['document_id'],r['chunk_id']),{})[r['arm_id']]=r
    pairs=[]
    for (doc,chunk),arms in sorted(groups.items()):
        A=arms.get('A'); B=arms.get('B')
        status='COMPLETE' if A and B else 'INCOMPLETE'
        reason=None
        if A and B and A.get('source_input_hash')!=B.get('source_input_hash'):
            status='MISMATCH'; reason='SOURCE_HASH_MISMATCH'
        delta={}
        if status=='COMPLETE':
            for k in ('entity_f1','relation_f1'):
                if A.get(k) is not None and B.get(k) is not None: delta[k]=B[k]-A[k]
        pairs.append({'document_id':doc,'chunk_id':chunk,'status':status,'reason':reason,'delta':delta,'A':A,'B':B})
    complete=[p for p in pairs if p['status']=='COMPLETE']
    return {'metric_definition_version':METRIC_VERSION,'pair_count':len(pairs),'complete_pairs':len(complete),'incomplete_pairs':len(pairs)-len(complete),'pairs':pairs}
