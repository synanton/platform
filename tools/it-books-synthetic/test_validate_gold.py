import copy, json, sys, unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from validate_gold import validate, GoldError

ROOT = Path(__file__).resolve().parents[2]
VALID = json.loads((ROOT / 'evaluation/it-books/synthetic-ingestion-v1/gold/examples/valid.json').read_text())

class GoldValidationTest(unittest.TestCase):
    def setUp(self): self.doc = copy.deepcopy(VALID)
    def test_valid_example(self):
        result = validate(self.doc)
        self.assertEqual(result['chunks'], 3)
    def test_duplicate_mention_rejected(self):
        self.doc['mentions'].append(copy.deepcopy(self.doc['mentions'][0]))
        with self.assertRaises(GoldError): validate(self.doc)
    def test_unknown_predicate_rejected(self):
        self.doc['relations'][0]['predicate'] = 'IS_A'
        with self.assertRaises(GoldError): validate(self.doc)
    def test_metadata_label_mention_rejected(self):
        self.doc['mentions'].append({'id':'gold:bad:label','surface':'Edition','type':'Concept','chunkId':'gold:chunk:book','span':{'start':1,'end':8},'evidence':{'kind':'EXACT'}})
        with self.assertRaises(GoldError): validate(self.doc)
    def test_unknown_retrieval_chunk_rejected(self):
        self.doc['retrieval'][0]['expectedChunkIds'] = ['gold:missing']
        with self.assertRaises(GoldError): validate(self.doc)
    def test_invalid_domain_range_rejected(self):
        self.doc['relations'][0]['predicate'] = 'HAS_CHAPTER'
        with self.assertRaises(GoldError): validate(self.doc)

if __name__ == '__main__': unittest.main()
