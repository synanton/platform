import unittest
from pre_run_observability import (mint_attempt, validate_identity, validate_manifest,
                                   validate_telemetry, capture_dry_telemetry, output_row, REQUIRED_MANIFEST_FIELDS)

def manifest(**over):
 m={k:f'x-{k}' for k in REQUIRED_MANIFEST_FIELDS}
 m.update({'run_id':'run-1','temperature':0,'top_p':1,'seed':0,'max_tokens':4096,'arm_ids':['A','B'],'dataset_ids':['g7-dev-001','g7-dev-002','g7-dev-003']})
 m.update(over); return m

class PreRunTests(unittest.TestCase):
 def test_ids_minted_before_inference_and_linked(self):
  c=mint_attempt(run_id='run-1',manifest=manifest(),document_id='g7-dev-001',chunk_id='0',arm_id='A')
  self.assertEqual(validate_identity(c),[])
  row=output_row(c,arm_id='A',payload_hash='hash')
  for k in ('run_id','request_id','document_id','chunk_id','arm_id','attempt_id','output_id','telemetry_record_id','manifest_hash'):
   self.assertIn(k,row)
 def test_retry_gets_new_attempt_and_output_id(self):
  a=mint_attempt(run_id='run-1',manifest=manifest(),document_id='g7-dev-001',chunk_id='0',arm_id='B',attempt_id=1)
  b=mint_attempt(run_id='run-1',manifest=manifest(),document_id='g7-dev-001',chunk_id='0',arm_id='B',attempt_id=2)
  self.assertNotEqual(a.output_id,b.output_id); self.assertNotEqual(a.request_id,b.request_id)
 def test_manifest_missing_field_blocks(self):
  m=manifest(); m.pop('ontology_snapshot_hash')
  self.assertTrue(validate_manifest(m))
 def test_missing_telemetry_is_not_zero(self):
  t=capture_dry_telemetry(device_index=0,device_name='GPU',device_total_memory_bytes=1000,samples=[],sampling_interval_ms=100)
  self.assertIsNone(t.max_memory_allocated); self.assertTrue(validate_telemetry(t))
 def test_dry_telemetry_peaks(self):
  t=capture_dry_telemetry(device_index=0,device_name='GPU',device_total_memory_bytes=1000,samples=[(100,200),(300,400),(250,500)],sampling_interval_ms=100)
  self.assertEqual((t.max_memory_allocated,t.max_memory_reserved),(300,500)); self.assertEqual(validate_telemetry(t),[])
 def test_output_missing_identity_rejected(self):
  c=mint_attempt(run_id='run-1',manifest=manifest(),document_id='g7-dev-001',chunk_id='0',arm_id='A')
  bad=type(c)('',c.manifest_hash,c.request_id,c.document_id,c.chunk_id,c.arm_id,c.attempt_id,c.output_id,c.telemetry_record_id)
  self.assertTrue(validate_identity(bad))

if __name__=='__main__': unittest.main()
