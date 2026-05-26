# Allergy / Rx Warning — Spike

Throwaway exploration to validate the design proposal in `.context/allergy-rx-warning-design.md`.

**Goal:** prove the load-bearing technical claim — *Snowstorm can answer "is amoxicillin a penicillin" via FHIR `$subsumes`* — and sketch the matching algorithm end-to-end. Not production code.

## What's here

1. `01-curl-tests.sh` — raw FHIR calls against SNOMED International's public Snowstorm. Run this first. If `$subsumes` doesn't return what we expect for the penicillin/amoxicillin case, the architecture needs rethinking before any more code.
2. `02-match.py` — the entire matching algorithm in toy form. Hardcoded patient allergy list + ordered drug, calls `$subsumes` per pair, prints matches.
3. `docker-compose.snowstorm.yml` — local Snowstorm + Elasticsearch, for later when the public endpoint isn't enough. Requires a SNOMED CT release file to load data into.

## Endpoint: tx.fhir.org (not the IHTSDO Snowstorm)

`https://snowstorm.ihtsdotools.org/fhir` redirects programmatic clients to a "denied" page — SNOMED International has gated their public Snowstorm to browser traffic only.

The spike uses `https://tx.fhir.org/r4` instead — HL7's official terminology server, with SNOMED CT International Edition loaded. Same FHIR operations, no access restriction.

The local docker compose is there for when offline / write access is needed.

## Spike result: ✅ architecture validated, algorithm reshaped

**v1 (`02-match.py`)** — single `$subsumes` call. Validated the FHIR mechanic but used product-vs-product SCTIDs that don't reflect real OpenMRS data. Kept for reference but not the real algorithm.

**v2 (`06-match-v2.py`)** — cross-hierarchy traversal via SNOMED attribute relationships:

```
Allergen finding  --Causative agent (246075003)-->  Substance(s)
                                                        ^
                                                        | $subsumes
                                                        |
Drug product  --Has active ingredient (127489000)-->  Substance(s)
```

Running v2 produces:

```
⚠ [SEVERE] CLASS MATCH
   Allergen: Allergy to penicillin  (reaction: Hepatotoxicity)
   Amoxicillin-containing product contains Amoxicillin (substance),
   which is a Penicillin — the causative-agent class for Allergy to penicillin.
```

And correctly returns no match for Acetaminophen vs. penicillin allergy.

### Why v2 is the real algorithm

`05-snomed-data-check.md` documented the data shape on the running RefApp:

- Allergen concepts in CIEL map to SNOMED **finding** codes (e.g., "Allergy to penicillin (finding)" 91936005).
- Drug concepts in CIEL map to SNOMED **medicinal product** codes (e.g., "Acetaminophen-containing product" 777067000).
- Findings, substances, and products are parallel SNOMED hierarchies; direct `$subsumes` across them returns `not-subsumed`.

The bridge is SNOMED attribute relationships:
- `246075003` (Causative agent) on findings → substance/product values
- `127489000` (Has active ingredient) on products → substance values

v2 traverses both, then does substance-vs-substance `$subsumes`.

### Performance note

v2 makes more FHIR calls per check than v1:

- 1 `$lookup` per allergen finding (gets causative agents)
- 1 `$lookup` per drug product (gets active ingredients)
- N × M `$subsumes` calls for each (causative agent, active ingredient) pair

For a patient with several allergies and a multi-ingredient drug, that adds up. The backend module should cache:
- Causative agents per allergen concept UUID (concept-level, rarely changes)
- Active ingredients per drug concept UUID (concept-level, rarely changes)
- `$subsumes` results per (ancestor, descendant) SCTID pair (SNOMED-version-scoped)

These caches are bounded and warm quickly.

## Findings worth reporting back to the design discussion

1. **SNOMED has parallel drug hierarchies** — *substance* (e.g., "Amoxicillin (substance)") and *medicinal product* (e.g., "Amoxicillin-containing product"). The class-match ancestor for "Amoxicillin-containing product" is "Product containing penicillin" (SCTID `890458001`), which lives in the product hierarchy. The substance hierarchy is parallel and uses different SCTIDs.

   **Implication for the design doc:** the OpenMRS-to-SNOMED mapping must specify *which* hierarchy each concept is mapped to, and the matching algorithm must compare like-to-like. Mixing product and substance SCTIDs in `$subsumes` returns `not-subsumed`. This belongs in the Talk thread as a question for Andrew — which hierarchy is canonical for CIEL drug / allergen concepts?

2. **The HL7 public terminology server (`tx.fhir.org`) is a usable dev dependency** but a hosted RefApp shouldn't depend on it long-term — too much shared infrastructure, no SLA. The "Snowstorm hosting" open question in the design doc is real and the answer is probably "implementer-owned with a documented bundled-deploy option."

3. **Latency** — a single `$subsumes` round-trip from a US connection averages ~200-400ms against `tx.fhir.org`. Acceptable for on-add lookup; problematic for per-keystroke. Locally-hosted Snowstorm should be much faster.
