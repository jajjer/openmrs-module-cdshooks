# Reference-map data for the drug-allergy matcher

Two things live here:

1. **`concept_reference_term_map_refapp3.csv`** — a snapshot of what a stock
   RefApp 3 already ships in its `concept_reference_term_map` table (the baseline
   + exact format).
2. **`rxclass_drug_class_edges.csv`** — a generated starter set of RxClass
   drug→class edges (the data Andrew asked about), produced by
   [`rxclass_extract.py`](rxclass_extract.py) from the public NLM RxClass API.

---

## 1. `concept_reference_term_map_refapp3.csv` — RefApp 3 baseline

Snapshot of the `concept_reference_term_map` table from a stock OpenMRS
Reference Application 3 backend (image tag `qa`), dumped from the dev
Docker stack in `dev/docker-compose.yml`.

## Format

One row per reference-term map. `source_a/code_a` (the descendant) maps to
`source_b/code_b` (the broader/ancestor term) via `map_type`. `name_a/name_b`
are the term display names where present (`""` where the dictionary stores none).

```
source_a,code_a,name_a,map_type,source_b,code_b,name_b
```

## What's in it (154 rows)

| source_a | → | source_b | map_type | rows | what it is |
|---|---|---|---|---|---|
| ATC | → | ATC | NARROWER-THAN | 100 | the ATC drug-class tree itself (child class → parent class) |
| RxNORM | → | ATC | NARROWER-THAN | 53 | drug ingredient → its ATC class |
| RxNORM | → | RxClass | NARROWER-THAN | 1 | **amoxicillin (723) → Penicillins (N0000175503)** |

## Read this before reusing the file

- **The 153 ATC rows ship with the stock RefApp dictionary.** OpenMRS already
  carries a drug→class mechanism out of the box — it's just **ATC**
  (Anatomical Therapeutic Chemical), not RxClass. This is directly relevant to
  the Talk discussion: the "load drug-class edges into
  `concept_reference_term_map`" idea is already partially realized via ATC.
- **The single RxNORM→RxClass row is NOT stock.** It was inserted by this
  module's own dev work (creator=admin, date 2026-06-14) as a fixture to prove
  the matcher's subsumption walk fires on the amoxicillin/Penicillins example.
  It is not authoritative RxClass content.
- So this export is useful as a **baseline + exact format reference**, not as a
  curated RxClass dataset. The authoritative RxClass class↔ingredient links
  still need to be generated from the public RxClass/RxNav source (NLM) and
  loaded in.

## How it was produced

```bash
docker compose -p cdshooks-dev -f dev/docker-compose.yml up -d
docker exec cdshooks-dev-db-1 mysql --user=openmrs --password=openmrs openmrs -N -B -e "
  SELECT csa.name, ta.code, COALESCE(ta.name,''), mt.name, csb.name, tb.code, COALESCE(tb.name,'')
  FROM concept_reference_term_map m
  JOIN concept_reference_term ta  ON m.term_a_id=ta.concept_reference_term_id
  JOIN concept_reference_source csa ON ta.concept_source_id=csa.concept_source_id
  JOIN concept_reference_term tb  ON m.term_b_id=tb.concept_reference_term_id
  JOIN concept_reference_source csb ON tb.concept_source_id=csb.concept_source_id
  JOIN concept_map_type mt ON m.a_is_to_b_id=mt.concept_map_type_id
  ORDER BY csa.name, ta.code, csb.name, tb.code;"
```

---

## 2. `rxclass_drug_class_edges.csv` — generated RxClass starter set

140 `RxNORM ingredient → NARROWER-THAN → RxClass class` edges across 26
Established Pharmacologic Classes (EPC) covering the common drug-allergy
families (penicillins, cephalosporins, sulfonamides, macrolides, tetracyclines,
quinolones, aminoglycosides, glycopeptides, NSAIDs, opioids, …). Same column
shape as the baseline export, so it can be reviewed side by side and shaped into
an Initializer / OCL import.

Regenerate with:

```bash
python3 dev/exports/rxclass_extract.py > dev/exports/rxclass_drug_class_edges.csv
```

### Two findings worth carrying into the Talk thread

- **The repo's existing fixture maps amoxicillin to the wrong class.** The
  hand-inserted row and the integration test use NUI `N0000175503` labelled
  "Penicillins" — but per RxClass, `N0000175503` is **"Sulfonamide
  Antibacterial."** The real penicillin EPC is `N0000175497` ("Penicillin-class
  Antibacterial"). The test still passes because it only checks the graph-walk
  mechanics, not real-world correctness — but the fixture is clinically wrong and
  should be corrected to `N0000175497`.
- **IN vs PIN normalization matters.** RxClass returns some members as precise
  ingredients (PIN), e.g. amoxicillin arrives as "amoxicillin anhydrous"
  (PIN `1297882`), not the base ingredient CIEL maps to (IN `723`). The
  extractor resolves PIN→IN so edges land on the ingredient CUI used in practice.

### Open modeling decisions (for the terminology lead)

- **Which RxClass axes?** This starter uses EPC only. RxClass also exposes ATC,
  mechanism-of-action, chemical structure, and physiologic effect — more axes =
  more coverage but more noise.
- **`relaSource`** is `DAILYMED` here; `FDASPL` gives near-identical results,
  `MEDRT` returned none for these classes.
- **Allergen anchoring** — does the allergen concept map directly to the class
  NUI, or to an ingredient CUI whose class the matcher then climbs?
