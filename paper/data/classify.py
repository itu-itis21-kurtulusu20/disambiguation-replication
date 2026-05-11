#!/usr/bin/env python3
"""
Deterministic decision-tree classifier for LLM disambiguation failures.

Implements the taxonomy of Section 2 of the paper. Given a failure record,
assigns exactly one leaf category (A.1, A.2, B, C.1, C.2).

Each failure record is a dict with the following fields:
    test_id          : str               # e.g. "n01-clarity"
    key_term         : str               # e.g. "clarity"
    has_canonical    : bool              # Q1: does the term have a single canonical formula?
    only_behavior    : bool              # Q1a: does prompt describe only output behavior?
    multiple_defs    : bool              # Q2: does the term map to >=2 standard formulas across domains?
    wrong_component  : bool              # Q3: did the LLM identify operation but pick wrong sub-piece?
    wrong_piece      : str  # "reference" | "operator" | None  -- only if wrong_component is True

The classifier walks the decision tree top-down; the first matching leaf wins.

Usage:
    $ ./classify.py < records.jsonl > classified.jsonl
or programmatically:
    from classify import classify
    leaf = classify(record)
"""
import json
import sys
from typing import Dict, Optional


def classify(rec: Dict) -> Dict:
    """Return rec augmented with `category` (one of A.1, A.2, B, C.1, C.2)."""
    out = dict(rec)

    # Q1: Does the key term have ANY standard mathematical definition?
    if not rec.get("has_canonical", False):
        # No canonical definition -> Semantic Gap branch
        # Q1a: Does the prompt only describe behavior (no algorithm)?
        if rec.get("only_behavior", False):
            out["category"] = "A.2"
            out["rationale"] = "No canonical formula; prompt describes behavior only."
        else:
            out["category"] = "A.1"
            out["rationale"] = "No canonical formula; informal lexical term."
        return out

    # Yes, has canonical definition.
    # Q2: Does the term have multiple standard definitions across domains?
    if rec.get("multiple_defs", False):
        out["category"] = "B"
        out["rationale"] = "Multiple competing standard formulas (training-frequency bias)."
        return out

    # Single canonical, but the LLM still produced a wrong result.
    # Q3: Did the LLM correctly identify the operation type but choose the wrong component?
    if rec.get("wrong_component", False):
        piece = rec.get("wrong_piece")
        if piece == "reference":
            out["category"] = "C.1"
            out["rationale"] = "Correct operation; wrong reference point."
        elif piece == "operator":
            out["category"] = "C.2"
            out["rationale"] = "Correct operation; wrong reduction operator."
        else:
            out["category"] = "C"
            out["rationale"] = "Correct operation; wrong component (sub-leaf unspecified)."
        return out

    # None of the above -- behavioral specification masquerading as standard
    out["category"] = "A.2"
    out["rationale"] = "Behavioral specification without algorithm."
    return out


def cohens_kappa(rater_a: list, rater_b: list) -> float:
    """Compute Cohen's kappa between two equal-length lists of categorical labels."""
    assert len(rater_a) == len(rater_b), "Lists must be same length"
    n = len(rater_a)
    if n == 0:
        return 0.0
    categories = set(rater_a) | set(rater_b)
    # Observed agreement
    po = sum(1 for a, b in zip(rater_a, rater_b) if a == b) / n
    # Expected agreement by chance
    pe = 0.0
    for c in categories:
        pa = rater_a.count(c) / n
        pb = rater_b.count(c) / n
        pe += pa * pb
    if pe == 1.0:
        return 1.0
    return (po - pe) / (1.0 - pe)


def _self_test() -> None:
    """Run a small built-in sanity check on the classifier."""
    cases = [
        ({"test_id": "intensity",  "has_canonical": False, "only_behavior": False}, "A.1"),
        ({"test_id": "smoothness", "has_canonical": False, "only_behavior": True},  "A.2"),
        ({"test_id": "entropy",    "has_canonical": True,  "multiple_defs": True}, "B"),
        ({"test_id": "center",     "has_canonical": True,  "multiple_defs": False,
          "wrong_component": True, "wrong_piece": "reference"}, "C.1"),
        ({"test_id": "avg-diff",   "has_canonical": True,  "multiple_defs": False,
          "wrong_component": True, "wrong_piece": "operator"},  "C.2"),
    ]
    for rec, expected in cases:
        got = classify(rec)["category"]
        assert got == expected, f"{rec['test_id']}: expected {expected}, got {got}"
    print("self-test: OK (5/5)", file=sys.stderr)

    # Kappa sanity
    a = ["A.1", "A.1", "B", "C.1", "C.2"]
    b = ["A.1", "A.2", "B", "C.1", "C.2"]
    k = cohens_kappa(a, b)
    print(f"sample kappa = {k:.3f} (expected ~0.74)", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        _self_test()
        sys.exit(0)

    # Read JSON-lines from stdin, classify, write JSON-lines to stdout
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        out = classify(rec)
        sys.stdout.write(json.dumps(out) + "\n")
