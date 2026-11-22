import unittest
from extraction_contract import (ModelProposal,ModelEntityProposal,ModelRelationProposal,
                                 validate_proposal,evaluation_record)
from extraction_pipeline import ALLOWED_TYPES,ALLOWED_PREDICATES

ONTOLOGY='Component: a physical part. Supplier: an organization.'
TEXT='Acme supplies Widget.'

class EndpointEnforcementTests(unittest.TestCase):
 def test_ontology_only_endpoint_rejected(self):
  p=ModelProposal(entities=(ModelEntityProposal('Acme','Organization'),),relations=(ModelRelationProposal('Acme','has_component','Component','Component: a physical part.'),))
  v=validate_proposal(p,TEXT,allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES,ontology_context=ONTOLOGY)
  self.assertEqual(v.relationships,())
  self.assertEqual(v.rejected[0]['reason'],'ENDPOINT_ONLY_IN_ONTOLOGY')

 def test_proposed_but_rejected_mention_rejected(self):
  p=ModelProposal(entities=(ModelEntityProposal('Acme','Organization'),ModelEntityProposal('Widget','Alien')),relations=(ModelRelationProposal('Acme','has_component','Widget','Acme supplies Widget.'),))
  v=validate_proposal(p,TEXT,allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
  self.assertEqual(v.relationships,())
  self.assertIn('ENDPOINT_PROPOSED_BUT_REJECTED',{x['reason'] for x in v.rejected})

 def test_unresolved_endpoint_rejected(self):
  p=ModelProposal(entities=(ModelEntityProposal('Acme','Organization'),),relations=(ModelRelationProposal('Acme','has_component','Missing','Acme supplies Widget.'),))
  v=validate_proposal(p,TEXT,allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
  self.assertEqual(v.relationships,())
  self.assertIn('ENDPOINT_UNRESOLVED',{x['reason'] for x in v.rejected})

 def test_per_document_rejection_record(self):
  p=ModelProposal(entities=(ModelEntityProposal('Acme','Organization'),),relations=(ModelRelationProposal('Acme','has_component','Missing','Acme supplies Widget.'),))
  v=validate_proposal(p,TEXT,allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
  row=evaluation_record(doc_id='g7-dev-001',chunk_id='0',arm_id='B',validated=v)
  self.assertEqual(row['doc_id'],'g7-dev-001'); self.assertEqual(row['chunk_id'],'0'); self.assertEqual(row['arm_id'],'B')
  self.assertEqual(row['accepted_entities'],1); self.assertEqual(row['accepted_relations'],0)
  self.assertEqual(row['rejections'][0]['reason'],'ENDPOINT_UNRESOLVED'); self.assertEqual(row['rejections'][0]['count'],1)

if __name__=='__main__': unittest.main()
