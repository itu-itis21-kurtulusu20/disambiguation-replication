# Disambiguation Failures in LLM Code Generation — Replication Package

This repository is the replication package for an **ASE 2026 NIER** submission
on a fine-grained taxonomy of LLM disambiguation failures in underspecified
software requirements.

## Repository layout

```
.
├── paper/                                       LaTeX source + figures + reproducibility scripts
│   ├── main.tex                                 4-page IEEEtran paper draft
│   ├── REPLICATION.md                           detailed reproduction instructions
│   ├── data/
│   │   ├── classify.py                          deterministic decision-tree classifier
│   │   └── classification.csv                   independent re-classification (80 cases)
│   └── figs/                                    PDF/PNG figures + matplotlib generator
├── src/main/java/                               GenTest evaluation platform (Spring Boot 3.2)
└── src/main/resources/
    ├── ambiguity-fail-only/        82 YAMLs    Study 1 corpus part 1 (Claude Sonnet 4)
    ├── ambiguity-test-cases/       16 YAMLs    Study 1 corpus part 2 (overlapping)
    └── test-cases/
        ├── numeric-ambiguity-v2/  100 YAMLs    Study 2 (cross-model: DeepSeek-Chat)
        ├── crud-level6/           100 YAMLs    Study 3 (cross-domain: Spring Boot CRUD)
        └── mitigation-fix/         15 YAMLs    Study 4 (mitigation experiment)
```

## Quick start

See [`paper/REPLICATION.md`](paper/REPLICATION.md) for the full reproduction
recipe (corpora, run commands, classifier, figures, inter-rater Cohen's κ).

## Reproducing core numbers

```bash
# 1. Decision-tree classifier self-test + Cohen's kappa over 80 cases
cd paper/data
python3 classify.py --self-test
python3 -c "
import csv
from classify import cohens_kappa
rows = list(csv.DictReader(open('classification.csv')))
a = [r['rater_paper'] for r in rows]
b = [r['rater_independent'] for r in rows]
print('agreement', sum(x==y for x,y in zip(a,b)), '/', len(rows))
print('kappa', cohens_kappa(a, b))
"
# expected: agreement 71/80, kappa 0.855

# 2. Regenerate figures
cd ../figs
python3 -m pip install matplotlib
python3 make_figures.py
```

## Anonymity statement

This repository was prepared for **double-blind review**. All committer
identities have been replaced with `Anonymous`, the commit history was
squashed to a single submission commit, and no author-identifying information
appears in source files, configuration, or documentation.

The original (deanonymized) repository will be released following the review
period.
