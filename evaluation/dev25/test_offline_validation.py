import unittest, json
from pathlib import Path
from offline_validation import (validate_entities, validate_relations, reject_unsupported_relations,
                                 estimate_chunks, norm_predicate)

TYPES=set('MaintenanceSystem, Component, Protocol, Procedure, Material, Organization, Person, Role, Product, Document, Standard, Metric, Measurement, Location, Time, Algorithm, DataStructure, Concept, Event, Problem'.split(', '))
PREDS=set('has_component, part_of, tested_every, replaced_every, signs, records, documents, performed_on, logs, verifies, replaced_at, requires, repeats_every, applied_to, is_a, used_in, improves_over, discussed_in, same_as, seeks, visits, combines, implements, uses, has, related_to, computes, measures, obeys, matches'.split(', '))

class OfflineValidationTests(unittest.TestCase):
    def test_chunk_sizing(self):
        self.assertEqual(estimate_chunks('x'*16001, 8000, 200)[-1] <= 16000, True)
        self.assertGreaterEqual(len(estimate_chunks('x'*16001, 8000, 200)), 3)

    def test_predicate_normalization(self):
        self.assertEqual(norm_predicate('hasSupplier'), 'has_supplier')
        self.assertEqual(norm_predicate('has_supplier'), 'has_supplier')

    def test_valid_entities(self):
        text='Acme supplies Widget'
        ents=[{'mention_id':'m1','surface_form':'Acme','char_start':0,'char_end':4,'proposed_type':'Organization','resolution_status':'resolved','canonical_id':'org-acme'}, {'mention_id':'m2','surface_form':'Widget','char_start':14,'char_end':20,'proposed_type':'Product','resolution_status':'resolved','canonical_id':'prod-widget'}]
        self.assertEqual(validate_entities(text,ents,TYPES),[])

    def test_bad_span_and_type(self):
        text='Acme'
        ents=[{'mention_id':'m1','surface_form':'Acme','char_start':1,'char_end':4,'proposed_type':'Alien','resolution_status':'resolved','canonical_id':'x'}]
        errs=validate_entities(text,ents,TYPES); self.assertTrue(any('surface mismatch' in e for e in errs)); self.assertTrue(any('type not allowed' in e for e in errs))

    def test_valid_relation(self):
        text='Acme supplies Widget'
        ents=[{'mention_id':'m1','surface_form':'Acme','char_start':0,'char_end':4,'proposed_type':'Organization'}, {'mention_id':'m2','surface_form':'Widget','char_start':14,'char_end':20,'proposed_type':'Product'}]
        rels=[{'subject_id':'m1','predicate':'uses','object_id':'m2','evidence_span_start':0,'evidence_span_end':20}]
        self.assertEqual(validate_relations(text,ents,rels,PREDS),[])

    def test_reject_missing_endpoint_and_bad_predicate(self):
        text='Acme supplies Widget'; ents=[{'mention_id':'m1','surface_form':'Acme','char_start':0,'char_end':4,'proposed_type':'Organization'}]
        rels=[{'subject_id':'m1','predicate':'invented','object_id':'m9','evidence_span_start':0,'evidence_span_end':4}]
        self.assertEqual(len(reject_unsupported_relations(text,ents,rels,PREDS)),0)
        self.assertTrue(any('missing object' in e for e in validate_relations(text,ents,rels,PREDS)))

    def test_gold_v1_offline(self):
        base=Path(__file__).resolve().parent
        import csv
        man=list(csv.DictReader(open(base/'manifest.tsv'),delimiter='\t'))
        checked=0
        for r in man:
            g=json.loads((base.parent.parent/r['gold_record']).read_text())
            text=(base/'extracted'/f"{r['doc_id']}.txt")
            if text.exists(): text=text.read_text(errors='replace')
            else: text=(base.parent.parent/r['source_path']).read_text(errors='replace')
            self.assertEqual(validate_entities(text,g['entities'],TYPES),[],r['doc_id'])
            self.assertEqual(validate_relations(text,g['entities'],g['relationships'],PREDS),[],r['doc_id'])
            checked+=1
        self.assertEqual(checked,25)

if __name__=='__main__': unittest.main()
