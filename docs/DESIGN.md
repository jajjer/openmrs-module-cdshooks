# Design Proposal: Allergy / Rx Conflict Warning in O3

Hi all — following up on the Talk thread about the "1:1 Allergy/Rx should trigger warning" ticket. Veronica and Andrew shared some really helpful direction there, and the feature turned out to be more interesting than the ticket title suggests, so I've put together a design proposal to make sure I understand the shape of the work before writing code. Feedback very welcome — particularly on the open questions at the end.

## Current Implementation Status

The architecture below has been **prototyped end-to-end** against OpenMRS Platform 2.8.4. Source: see the rest of this repository — `omod/` and `api/` Maven modules, with unit tests.

**Live demo against the isolated dev stack** (`dev/docker-compose.yml`):

```bash
# Discovery
curl -u admin:Admin123 http://localhost:8081/openmrs/ws/cds-services
# 200 {"services":[{"hook":"medication-prescribe","title":"Drug-Allergy Alert",
#                   "description":"...","id":"drug-allergy"}]}

# Invocation against a real patient with a recorded penicillin allergy
curl -b cookies.txt -X POST http://localhost:8081/openmrs/ws/cds-services/drug-allergy \
  -H 'Content-Type: application/json' \
  -d '{"hook":"medication-prescribe","hookInstance":"demo",
       "context":{"patientId":"<uuid>","medications":{"entry":[
         {"resource":{"medicationCodeableConcept":{"text":"Amoxicillin",
           "coding":[{"system":"http://snomed.info/sct","code":"27658006"}]}}}]}}}'
# 200
# {
#   "cards": [{
#     "summary": "⚠ Allergy to penicillins (class match)",
#     "detail":  "Amoxicillin is a Penicillins; the patient has a recorded
#                 allergy to Penicillins.
#                 Recorded reaction: Hepatotoxicity",
#     "indicator": "critical",
#     "source":  { "label": "OpenMRS Drug-Allergy Alert" }
#   }],
#   "systemActions": []
# }
```

The matching algorithm (`AllergyMatcherImpl`) compares the drug's and allergen's terminology reference codes directly — the **primary** path resolves the class relationship from RxNORM CUI → RxClass NUI edges in `concept_reference_term_map` (amoxicillin *NARROWER-THAN* penicillins). A **secondary**, optional SNOMED CT bridge expands findings/products via the `Causative agent` (SCTID 246075003) and `Has active ingredient` (SCTID 127489000) attributes for deployments with richer SNOMED modelling. See the architecture section below.

**What's validated:**

- ✅ CDS-Hooks 2.0 discovery + invocation work at the spec-compliant `/openmrs/ws/cds-services` URL
- ✅ Direct RxNORM CUI → RxClass NUI subsumption (via `concept_reference_term_map`) correctly identifies Amoxicillin as a penicillin
- ✅ The optional SNOMED cross-hierarchy bridge reaches the same conclusion where the mappings exist
- ✅ Severity → CDS-Hooks `indicator` mapping (SEVERE → critical) works
- ✅ Module loads cleanly without modifying `openmrs-core`, `openmrs-module-fhir2`, or `esm-patient-medications-app`

**Known gaps (see [`IMPLEMENTATION_NOTES.md`](IMPLEMENTATION_NOTES.md)):**

- Basic auth alone doesn't establish a privileged user context for POST invocation; the service grants itself minimum read privileges as a workaround. The endpoint also supports HS256 bearer-token auth per the CDS-Hooks 2.0 security spec when an HMAC secret is configured.
- Matching can return noisy "matched against root-of-hierarchy" cards. Needs a filter for overly-broad class concepts.
- Frontend extension package (`esm-cdshooks-app`) renders into `order-item-additional-info-slot` on each line of the medication order basket; not yet wired into a packaged distro build.

---

## Problem

When a clinician prescribes a drug that conflicts with one of the patient's recorded allergies, the O3 RefApp does not surface a Clinical Decision Support warning. That is a patient-safety gap.

**Worked example.** A patient has a recorded severe allergy to *Penicillins* (reaction: hepatotoxicity). A clinician orders *Amoxicillin*. Amoxicillin is a penicillin. The system today shows nothing — not when the drug is added to the basket, not at order signing.

Other OpenMRS implementations (PIH, AMPATH, KenyaEMR, Bahmni) all have some form of this check. The RefApp should too.

## User Stories

- **As a clinician**, I want to be warned before adding a drug that conflicts with a recorded allergy — both for direct ingredient matches and for class matches (e.g., any penicillin when the patient is allergic to penicillins).
- **As a pharmacist**, I want the same warning visible at order verification so I can catch what the prescriber overrode or missed.
- **As a terminology maintainer**, I want allergy/drug relationships sourced from authoritative, externally maintained terminology — not hand-curated convenience sets that decay and become a liability.
- **As a system administrator**, I want the warning to be informational by default, with the option to require an override reason for severe allergies.

## Clinical & Terminology Background

The matching has to work at two levels:

- **Ingredient match.** Patient is allergic to Amoxicillin → an Amoxicillin order should warn.
- **Class match.** Patient is allergic to Penicillins → an Amoxicillin order should warn because amoxicillin *is a* penicillin.

The warning text should communicate which kind of match triggered, since the clinical reasoning differs.

The natural first instinct is to model class relationships as OpenMRS concept sets (e.g., a "Penicillins" set with member concepts). Andrew flagged in the Talk thread that this is a long-term liability: the OpenMRS terminology team becomes the single point of failure for clinical accuracy, and a stale set means false negatives on safety-critical checks. That is the kind of bug that hurts patients and creates medical-legal exposure.

The proposal here follows Andrew's direction: anchor drug and allergen concepts to an external, maintained terminology via the existing `concept_reference_term_map` table, and query the class hierarchy from that terminology rather than duplicating it in OpenMRS.

**Terminology: SNOMED CT.** SNOMED is the natural choice given Bahmni's existing precedent in the OpenMRS ecosystem and the existence of a mature open-source server (Snowstorm) that speaks FHIR. Licensing is free in many low/middle-income countries via the IHTSDO Member License — confirming the distro-wide licensing posture is one of the open questions below.

**Prior art: Bahmni.** Bahmni partnered with SNOMED International to integrate the Snowstorm Terminology Server over FHIR (https://www.bahmni.org/snomed-ct-support). Their drug-prescribing CDSS alerts (info / warning / critical) go through a third-party CDSS service via FHIR. They also use the parent-child SNOMED ontology for reporting aggregation. This is direct precedent for both the terminology choice and the FHIR-based access pattern.

## Proposed Architecture

### Data model

- The allergens come from the patient's **drug allergen list**
  (`patientService.getAllergies` → `Allergy.getAllergen().getCodedAllergen()`),
  not from findings or conditions.
- **Primary.** Drug and allergen concepts are mapped to RxNORM (ingredient CUI)
  and RxClass (class NUI) reference terms. The drug→class hierarchy is loaded as
  `NARROWER-THAN` / `BROADER-THAN` / `SAME-AS` edges in
  `concept_reference_term_map` (RxClass publishes an explicit class-NUI ↔
  ingredient-CUI relationship). The class hierarchy lives in the maintained
  external terminology, not in hand-curated OpenMRS convenience sets.
- **Secondary (optional).** Where a deployment prefers SNOMED CT, drug and
  allergen concepts may instead/also carry SNOMED reference terms and the class
  hierarchy is queried through a Snowstorm instance over FHIR.

### Matching algorithm

**Primary — direct reference-code subsumption.** Given an ordered drug `D` and a
patient's allergy list `A`, the matcher compares `D`'s reference codes against
each allergy's reference codes directly:

- **Ingredient match**: a drug code equals an allergen code.
- **Class match**: an allergen *class* code subsumes a drug code — walked over
  the `concept_reference_term_map` edges (RxNORM CUI → RxClass NUI), e.g.
  amoxicillin (CUI) *NARROWER-THAN* penicillins (NUI). No terminology server
  required.

When both fire for the same allergen, both are surfaced.

**Secondary — SNOMED finding/product bridge (optional, long-term completeness).**
A naive SNOMED sketch — resolve drug and allergen to SNOMED codes and call
`$subsumes` directly — does not work: allergen concepts map to the SNOMED
*finding* hierarchy (e.g., "Allergy to penicillin (finding)") while drug
concepts map to the *medicinal product* hierarchy. These trees are parallel, so
a cross-hierarchy `$subsumes` returns `not-subsumed`. When a Snowstorm backend
is configured, the matcher therefore bridges them via SNOMED attribute
relationships, comparing like-to-like in the *substance* hierarchy:

1. Resolve `D` to its SNOMED product code(s), then `$lookup` each to read its
   `Has active ingredient` (SCTID 127489000) values — the substance(s) `D`
   contains.
2. For each allergy `a` in `A`, resolve to its SNOMED finding code(s), then
   `$lookup` each to read its `Causative agent` (SCTID 246075003) values — the
   substance(s) the patient reacts to.
3. For each (drug substance, allergen substance) pair, `$subsumes` for
   ingredient/class matches as above.

These bridged substances are added to the same candidate comparison as the
primary codes. As Andrew put it, "most people expect the drug allergen list, not
findings… to be complete that would be a good long-term goal" — hence secondary.

All lookups are concept-level and rarely change, and subsumption results are
terminology-version-scoped — they are cached (see `cdshooks.cacheTtlSeconds`) to
keep per-order-add latency acceptable.

### Where the logic lives

A new OpenMRS module exposes the lookup as a FHIR endpoint; the frontend consumes it. A module rather than core because it ships faster, iterates without a core release cadence, and packages cleanly with the RefApp distro. If the feature stabilizes and proves widely useful, core integration is a follow-up question, not a blocker for shipping.

The module talks to a configurable Snowstorm instance. How the RefApp ships that — bundled deployment, documented public instance, or implementer-owned — is one of the open questions.

## API Shape

FHIR `DetectedIssue` — aligns with HL7 conventions for surfacing CDS findings, matches the Bahmni pattern, and gives downstream consumers (analytics, audit, interoperability) a standard resource to work with.

```http
POST /ws/fhir2/R4/DetectedIssue/$evaluate
Content-Type: application/fhir+json

{
  "resourceType": "Parameters",
  "parameter": [
    { "name": "patient",    "valueReference": { "reference": "Patient/{uuid}" } },
    { "name": "medication", "valueReference": { "reference": "Medication/{uuid}" } }
  ]
}
```

Response: a `Bundle` of `DetectedIssue` resources, each with:

- `code`: `DRG` (drug-related issue)
- `severity`: `high` | `moderate` | `low` (mapped from allergy severity)
- `patient`: reference to the patient
- `implicated`: references to the medication and the allergy
- `detail`: human-readable explanation — e.g., "Amoxicillin is a penicillin; patient has a severe penicillin allergy with hepatotoxicity reaction."
- `extension`: `matchType` (`ingredient` or `class`)

The exact operation name (`$evaluate` vs. a search-style endpoint) is worth settling with whoever maintains `openmrs-module-fhir2`.

## Frontend Integration

- **Where it renders.** Inline on the order line in the medication order basket — not a modal, not only on save. Appears as soon as the drug is added, per Veronica's guidance.
- **What it says.** Template:
  > ⚠ Patient has a **{severity}** allergy to **{allergen}** (reaction: {reaction}). {Drug} {is | contains a} {allergen-class}.
- **Behavior.** Soft warning by default; the clinician can proceed. Whether severe allergies should require a documented override reason is an open question (see below).
- **Pharmacist view.** The same alerts surface in order-verification workflows.
- **Free-text allergies.** Cannot be matched by this algorithm. Display them in a static "Also note:" panel near the warning so the clinician at least sees them. No fuzzy matching — false confidence here is worse than no check.

## Phasing

Proposed milestones so this can land incrementally rather than as one large PR:

- **Phase 0.** This design accepted; Snowstorm hosting story agreed; existing allergy-module API surface inventoried.
- **Phase 1.** New backend module: matching algorithm, FHIR `DetectedIssue` endpoint, Snowstorm integration, tests against a hand-curated SNOMED subset.
- **Phase 2.** Reference-map data pipeline: populate SNOMED mappings for drug and allergen concepts — upstream in CIEL where possible, otherwise via Initializer CSVs in the RefApp distro. Likely the longest-running phase; can run in parallel with later phases.
- **Phase 3.** Frontend warning UI in `openmrs-esm-patient-chart`, consuming the Phase 1 endpoint.
- **Phase 4.** Polish: severity-based styling, override-with-reason flow for severe allergies, audit logging of overrides, pharmacist-side display.

Each phase is its own PR (or small set of PRs).

## Existing Technical Assets

- `concept_reference_term_map` table — already in core.
- CIEL concept dictionary — current source for drug and allergen concepts.
- `openmrs-module-initializer` — supports CSV-loaded concept and reference-term data; usable to populate SNOMED mappings during distro setup.
- `openmrs-module-fhir2` — exposes OpenMRS data as FHIR R4; the new endpoint extends this surface.
- `openmrs-esm-patient-chart` — owns the medication order workspace where the UI lives.
- [Snowstorm](https://github.com/IHTSDO/snowstorm) — SNOMED International's open-source FHIR terminology server.
- [Bahmni SNOMED CT integration](https://www.bahmni.org/snomed-ct-support) — direct precedent.

## Safety & Tech Risks

- **False negatives → patient harm.** Highest-severity risk. Mitigation: anchor on maintained external terminology rather than hand-curated OpenMRS sets.
- **Stale reference data → false confidence.** Mitigation: document and automate SNOMED sync cadence; surface "data last updated" metadata in the UI for transparency.
- **Alert fatigue.** Over-broad class matching trains clinicians to ignore warnings. Mitigation: tune class-match granularity with clinical input; tier visually by severity.
- **Performance.** Per-drug-add lookups could lag on slow networks. Mitigation: lookup on add (not on type); server-side caching keyed by `(patient, drug)`.
- **Migration friction.** Implementers using convenience sets today need a path forward. Mitigation: feature flag plus a clear migration guide.
- **Licensing.** SNOMED licensing varies by country; need to confirm the distro-wide posture works for all target deployments.

## Questions for the Community

These are the calls I do not feel I should make alone. Feedback especially welcome here:

1. **Snowstorm hosting.** Does the RefApp ship with a default Snowstorm deployment, document a public instance to point at, or leave it entirely to the implementer?
2. **SNOMED licensing.** Is the IHTSDO Member License coverage sufficient for the countries where the RefApp is deployed? Is there a country where this would block adoption?
3. **Soft warning vs override-with-reason** for severe allergies — what matches existing clinical-workflow norms in OpenMRS?
4. **Ongoing ownership** of the reference-map data pipeline — CIEL team, distro maintainers, or implementer?
5. **Performance budget** — is per-drug-add lookup acceptable, or do we need batch / client-side caching of common patient-allergy combinations?
6. **FHIR operation shape** — `$evaluate` parameters resource, or something else? Best answered by the `openmrs-module-fhir2` maintainers.
7. **Backward compatibility** for implementers leaning on convenience sets today — what should the migration story look like?
8. **Canonical SNOMED reference frame.** Which hierarchy is authoritative for CIEL drug and allergen concepts — finding, substance, or product? The matching algorithm's bridging steps depend on this; CIEL currently appears to map drugs to *product* and allergens to *finding*, which is why the algorithm traverses `Has active ingredient` and `Causative agent` to meet in the *substance* hierarchy. Best confirmed with the CIEL/terminology maintainers.

Thanks for reading — and thanks to Veronica and Andrew for the framing that made this proposal possible. Looking forward to feedback before I start on Phase 1.
