import unittest
from relation_scoring import score_relations

ENT = lambda i, s: {"mention_id": i, "surface_form": s}
REL = lambda s, p, o: {"subject_id": s, "predicate": p, "object_id": o}

class RelationScoringTests(unittest.TestCase):
    def test_matching_relation(self):
        p = [ENT("e1", "Acme"), ENT("e2", "Globex")]
        g = [ENT("g1", "Acme"), ENT("g2", "Globex")]
        m = score_relations(p, [REL("e1", "hasSupplier", "e2")], g, [REL("g1", "has_supplier", "g2")])
        self.assertEqual(m["f1"], 1.0)
        self.assertEqual(m["direction_errors"], 0)

    def test_incorrect_object_endpoint(self):
        p = [ENT("e1", "Acme"), ENT("e2", "Globex"), ENT("e3", "Initech")]
        g = [ENT("g1", "Acme"), ENT("g2", "Globex")]
        m = score_relations(p, [REL("e1", "hasSupplier", "e3")], g, [REL("g1", "hasSupplier", "g2")])
        self.assertEqual(m["f1"], 0.0)

    def test_direction_mismatch(self):
        p = [ENT("e1", "Acme"), ENT("e2", "Globex")]
        g = [ENT("g1", "Acme"), ENT("g2", "Globex")]
        m = score_relations(p, [REL("e2", "hasSupplier", "e1")], g, [REL("g1", "hasSupplier", "g2")])
        self.assertEqual(m["f1"], 0.0)
        self.assertEqual(m["direction_errors"], 1)

    def test_duplicate_mentions_dedupe(self):
        p = [ENT("e1", "Acme"), ENT("e2", "Acme"), ENT("e3", "Globex")]
        g = [ENT("g1", "Acme"), ENT("g2", "Globex")]
        m = score_relations(p, [REL("e1", "hasSupplier", "e3"), REL("e2", "hasSupplier", "e3")], g, [REL("g1", "hasSupplier", "g2")])
        self.assertEqual(m["tp"], 1)
        self.assertEqual(m["direction_errors"], 0)

    def test_missing_endpoint(self):
        p = [ENT("e1", "Acme")]
        g = [ENT("g1", "Acme"), ENT("g2", "Globex")]
        m = score_relations(p, [REL("e1", "hasSupplier", "e9")], g, [REL("g1", "hasSupplier", "g2")])
        self.assertEqual(m["f1"], 0.0)
        self.assertEqual(m["missing_endpoint_relations"], 1)

if __name__ == "__main__":
    unittest.main()
