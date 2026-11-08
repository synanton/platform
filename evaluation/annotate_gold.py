#!/usr/bin/env python3
"""Produce the frozen extraction gold from the manual annotations.

The entity/relationship decisions below were made by reading
evaluation/corpus/*.txt only (no Cassandra, regex, Lucene, or model output).
This script only resolves the chosen surface forms to character offsets and
writes the JSON; it is not an extractor.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CORPUS = ROOT / "corpus"
GOLD = ROOT / "gold"

# document_id -> (corpus file, entities, relationships)
# entity tuple: (surface, occurrence, proposed_type, resolution_status, canonical_id)
# relationship tuple: (subject_mention, predicate, object_mention, evidence_substring, evidence_occurrence)
DOCS = {
    "halcyon-7-directive": {
        "file": "halcyon-7-directive.txt",
        "entities": [
            ("Halcyon-7", 0, "MaintenanceSystem", "resolved", "entity-halcyon-7"),
            ("Maintenance Directive", 0, "Document", "resolved", "doc-halcyon-7-directive"),
            ("auxiliary coolant loop", 0, "Component", "resolved", "component-halcyon7-coolant-loop"),
            ("relief valve", 0, "Component", "resolved", "component-relief-valve"),
            ("secondary manifold", 0, "Component", "resolved", "component-secondary-manifold"),
            ("technicians", 0, "Role", "resolved", "role-halcyon7-technician"),
            ("pressure-test log", 0, "Document", "resolved", "doc-pressure-test-log"),
            ("ambient dew point", 0, "Measurement", "resolved", "measurement-ambient-dew-point"),
            ("ninety days", 0, "Time", "resolved", "time-ninety-days"),
            ("seventy days", 0, "Time", "resolved", "time-seventy-days"),
        ],
        "relationships": [
            ("Halcyon-7", "has_component", "auxiliary coolant loop",
             "The Halcyon-7 auxiliary coolant loop must be pressure-tested every ninety days.", 0),
            ("relief valve", "part_of", "secondary manifold", "relief valve on", 0),
            ("auxiliary coolant loop", "tested_every", "ninety days",
             "must be pressure-tested every ninety days", 0),
            ("relief valve", "replaced_every", "seventy days",
             "must be replaced every two hundred", 0),
            ("technicians", "signs", "pressure-test log",
             "technicians may sign the pressure-test log", 0),
            ("pressure-test log", "records", "ambient dew point",
             "every test must record the ambient dew point", 0),
            ("Maintenance Directive", "documents", "Halcyon-7",
             "Project Halcyon-7 Maintenance Directive", 0),
        ],
    },
    "e650-live-dense-v2": {
        "file": "e650-live-dense-v2.txt",
        "entities": [
            ("Zephyr-9", 0, "MaintenanceSystem", "resolved", "entity-zephyr-9"),
            ("platform", 0, "MaintenanceSystem", "ambiguous", None),
            ("Zephyr-9 protocol", 0, "Protocol", "resolved", "protocol-zephyr-9"),
            ("auxiliary coolant manifold", 0, "Component", "resolved", "component-coolant-manifold"),
            ("Technicians", 0, "Role", "unresolved", None),
            ("titanium retaining fasteners", 0, "Component", "resolved", "component-titanium-fasteners"),
            ("audit tolerance", 0, "Metric", "resolved", "metric-audit-tolerance"),
            ("two percent", 0, "Metric", "resolved", "metric-two-percent"),
            ("helium leak", 0, "Procedure", "resolved", "procedure-helium-leak-check"),
            ("audit cycle", 0, "Event", "resolved", "event-audit-cycle"),
            ("Quarterly torque audits", 0, "Procedure", "resolved", "procedure-quarterly-torque-audit"),
            ("manifold pressure drift", 0, "Measurement", "resolved", "measurement-manifold-pressure-drift"),
        ],
        "relationships": [
            ("Quarterly torque audits", "performed_on", "auxiliary coolant manifold",
             "Quarterly torque audits of the auxiliary coolant manifold are mandatory", 0),
            ("Technicians", "logs", "manifold pressure drift",
             "Technicians must log manifold pressure drift", 0),
            ("Technicians", "verifies", "titanium retaining fasteners",
             "titanium retaining fasteners", 0),
            ("titanium retaining fasteners", "replaced_at", "two percent",
             "replace any fastener whose audit tolerance", 0),
            ("Zephyr-9 protocol", "requires", "helium leak",
             "The Zephyr-9 protocol uniquely requires a helium leak", 0),
            ("helium leak", "repeats_every", "audit cycle",
             "after every fifth audit cycle", 0),
            ("Zephyr-9", "has_component", "auxiliary coolant manifold",
             "Quarterly torque audits of the auxiliary coolant manifold", 0),
        ],
    },
    "skiena-sorting": {
        "file": "skiena-sorting.txt",
        "entities": [
            ("Sorting", 0, "Concept", "resolved", "concept-sorting"),
            ("sorting algorithms", 0, "Algorithm", "resolved", "algorithm-sorting"),
            ("data structures", 0, "DataStructure", "resolved", "concept-data-structures"),
            ("algorithms", 1, "Concept", "resolved", "concept-algorithms"),
            ("divide-and-conquer", 0, "Algorithm", "resolved", "algorithm-divide-and-conquer"),
            ("randomized algorithms", 0, "Algorithm", "resolved", "algorithm-randomized"),
            ("heapsort", 0, "Algorithm", "resolved", "algorithm-heapsort"),
            ("mergesort", 0, "Algorithm", "resolved", "algorithm-mergesort"),
            ("quicksort", 0, "Algorithm", "resolved", "algorithm-quicksort"),
            ("distribution sort", 0, "Algorithm", "resolved", "algorithm-distribution-sort"),
            ("O(n log n)", 0, "Metric", "resolved", "metric-onlogn"),
            ("O(n2 )", 0, "Metric", "resolved", "metric-on2"),
            ("Section 17.1 (page 506)", 0, "Document", "resolved", "section-17-1"),
            ("problem catalog", 0, "Document", "resolved", "doc-problem-catalog"),
            ("Chapter 4", 0, "Document", "resolved", "doc-chapter-4"),
        ],
        "relationships": [
            ("Sorting", "applied_to", "algorithms", "sorting can be applied to", 0),
            ("heapsort", "is_a", "sorting algorithms",
             "fundamental algorithms: heapsort, mergesort, quicksort, and distribution sort", 0),
            ("mergesort", "is_a", "sorting algorithms",
             "fundamental algorithms: heapsort, mergesort, quicksort, and distribution sort", 0),
            ("quicksort", "is_a", "sorting algorithms",
             "fundamental algorithms: heapsort, mergesort, quicksort, and distribution sort", 0),
            ("distribution sort", "is_a", "sorting algorithms",
             "fundamental algorithms: heapsort, mergesort, quicksort, and distribution sort", 0),
            ("divide-and-conquer", "used_in", "Sorting",
             "the context of sorting, such as divide-and-conquer", 0),
            ("randomized algorithms", "used_in", "Sorting", "randomized algorithms", 0),
            ("O(n log n)", "improves_over", "O(n2 )",
             "This is a big improvement over naive O(n2 ) sorting algorithms", 0),
            ("Sorting", "discussed_in", "Section 17.1 (page 506)",
             "by Section 17.1 (page 506) in the problem catalog.", 0),
            ("Chapter 4", "documents", "Sorting", "Chapter 4", 0),
        ],
    },
    "skiena-dynamic-programming": {
        "file": "skiena-dynamic-programming.txt",
        "entities": [
            ("Dynamic Programming", 0, "Concept", "resolved", "concept-dynamic-programming"),
            ("optimization", 0, "Concept", "resolved", "concept-optimization"),
            ("objective function", 0, "Concept", "resolved", "concept-objective-function"),
            ("salesman", 0, "Problem", "resolved", "problem-traveling-salesman"),
            ("TSP", 0, "Problem", "resolved", "problem-tsp"),
            ("tour", 0, "Concept", "resolved", "concept-tour"),
            ("vertices", 0, "Concept", "resolved", "concept-vertex"),
            ("graph", 0, "DataStructure", "resolved", "concept-graph"),
            ("Greedy algorithms", 0, "Algorithm", "resolved", "algorithm-greedy"),
            ("Exhaustive search algorithms", 0, "Algorithm", "resolved", "algorithm-exhaustive-search"),
            ("time complexity", 0, "Concept", "resolved", "concept-time-complexity"),
            ("recursive algorithm", 0, "Algorithm", "resolved", "algorithm-recursive"),
            ("results matrix", 0, "DataStructure", "resolved", "datastructure-results-matrix"),
            ("table", 0, "DataStructure", "resolved", "datastructure-table"),
            ("Chapter 1", 0, "Document", "resolved", "doc-chapter-1"),
        ],
        "relationships": [
            ("salesman", "is_a", "optimization",
             "salesman is a classic optimization problem", 0),
            ("TSP", "same_as", "salesman", "propose TSP", 0),
            ("salesman", "seeks", "tour", "where we seek the tour visiting all", 0),
            ("tour", "visits", "vertices", "vertices of a graph at minimum total cost", 0),
            ("vertices", "part_of", "graph", "vertices of a graph", 0),
            ("Dynamic Programming", "combines", "Greedy algorithms",
             "Dynamic programming combines the best of both worlds", 0),
            ("Dynamic Programming", "combines", "Exhaustive search algorithms",
             "Dynamic programming combines the best of both worlds", 0),
            ("Dynamic Programming", "implements", "recursive algorithm",
             "implementing a recursive", 0),
            ("recursive algorithm", "uses", "table",
             "storing the answer for each subproblem in a table", 0),
            ("Dynamic Programming", "uses", "results matrix", "using a results matrix.", 0),
        ],
    },
    "skiena-tsp-parsing": {
        "file": "skiena-tsp-parsing.txt",
        "entities": [
            ("TSP", 0, "Problem", "resolved", "problem-tsp"),
            ("closed tour", 0, "Concept", "resolved", "concept-closed-tour"),
            ("path", 0, "Concept", "resolved", "concept-path"),
            ("edge (t, s)", 0, "Concept", "resolved", "concept-edge"),
            ("vertices", 0, "Concept", "resolved", "concept-vertex"),
            ("unweighted graphs", 0, "DataStructure", "resolved", "concept-unweighted-graph"),
            ("Hamiltonian paths", 0, "Problem", "resolved", "problem-hamiltonian-path"),
            ("Section 19.5 (page 598)", 0, "Document", "resolved", "section-19-5"),
            ("Dynamic Programming", 0, "Concept", "resolved", "concept-dynamic-programming"),
            ("recurrence relations", 0, "Concept", "resolved", "concept-recurrence-relation"),
            ("LP [i, j]", 0, "Metric", "resolved", "function-lp"),
            ("simple path", 0, "Concept", "resolved", "concept-simple-path"),
            ("vertex x", 0, "Concept", "resolved", "concept-vertex"),
            ("optimality", 0, "Concept", "resolved", "concept-optimality"),
            ("edit sequences", 0, "Concept", "resolved", "concept-edit-sequence"),
            ("approximate string matching", 0, "Algorithm", "resolved", "algorithm-approximate-string-matching"),
            ("pattern P", 0, "Concept", "resolved", "concept-pattern"),
            ("string T", 0, "Concept", "resolved", "concept-string"),
        ],
        "relationships": [
            ("TSP", "uses", "closed tour", "asks for a path instead of a closed tour", 0),
            ("unweighted graphs", "has", "edge (t, s)", "where each edge has cost 1", 0),
            ("Hamiltonian paths", "related_to", "simple path", "Finding such Hamiltonian paths", 0),
            ("Hamiltonian paths", "discussed_in", "Section 19.5 (page 598)",
             "discussed in Section 19.5 (page 598)", 0),
            ("Dynamic Programming", "requires", "recurrence relations",
             "Dynamic programming algorithms are only as correct as the recurrence relations", 0),
            ("recurrence relations", "computes", "LP [i, j]",
             "This suggests the following recurrence relation to compute", 0),
            ("LP [i, j]", "measures", "simple path",
             "LP [i, j] to be the length of the longest", 0),
            ("Dynamic Programming", "obeys", "optimality", "obeys the prin-", 0),
            ("approximate string matching", "uses", "edit sequences",
             "edit sequences that achieve a", 0),
            ("approximate string matching", "matches", "pattern P",
             "characters of pattern P", 0),
        ],
    },
}


def nth_occurrence(text: str, surface: str, occurrence: int) -> int:
    start = -1
    for _ in range(occurrence + 1):
        start = text.find(surface, start + 1)
        if start < 0:
            raise SystemExit(f"surface not found (occurrence {occurrence}): {surface!r}")
    return start


def build(document_id: str, spec: dict) -> dict:
    path = CORPUS / spec["file"]
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    entities = []
    by_surface: dict[str, str] = {}
    for index, (surface, occurrence, entity_type, status, canonical) in enumerate(spec["entities"], start=1):
        mention_id = f"m{index}"
        start = nth_occurrence(text, surface, occurrence)
        end = start + len(surface)
        assert text[start:end] == surface, (document_id, surface)
        assert (status == "resolved") == (canonical is not None), (document_id, surface)
        entities.append({
            "mention_id": mention_id,
            "surface_form": surface,
            "char_start": start,
            "char_end": end,
            "proposed_type": entity_type,
            "resolution_status": status,
            "canonical_id": canonical,
        })
        by_surface.setdefault(surface, mention_id)
    relationships = []
    for subject, predicate, obj, evidence, occurrence in spec["relationships"]:
        start = nth_occurrence(text, evidence, occurrence)
        relationships.append({
            "subject_id": by_surface[subject],
            "predicate": predicate,
            "object_id": by_surface[obj],
            "evidence_span_start": start,
            "evidence_span_end": start + len(evidence),
        })
        assert start >= 0 and text[start:start + len(evidence)] == evidence
    return {
        "document_id": document_id,
        "text_sha256": hashlib.sha256(raw).hexdigest(),
        "entities": entities,
        "relationships": relationships,
    }


def main() -> None:
    GOLD.mkdir(parents=True, exist_ok=True)
    for number, (document_id, spec) in enumerate(DOCS.items(), start=1):
        document = build(document_id, spec)
        target = GOLD / f"doc-{number}.json"
        target.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"{target.name}: {document_id} entities={len(document['entities'])} "
              f"relationships={len(document['relationships'])} sha={document['text_sha256'][:12]}")


if __name__ == "__main__":
    main()
