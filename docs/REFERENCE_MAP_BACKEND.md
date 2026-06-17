# Reference-map terminology backend

The matcher's **primary, default** path resolves drug→class parent-child links
from the OpenMRS `concept_reference_term_map` table — a live Snowstorm server is
an optional secondary source. That table is intended to capture hierarchies and
other relationships between reference codes, which is exactly what a drug-class
lookup needs: the RxClass NUI ↔ RxNORM CUI edges live there, and this backend is
the service that walks them.

> New to SNOMED CT / RxNorm / RxClass / CIEL and the two OpenMRS mapping tables?
> Read [`TERMINOLOGY_PRIMER.md`](TERMINOLOGY_PRIMER.md) first — it explains the
> coding systems from scratch with diagrams.

## Why

The matcher originally led with a live Snowstorm instance over FHIR
(`$lookup` / `$subsumes`), with this reference-map path as an opt-in backend.
That priority is now inverted — the reference map is the default. Two problems
the SNOMED data check surfaced drove this:

1. **CIEL SNOMED coverage is uneven.** Amoxicillin (CIEL 71160) — the worked
   example — has *zero* reference-term mappings on dev3, so the SNOMED bridge
   can't fire for it (see [`IMPLEMENTATION_NOTES.md`](IMPLEMENTATION_NOTES.md)).
2. **RxNorm/RxClass is the more common drug-class source.** CIEL maps drugs to
   both SNOMED and RxNORM. RxClass publishes an explicit relationship between a
   drug class (NUI) and its member ingredient CUIs — exactly a parent-child
   edge that belongs in `concept_reference_term_map`.

A local reference-map lookup needs no terminology server, runs offline, is
deterministic, and keeps the edits/audits in the OpenMRS database.

## How it's wired

```
AllergyMatcherImpl
   │  (TerminologyBackend)
   ▼
TerminologyBackendRouter        ← reads GP cdshooks.terminologyBackend
   ├── referenceMap  → ConceptReferenceTermMapBackend  (local concept_reference_term_map)  [default]
   └── snowstorm     → SnowstormClient                 (live FHIR $lookup/$subsumes)        [optional]
```

`cdshooks.terminologyBackend` (global property) selects the source:

| Value | Behaviour |
|---|---|
| `referenceMap` (default) | Local `concept_reference_term_map` edges only. No terminology server. The primary path. |
| `snowstorm` | Live FHIR, including the SNOMED finding/product attribute bridge. The secondary, "long-term completeness" path. |
| `both` | Reference map first; Snowstorm confirms. **Any** positive ancestry answer wins, because a missed drug-allergy conflict is the costliest error. |

### Subsumption walk

`ConceptReferenceTermMapBackend.subsumes(ancestor, descendant)` resolves both
codes to `ConceptReferenceTerm`s and walks the *broader* closure of the
descendant:

- `NARROWER-THAN` / `IS-A` edges (A→B) are followed upward (B is broader).
- `BROADER-THAN` edges are followed in reverse (via `getReferenceTermMappingsTo`).
- `SAME-AS` is a zero-cost equivalence hop.

Reaching the ancestor → `SUBSUMES`. Reaching it *only* through SAME-AS hops →
`EQUIVALENT`. Codes absent from the table → `UNKNOWN` (so `both` mode falls
through to Snowstorm rather than assuming "safe").

`getAttributeValues` returns empty for this backend — the reference-map model
has no equivalent of SNOMED's `Causative agent` / `Has active ingredient`
attribute relationships. That is by design: the matcher always compares the drug
and allergen codes directly via `subsumes` (its primary path), so an empty
attribute bridge simply means no secondary SNOMED candidates are added. The
direct comparison is the CUI→NUI class lookup this backend is built for.

## Loading RxClass edges (Initializer)

The edges live in the **RefApp distro**, not this module, loaded via
`openmrs-module-initializer`. Conceptually:

1. **Concept sources** for `RxNORM` and `RxClass` (RxNORM typically already
   exists in CIEL).
2. **Reference terms** for each RxClass class NUI and each ingredient CUI.
3. **Reference-term maps**: ingredient CUI `NARROWER-THAN` class NUI.

Example `concept_reference_term_map` CSV (shape — exact Initializer headers TBD
against the running distro):

```csv
Source A,Code A,Map type,Source B,Code B
RxNORM,723,NARROWER-THAN,RxClass,N0000175497   # amoxicillin → Penicillins
RxNORM,2180,NARROWER-THAN,RxClass,N0000175561   # cefazolin  → Cephalosporins
```

The allergen concept ("Allergy to penicillin") must carry the matching RxClass
NUI mapping so the matcher's allergen code lands on the class node.

## Code extraction

`ReferenceCodeExtractor` (formerly `SnomedMappingExtractor`) now surfaces
SNOMED CT, RxNORM and RxClass codes from a concept's mappings, so the
reference-map path fires end-to-end. Each backend resolves the codes it
recognises and ignores the rest (Snowstorm returns "no data" for an RxNORM CUI;
the reference-map backend returns `UNKNOWN` for a code it has no edges for).

## Alert-noise filter

Class matches anchored on an overly-broad root (e.g. SNOMED "Substance") are
suppressed — such a root subsumes nearly every drug and would train clinicians
to ignore warnings. The suppressed codes are configurable via
`cdshooks.classMatchExcludedCodes` (defaults: SNOMED Substance, Pharmaceutical/
biologic product, Medicinal product, Drug or medicament).

## Still open

- **Canonical class anchor for allergens** — does the allergen concept map to
  the RxClass NUI directly, or to an ingredient CUI whose class we then climb?
- **Ownership of the RxClass→CRTM loader pipeline** and its refresh cadence
  (RxClass NUIs change over releases).
