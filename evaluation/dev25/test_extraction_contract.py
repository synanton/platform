import unittest
from extraction_contract import (ModelProposal, ModelEntityProposal, ModelRelationProposal,
                                 ModelEntityProposal, validate_proposal, ValidatedExtraction)
from extraction_pipeline import ALLOWED_TYPES, ALLOWED_PREDICATES

class ContractTests(unittest.TestCase):
    def test_model_proposal_requires_explicit_validation(self):
        p=ModelProposal(entities=(ModelEntityProposal('Acme','Organization'),),relations=())
        self.assertNotIsInstance(p, ValidatedExtraction)
        v=validate_proposal(p,'Acme supplies Widget',allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
        self.assertEqual(len(v.entities),1); self.assertEqual(v.entities[0].char_start,0)

    def test_unique_surface_offsets_are_computed(self):
        p=ModelProposal(entities=(ModelEntityProposal('BERT','Algorithm'),),relations=())
        v=validate_proposal(p,'prefix zzzzzzz BERT',allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
        self.assertEqual((v.entities[0].char_start,v.entities[0].char_end),(15,19))

    def test_ambiguous_surface_rejected(self):
        p=ModelProposal(entities=(ModelEntityProposal('BERT','Algorithm'),),relations=())
        v=validate_proposal(p,'BERT then BERT',allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
        self.assertEqual(len(v.entities),0); self.assertEqual(v.rejected[0]['reason'],'AMBIGUOUS_SURFACE')

    def test_repeated_surface_context_disambiguates(self):
        p=ModelProposal(entities=(ModelEntityProposal('BERT','Algorithm',context='then BERT'),),relations=())
        v=validate_proposal(p,'BERT then BERT',allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
        self.assertEqual(len(v.entities),1); self.assertEqual(v.entities[0].char_start,10)

    def test_relation_requires_unique_accepted_endpoints(self):
        p=ModelProposal(entities=(ModelEntityProposal('Acme','Organization'),ModelEntityProposal('Widget','Product')),relations=(ModelRelationProposal('Acme','has_component','Widget','Acme supplies Widget'),))
        v=validate_proposal(p,'Acme supplies Widget',allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
        self.assertEqual(len(v.relationships),1); self.assertEqual(v.relationships[0].evidence_start,0)

    def test_relation_missing_endpoint_rejected(self):
        p=ModelProposal(entities=(ModelEntityProposal('Acme','Organization'),),relations=(ModelRelationProposal('Acme','has_component','Widget','Acme supplies Widget'),))
        v=validate_proposal(p,'Acme supplies Widget',allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
        self.assertEqual(len(v.relationships),0); self.assertEqual(v.rejected[0]['reason'],'AMBIGUOUS_OR_MISSING_ENDPOINT')

    def test_bad_type_rejected(self):
        p=ModelProposal(entities=(ModelEntityProposal('Acme','Alien'),),relations=())
        v=validate_proposal(p,'Acme',allowed_types=ALLOWED_TYPES,allowed_predicates=ALLOWED_PREDICATES)
        self.assertEqual(v.rejected[0]['reason'],'TYPE_NOT_ALLOWED')

if __name__=='__main__': unittest.main()
