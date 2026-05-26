# SNOMED data coverage in the running RefApp — findings

Goal: confirm whether CIEL drug and allergen concepts in the running ref distro carry SNOMED CT reference terms today. This is the data prerequisite for the whole feature.

## Method

Queried the running dev backend at `http://localhost/openmrs/` (admin:Admin123) via both FHIR2 and REST. Sampled drug concepts (Acetaminophen, Amoxicillin) and allergen concepts ("Allergy to penicillin").

## Findings

### 1. The plumbing works — when data is present

**Acetaminophen** (CIEL `70116`) is richly mapped:

| Source | Code | Type |
|---|---|---|
| CIEL | 70116 | SAME-AS |
| SNOMED CT | **777067000** | SAME-AS |
| RxNORM | 161 | SAME-AS |
| WHOATC | N02BE01 | SAME-AS |
| AMPATH | 453, 89 | SAME-AS, NARROWER-THAN |

These all surface automatically in the FHIR `Medication.code.coding` array via `ConceptTranslatorImpl`. **No translator work needed for these concepts.**

### 2. CIEL coverage is partial — and the worked example is unmapped

**Amoxicillin** (CIEL `71160`) — the drug we've been using in every mockup and in the spike — has **zero** reference-term mappings in this dev instance:

```json
{
  "uuid": "71160AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "display": "Amoxicillin",
  "mappings": []
}
```

This is Veronica's earlier warning materializing on a different concept: *"set members for the Penicillins concept don't actually exist on dev3 currently."* CIEL coverage of SNOMED is uneven. Some concepts are fully mapped (Acetaminophen has SNOMED, RxNORM, WHOATC); others are bare.

### 3. The allergen side maps to a SNOMED *finding*, not a substance or product

**"Allergy to penicillin"** (CIEL `149071`) maps to SNOMED CT **`91936005`**, which in the SNOMED hierarchy is a *clinical finding* concept ("Allergy to penicillin (finding)") — not a substance, not a medicinal product.

This breaks the matching algorithm as currently sketched. The design doc assumes we can `$subsumes(allergenSNOMED, drugSNOMED)`. But:

- `allergenSNOMED` = `91936005` lives in the **finding** hierarchy
- `drugSNOMED` (e.g., Amoxicillin product 27658006) lives in the **medicinal product** hierarchy
- These hierarchies are parallel; cross-hierarchy `$subsumes` returns `not-subsumed`

The SNOMED-native way to bridge them: the finding concept has an attribute relationship `Causative agent → Penicillin (substance)`. The matching algorithm must traverse this relationship to get from finding to substance, then check the substance/product hierarchy for the drug.

This is real SNOMED modeling work. Not insurmountable — `Causative agent` is a standard SNOMED attribute and Snowstorm exposes it — but it changes the algorithm shape.

## What this means for the design

### Design doc updates needed

1. **Phase 2 (data pipeline) is not "small."** Partial CIEL coverage means the data work is real. The drug concepts most likely to be ordered (antibiotics, NSAIDs, etc.) need to be audited and back-filled with SNOMED mappings. This is CIEL-team work, not OpenMRS-module work — but the feature blocks on it.

2. **The matching algorithm needs a `Causative agent` traversal step.** The current sketch (single `$subsumes` call) doesn't bridge finding→substance→product. The algorithm should be:

   - Resolve allergen concept to its SNOMED Coding (likely a finding code).
   - If finding: traverse `Causative agent` relationship to get the substance code.
   - For each substance the patient is allergic to, query Snowstorm: does the ordered drug's SNOMED code descend from any product that has this substance as an active ingredient? (`Has active ingredient` relationship.)
   - Return matches with the allergen, the matched substance, and the hierarchy path used.

   This is more complex than `$subsumes` and arguably the spike Python script no longer reflects the real algorithm.

3. **A new open question for Andrew:** which SNOMED reference frame is canonical for CIEL allergen concepts — finding (`91936005`), substance (`6369005`), or both? The matching algorithm shape depends on this answer. Same question on the drug side — substance, product, or both? CIEL appears to use product (e.g., Acetaminophen's `777067000` is a medicinal product code), so likely Has active ingredient is the cleanest path.

### Spike algorithm needs an update

`02-match.py` in this directory hardcodes a single `$subsumes` call. It validated that the FHIR mechanic works, but the real algorithm requires Causative agent + Has active ingredient relationships. A v2 of the spike should demonstrate that traversal end-to-end.

## Bottom line

The data layer **is** ready (plumbing works, CIEL mappings flow through FHIR). The data **isn't** — CIEL coverage of clinically critical drugs is incomplete, and the allergen side uses a different SNOMED hierarchy than the drug side. The feature is achievable, but with two real prerequisites that should be on Andrew's radar before any module code is written:

- Audit + backfill SNOMED mappings on common drug concepts (CIEL-team work)
- Confirm the canonical SNOMED reference frame for allergens (finding vs. substance) and drugs (substance vs. product)
