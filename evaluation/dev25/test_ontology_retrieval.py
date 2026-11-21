import unittest,json
from ontology_context import OntologyItem, retrieve_ontology, snapshot_json

ITEMS=(
 OntologyItem('t1','Class','Component','A physical component.', 'tenant-a','v1',('part_of',)),
 OntologyItem('t2','Predicate','has_component','Connects a system to a component.','tenant-a','v1'),
 OntologyItem('t3','Class','Organization','An organization.','tenant-a','v1'),
 OntologyItem('t4','Class','Component','Other tenant copy.','tenant-b','v1'),
 OntologyItem('t5','Class','Component','Old ontology copy.','tenant-a','v0'),
)

class OntologyRetrievalTests(unittest.TestCase):
 def test_relevant_items_selected(self):
  s=retrieve_ontology('component has_component',ITEMS,tenant='tenant-a',ontology_version='v1')
  self.assertIn('t1',s.object_ids); self.assertIn('t2',s.object_ids); self.assertNotIn('t3',s.object_ids)
 def test_tenant_isolation(self):
  s=retrieve_ontology('component',ITEMS,tenant='tenant-a',ontology_version='v1')
  self.assertNotIn('t4',s.object_ids)
 def test_version_pin(self):
  s=retrieve_ontology('component',ITEMS,tenant='tenant-a',ontology_version='v0')
  self.assertEqual(s.object_ids,('t5',))
 def test_deterministic_snapshot(self):
  a=retrieve_ontology('component',ITEMS,tenant='tenant-a',ontology_version='v1')
  b=retrieve_ontology('component',ITEMS,tenant='tenant-a',ontology_version='v1')
  self.assertEqual(snapshot_json(a),snapshot_json(b)); self.assertEqual(a.context_sha256,b.context_sha256)
 def test_context_budget(self):
  s=retrieve_ontology('component has_component',ITEMS,tenant='tenant-a',ontology_version='v1',max_context_chars=20)
  self.assertLessEqual(len(s.context),20)
 def test_top_k(self):
  s=retrieve_ontology('component has_component',ITEMS,tenant='tenant-a',ontology_version='v1',top_k=1)
  self.assertEqual(len(s.object_ids),1)

if __name__=='__main__': unittest.main()
