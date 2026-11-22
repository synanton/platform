import unittest,json
from evaluation_reporting import build_record,validate_record,paired_report
from request_provenance import RequestProvenance
from extraction_contract import ValidatedExtraction
from types import SimpleNamespace

def rec(arm,doc='d1',chunk='0',src='s',ent=0.4,rel=0.2):
 c=SimpleNamespace(run_id='run',manifest_hash='mh',request_id='r-'+arm,document_id=doc,chunk_id=chunk,arm_id=arm,attempt_id=1,output_id='o-'+arm,telemetry_record_id='t-'+arm)
 p=RequestProvenance(arm,src,None,'ser',None,(),'{}')
 v=ValidatedExtraction((),(),())
 return build_record(chain=c,provenance=p,validated=v,metrics={'entity_f1':ent,'relation_f1':rel},model_revision='rev',runtime_version='rt',schema_valid=True,latency_s=1.0,gpu_max_allocated=10,gpu_max_reserved=20)
class ReportingTests(unittest.TestCase):
 def test_record_requires_all_reasons(self):
  r=rec('A'); self.assertEqual(validate_record(r),[]); self.assertTrue(r['eligible'])
 def test_missing_telemetry_ineligible(self):
  r=rec('A'); r['gpu_max_allocated']=None; r['eligible']=False; r['ineligibility_reason']='missing_telemetry'; self.assertFalse(r['eligible'])
 def test_complete_paired_delta(self):
  out=paired_report([rec('A'),rec('B',ent=0.6,rel=0.3)]); self.assertEqual(out['complete_pairs'],1); self.assertAlmostEqual(out['pairs'][0]['delta']['entity_f1'],0.2)
 def test_incomplete_pair_visible(self):
  out=paired_report([rec('A')]); self.assertEqual(out['incomplete_pairs'],1); self.assertEqual(out['pairs'][0]['status'],'INCOMPLETE')
 def test_source_hash_mismatch(self):
  out=paired_report([rec('A',src='s1'),rec('B',src='s2')]); self.assertEqual(out['pairs'][0]['status'],'MISMATCH')
 def test_deterministic_report(self):
  rs=[rec('A'),rec('B')]; self.assertEqual(json.dumps(paired_report(rs),sort_keys=True),json.dumps(paired_report(rs),sort_keys=True))
if __name__=='__main__': unittest.main()
