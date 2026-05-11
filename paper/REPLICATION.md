# Replication Package

This directory accompanies the ASE 2026 NIER submission *"Why LLM-Generated Code
Quietly Fails: A Decision-Tree Taxonomy of Disambiguation Errors in
Underspecified Software Requirements"*.

## Contents

```
paper/
├── main.tex                      LaTeX source (IEEEtran conference, 4 pages)
├── REPLICATION.md                this file
├── data/
│   ├── classify.py               deterministic decision-tree classifier
│   └── classification.csv        independent re-classification, 80 cases
├── figs/
│   ├── make_figures.py           regenerates the figures
│   ├── fig_cross_model.pdf       Sonnet 4 vs DeepSeek per-category bars
│   └── fig_crud_bridge.pdf       62 CRUD failures -> 17 mapped to taxonomy
src/main/resources/test-cases/
├── ambiguity-fail-only/          82 numeric ambiguity YAMLs (Sonnet 4 corpus)
├── ambiguity-test-cases/         16 earlier-iteration YAMLs (overlapping)
├── numeric-ambiguity-v2/         100 fresh ambiguity YAMLs (DeepSeek corpus)
├── crud-level6/                  100 CRUD-domain YAMLs targeting Level 6
└── mitigation-fix/               15 mitigation-applied YAMLs (3 per category)
```

## Reproducing the empirical study

### Prerequisites

* Java 17, Maven 3.9, PostgreSQL 16
* GenTest platform (root of this repository)
* API access to one or more LLM providers (DeepSeek-Chat used for v2 runs)

### Running a single corpus

```bash
# 1) start postgres (host=localhost:5432 expected; password via $POSTGRES_PASSWORD)
# 2) start the GenTest application
DEEPSEEK_API_KEY=<your-key>      \
OPENAI_API_KEY=dummy             \
GEMINI_API_KEY=dummy             \
ANTHROPIC_API_KEY=dummy          \
POSTGRES_PASSWORD=<password>     \
mvn -q spring-boot:run

# 3) trigger evaluation on a corpus (replace <group>)
curl -sS "http://localhost:8080/api/test-runner/run-all\
?group=numeric-ambiguity-v2\
&promptProfile=ENHANCED\
&includeProjectContext=true"
```

Available groups: `numeric-ambiguity-v2`, `crud-level6`, `mitigation-fix`.

### Reproducing the classifier and inter-rater agreement

```bash
cd paper/data
python3 classify.py --self-test         # built-in sanity check
python3 -c "
import csv
from classify import cohens_kappa
rows = list(csv.DictReader(open('classification.csv')))
a = [r['rater_paper']      for r in rows]
b = [r['rater_independent'] for r in rows]
print('agreement', sum(x==y for x,y in zip(a,b)), '/', len(rows))
print('kappa', cohens_kappa(a, b))
"
```

Expected output: agreement 71/80, kappa 0.855.

### Regenerating the figures

```bash
cd paper/figs
python3 -m pip install matplotlib            # one-time
python3 make_figures.py
# writes fig_cross_model.{pdf,png} and fig_crud_bridge.{pdf,png}
```

## Test case anatomy

Each YAML file under `src/main/resources/test-cases/<group>/` has three required
fields (the framework also accepts additional optional fields):

```yaml
prompt: >
  Free-form, deliberately underspecified natural-language requirement.
fullClassName: com.example.llmdyn.dynamic.utils.SomeAmbiguity
tests:
  - methodName: someMethod
    args: [[1, 2, 3]]
    expectedResult: 2.0           # author's intended interpretation
```

The LLM only sees `prompt` and the inferred method signature; expected outputs
are withheld.

## Anonymity

This package was prepared for double-blind review. All git committer identities
in this fork have been replaced with `Anonymous`. The original repository will
be released after the review period.
