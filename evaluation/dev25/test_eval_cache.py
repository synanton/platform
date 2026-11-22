import unittest
from eval_cache import CacheKey,ScopedCache

A=CacheKey('tenant-a','A','src')
B1=CacheKey('tenant-a','B','src','ont1','r1')
B2=CacheKey('tenant-a','B','src','ont2','r1')
class CacheTests(unittest.TestCase):
 def test_same_scope_reuse(self):
  c=ScopedCache(); self.assertTrue(c.put(A,{'v':1})); self.assertEqual(c.get(A),{'v':1}); self.assertEqual(c.stats()['hits'],1)
 def test_cross_tenant_miss(self):
  c=ScopedCache(); c.put(A,1); self.assertIsNone(c.get(CacheKey('tenant-b','A','src')))
 def test_cross_arm_miss(self):
  c=ScopedCache(); c.put(B1,1); self.assertIsNone(c.get(A)); self.assertIsNone(c.get(B2))
 def test_arm_a_cannot_use_ontology_key(self):
  c=ScopedCache(); k=CacheKey('tenant-a','A','src','ont1','r1')
  self.assertFalse(c.put(k,1)); self.assertIsNone(c.get(k)); self.assertTrue(c.rejected)
 def test_arm_b_requires_snapshot(self):
  c=ScopedCache(); k=CacheKey('tenant-a','B','src')
  self.assertFalse(c.put(k,1)); self.assertIsNone(c.get(k))
 def test_stale_snapshot_miss_and_invalidation(self):
  c=ScopedCache(); c.put(B1,1); self.assertIsNone(c.get(B2)); self.assertEqual(c.invalidate(ontology_snapshot_hash='ont1'),1); self.assertIsNone(c.get(B1))

if __name__=='__main__': unittest.main()
