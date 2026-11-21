import unittest,json,hashlib
from request_provenance import build_arm_request
from ontology_context import ContextSnapshot
from extraction_contract import ModelProposal,ModelEntityProposal,ModelRelationProposal,validate_proposal
from extraction_pipeline import ALLOWED_TYPES,ALLOWED_PREDICATES

SETTINGS={'temperature':0,'top_p':1,'seed':0,'max_tokens':4096}
SNAP=ContextSnapshot('tenant-a','v1',hashlib.sha256(b'q').hexdigest(),1,('o1',),'Component: a physical part. Constraints: part_of',hashlib.sha256(b'Component: a physical part. Constraints: part_of').hexdigest())
PROMPT='Allowed entity types: {t}\nAllowed predicates: {p}\nTEXT:'

class RequestProvenanceTests(unittest.TestCase):
 def test_three_hashes_and_arm_isolation(self):
  a=build_arm_request(arm_id='A',chunk_text='Acme Widget',system_prompt=PROMPT.format(t='x',p='y'),model='m',settings=SETTINGS)
  b=build_arm_request(arm_id='B',chunk_text='Acme Widget',system_prompt=PROMPT.format(t='x',p='y'),model='m',settings=SETTINGS,ontology_snapshot=SNAP)
  self.assertEqual(a.source_input_hash,b.source_input_hash)
  self.assertIsNone(a.ontology_block_hash); self.assertIsNotNone(b.ontology_block_hash)
  self.assertNotEqual(a.serialized_context_hash,b.serialized_context_hash)
  self.assertNotIn('ONTOLOGY CONTEXT',json.loads(a.serialized_payload)['messages'][0]['content'])
  self.assertIn('ONTOLOGY CONTEXT',json.loads(b.serialized_payload)['messages'][0]['content'])
  self.assertNotIn('TEXT:',json.loads(b.serialized_payload)['messages'][1]['content'])

 def test_hashes_are_over_exact_serialized_payload(self):
  p=build_arm_request(arm_id='A',chunk_text='Acme Widget',system_prompt=PROMPT.format(t='x',p='y'),model='m',settings=SETTINGS)
  self.assertEqual(p.serialized_context_hash,hashlib.sha256(p.serialized_payload.encode()).hexdigest())

 def test_arm_a_does_not_call_retrieval(self):
  def boom(): raise AssertionError('retrieval called for A')
  build_arm_request(arm_id='A',chunk_text='Acme Widget',system_prompt=PROMPT.format(t='x',p='y'),model='m',settings=SETTINGS,ontology_provider=boom)

 def test_arm_b_requires_snapshot(self):
  with self.assertRaises(ValueError): build_arm_request(arm_id='B',chunk_text='x',system_prompt='TEXT:',model='m',settings=SETTINGS)

 def test_ontology_text_cannot_be_evidence(self):
  text='Acme supplies Widget.'
  prop=ModelProposal(entities=(ModelEntityProposal('Acme','Organization'),ModelEntityProposal('Widget','Product')),relations=(ModelRelationProposal('Acme','has_component','Widget','Component: a physical part.'),))
  v=validate_proposal(prop,text,allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES,ontology_context=SNAP.context)
  self.assertEqual(v.relationships,())
  self.assertEqual(v.rejected[0]['reason'],'ONTOLOGY_CONTEXT_NOT_EVIDENCE')

 def test_evidence_must_resolve_in_original_chunk(self):
  text='Acme supplies Widget.'; prop=ModelProposal(entities=(ModelEntityProposal('Acme','Organization'),ModelEntityProposal('Widget','Product')),relations=(ModelRelationProposal('Acme','has_component','Widget','Acme supplies Widget.'),))
  v=validate_proposal(prop,text,allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
  self.assertEqual(len(v.relationships),1); self.assertEqual(v.relationships[0].evidence_start,0)

if __name__=='__main__': unittest.main()
