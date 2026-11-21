import unittest, json, csv
from pathlib import Path
from extraction_pipeline import (plan_chunks, stage1_entities, stage2_relations,
                                 merge_chunk_entities, validate_provider_output_budget,
                                 preflight_output_budget,
                                 ALLOWED_TYPES, ALLOWED_PREDICATES)
from offline_validation import norm_predicate

class PipelineTests(unittest.TestCase):
    def test_chunk_coverage_and_budget(self):
        text='x'*12001
        chunks=plan_chunks(text,5000,200,max_output_tokens=32768)
        self.assertEqual(chunks[0]['char_start'],0)
        self.assertEqual(chunks[-1]['char_end'],len(text))
        for a,b in zip(chunks,chunks[1:]): self.assertLess(a['char_start'],b['char_start'])
        self.assertTrue(all(c['recommended_max_tokens'] <= 32768 for c in chunks))

    def test_large_output_caps_and_provider_validation(self):
        text='x'*12000
        c32=plan_chunks(text,12000,0,max_output_tokens=32768)[0]
        c64=plan_chunks(text,12000,0,max_output_tokens=65536)[0]
        self.assertGreaterEqual(c32['recommended_max_tokens'],2048)
        self.assertLessEqual(c32['recommended_max_tokens'],32768)
        self.assertLessEqual(c64['recommended_max_tokens'],65536)
        self.assertEqual(c32['output_char_ratio'],3.0)
        self.assertIsNone(validate_provider_output_budget(c32['recommended_max_tokens'],32768))
        self.assertIsNotNone(validate_provider_output_budget(65536,32768))
        self.assertEqual(preflight_output_budget([c32],32768,'live:/v1/models'),[])
        big=plan_chunks(text,12000,0,max_output_tokens=65536,output_char_ratio=12.0)[0]
        oversized=preflight_output_budget([big],32768,'live:/v1/models')
        self.assertEqual(oversized[0]['reason'],'chunk output budget exceeds provider limit')
        self.assertTrue(preflight_output_budget([c32],32768,None))
        self.assertTrue(preflight_output_budget([c32],None,'live:/v1/models'))

    def test_ratio_is_configurable_and_observable(self):
        text='x'*12000
        a=plan_chunks(text,12000,0,max_output_tokens=65536,output_char_ratio=2.0)[0]
        b=plan_chunks(text,12000,0,max_output_tokens=65536,output_char_ratio=4.0)[0]
        self.assertEqual(a['output_char_ratio'],2.0); self.assertEqual(b['output_char_ratio'],4.0)
        self.assertLess(a['recommended_max_tokens'],b['recommended_max_tokens'])

    def test_boundary_conditions(self):
        self.assertEqual(plan_chunks('',5000),[])
        exact=plan_chunks('x'*5000,5000,0)
        self.assertEqual(len(exact),1); self.assertEqual(exact[0]['char_end'],5000)
        short=plan_chunks('x',5000,0)
        self.assertEqual(short[0]['recommended_max_tokens'],2048)
        with self.assertRaises(ValueError): plan_chunks('x',5000,5000)

    def test_stage1_rejects_bad_type_and_span(self):
        text='Acme supplies Widget'
        ents=stage1_entities(text,[
          {'mention_id':'m1','surface_form':'Acme','char_start':0,'char_end':4,'proposed_type':'Organization'},
          {'mention_id':'m2','surface_form':'Widget','char_start':14,'char_end':20,'proposed_type':'Alien'},
          {'mention_id':'m3','surface_form':'Acme','char_start':1,'char_end':4,'proposed_type':'Organization'}])
        self.assertEqual([e['mention_id'] for e in ents],['m1'])

    def test_stage2_endpoint_and_direction(self):
        text='Acme supplies Widget'
        ents=[{'mention_id':'m1','surface_form':'Acme','char_start':0,'char_end':4,'proposed_type':'Organization'}, {'mention_id':'m2','surface_form':'Widget','char_start':14,'char_end':20,'proposed_type':'Product'}]
        cands=[
          {'subject_id':'m1','predicate':'has_component','object_id':'m2','direction':'subject_to_object','evidence_span_start':0,'evidence_span_end':20},
          {'subject_id':'m2','predicate':'has_component','object_id':'m1','direction':'object_to_subject','evidence_span_start':0,'evidence_span_end':20},
          {'subject_id':'m1','predicate':'invented','object_id':'m2','evidence_span_start':0,'evidence_span_end':20},
          {'subject_id':'m1','predicate':'has_component','object_id':'m9','evidence_span_start':0,'evidence_span_end':20},
        ]
        ok,bad=stage2_relations(text,ents,cands)
        self.assertEqual(len(ok),2)
        self.assertTrue(all(r['predicate']=='has_component' for r in ok))
        self.assertEqual({x[1] for x in bad},{'predicate_not_allowed','missing_endpoint'})

    def test_merge_provenance(self):
        chunks=[({'chunk_id':0,'char_start':0},[{'mention_id':'a','surface_form':'Acme','char_start':0,'char_end':4,'proposed_type':'Organization'}]),({'chunk_id':1,'char_start':100},[{'mention_id':'b','surface_form':'Globex','char_start':5,'char_end':11,'proposed_type':'Organization'}])]
        merged=merge_chunk_entities(chunks)
        self.assertEqual([(m['char_start'],m['chunk_id']) for m in merged],[(0,0),(105,1)])

    def test_gold_v1_relations_filter(self):
        base=Path(__file__).resolve().parent
        man=list(csv.DictReader(open(base/'manifest.tsv'),delimiter='\t'))
        checked=0
        for r in man:
            g=json.loads((base.parent.parent/r['gold_record']).read_text())
            textp=base/'extracted'/f"{r['doc_id']}.txt"
            text=textp.read_text(errors='replace') if textp.exists() else (base.parent.parent/r['source_path']).read_text(errors='replace')
            ok,bad=stage2_relations(text,g['entities'],g['relationships'])
            self.assertEqual(bad,[],r['doc_id']); self.assertEqual(len(ok),len(g['relationships']),r['doc_id']); checked+=1
        self.assertEqual(checked,25)

if __name__=='__main__': unittest.main()
