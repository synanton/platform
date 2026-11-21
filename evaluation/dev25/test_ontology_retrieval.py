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
 def test_gate_cross_tenant_or_missing_version_fails_closed(self):
  s=retrieve_ontology('component',ITEMS,tenant='tenant-a',ontology_version='v-missing')
  self.assertEqual(s.object_ids,())
  s=retrieve_ontology('component',ITEMS,tenant='tenant-b',ontology_version='v1')
  self.assertEqual(s.object_ids,('t4',))
  s=retrieve_ontology('component',ITEMS,tenant='tenant-a',ontology_version='v1')
  self.assertNotIn('t4',s.object_ids); self.assertNotIn('t5',s.object_ids)

 def test_gate_deterministic_tie_breaking(self):
  items=(OntologyItem('z','Class','Component','component', 'tenant-a','v1'),OntologyItem('a','Class','Component','component','tenant-a','v1'))
  s1=retrieve_ontology('component',items,tenant='tenant-a',ontology_version='v1')
  s2=retrieve_ontology('component',tuple(reversed(items)),tenant='tenant-a',ontology_version='v1')
  self.assertEqual(s1.object_ids,('a','z')); self.assertEqual(s1.object_ids,s2.object_ids)

 def test_gate_budget_after_serialization(self):
  items=(OntologyItem('a','Class','A','aaaa','tenant-a','v1'),OntologyItem('b','Class','B','bbbb','tenant-a','v1'))
  raw=len('[Class] A: aaaa')+len('[Class] B: bbbb')
  s=retrieve_ontology('A B',items,tenant='tenant-a',ontology_version='v1',max_context_chars=raw)
  self.assertLessEqual(len(s.context),raw)

 def test_gate_stable_snapshot_hash_under_input_order(self):
  s1=retrieve_ontology('component has_component',ITEMS,tenant='tenant-a',ontology_version='v1')
  s2=retrieve_ontology('component has_component',tuple(reversed(ITEMS)),tenant='tenant-a',ontology_version='v1')
  self.assertEqual(snapshot_json(s1),snapshot_json(s2)); self.assertEqual(s1.context_sha256,s2.context_sha256)

 def test_gate_context_is_not_validated_extraction(self):
  from extraction_contract import validate_proposal, ValidatedExtraction
  from ontology_context import ContextSnapshot
  s=retrieve_ontology('component',ITEMS,tenant='tenant-a',ontology_version='v1')
  self.assertNotIsInstance(s,ValidatedExtraction)
  with self.assertRaises(TypeError): validate_proposal(s,'component',allowed_types=frozenset(),allowed_predicates=frozenset())
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
