import unittest, json, csv
from pathlib import Path
from surface_anchoring import anchor_surface

class SurfaceAnchoringTests(unittest.TestCase):
    def test_unique_surface(self):
        r=anchor_surface('intro BERT and T5', 'BERT')
        self.assertEqual((r['status'],r['chunk_char_start'],r['chunk_char_end']),('RESOLVED',6,10))

    def test_bert_offset_discrepancy_is_not_shifted(self):
        text='prefix text model (BERT)'
        r=anchor_surface(text,'BERT',model_start=18,model_end=22)
        self.assertEqual(r['status'],'RESOLVED')
        self.assertEqual((r['char_start'],r['char_end']),(19,23))
        self.assertEqual((r['model_char_start'],r['model_char_end']),(18,22))
        text2='x'*40+'zzzzzzzBERT'; r2=anchor_surface(text2,'BERT',model_start=40,model_end=44)
        self.assertEqual((r2['char_start'],r2['char_end']),(47,51))
        self.assertEqual((r2['model_char_start'],r2['model_char_end']),(40,44))

    def test_repeated_surface_rejected_without_unique_context(self):
        text='BERT then BERT again'
        r=anchor_surface(text,'BERT')
        self.assertEqual(r['status'],'REJECTED'); self.assertEqual(r['reason'],'AMBIGUOUS_SURFACE')

    def test_repeated_surface_context_disambiguates(self):
        text='BERT then BERT again'
        r=anchor_surface(text,'BERT',context='then BERT again')
        self.assertEqual(r['status'],'RESOLVED'); self.assertEqual(r['chunk_char_start'],10)

    def test_missing_surface(self):
        r=anchor_surface('no match here','BERT')
        self.assertEqual((r['status'],r['reason']),('REJECTED','SURFACE_NOT_FOUND'))

    def test_unicode_and_punctuation(self):
        r=anchor_surface('“Müller,” said the model.','Müller')
        self.assertEqual((r['status'],r['chunk_char_start'],r['chunk_char_end']),('RESOLVED',1,7))

    def test_chunk_and_document_offsets(self):
        r=anchor_surface('xx BERT', 'BERT', chunk_start=60000)
        self.assertEqual((r['chunk_char_start'],r['char_start']),(3,60003))

    def test_duplicate_mentions_are_not_deduplicated(self):
        a=anchor_surface('BERT and BERT','BERT',context='and BERT')
        b=anchor_surface('BERT and BERT','BERT',context='BERT and')
        self.assertEqual((a['status'],b['status']),('RESOLVED','RESOLVED'))
        self.assertNotEqual(a['char_start'],b['char_start'])

    def test_all_gold_v1_offsets(self):
        base=Path(__file__).resolve().parent
        man=list(csv.DictReader(open(base/'manifest.tsv'),delimiter='\t'))
        checked=0
        for r in man:
            g=json.loads((base.parent.parent/r['gold_record']).read_text())
            textp=base/'extracted'/f"{r['doc_id']}.txt"
            text=textp.read_text(errors='replace') if textp.exists() else (base.parent.parent/r['source_path']).read_text(errors='replace')
            for e in g['entities']:
                self.assertEqual(text[e['char_start']:e['char_end']],e['surface_form'],r['doc_id'])
            checked+=1
        self.assertEqual(checked,25)

if __name__=='__main__': unittest.main()
