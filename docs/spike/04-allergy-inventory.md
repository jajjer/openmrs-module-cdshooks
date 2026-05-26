# OpenMRS Allergy Infrastructure Inventory

Research output. Determines what we extend vs. build parallel.

## What already exists (reuse these)

**Core data model.** `Allergy`, `Allergen` (with `codedAllergen` Concept + `nonCodedAllergen` String for free-text), `AllergyReaction`. Service methods live on `PatientService` (`getAllergies`, `saveAllergy`, etc.) with privileges `GET_ALLERGIES` / `ADD_ALLERGIES` / `EDIT_ALLERGIES` / `DELETE_ALLERGIES`. Migrated from the legacy `allergyapi` module to core circa 2015 — that module is deprecated.

**REST API.** `/ws/rest/v1/patient/{patientUuid}/allergy` (GET/POST/DELETE) exists today.

**FHIR API.** `openmrs-module-fhir2` exposes `AllergyIntolerance` R3/R4 via `AllergyIntoleranceTranslatorImpl`. Endpoint: `/ws/fhir2/R4/AllergyIntolerance?patient={uuid}`. Already consumed by the frontend `useAllergies` hook.

**Frontend.** `esm-patient-allergies-app/src/allergies/allergy-intolerance.resource.ts` — `useAllergies(patientUuid)` SWR hook. `esm-patient-medications-app/src/add-drug-order/drug-order-form.component.tsx` renders `<ExtensionSlot name="allergy-list-pills-slot" state={{patientUuid}} />` to *display* allergies in the order form, but performs no check.

## The headline finding

**`ConceptTranslatorImpl` in `openmrs-module-fhir2` already iterates `concept.getConceptMappings()` and emits each mapped `ConceptReferenceTerm` as a FHIR Coding** (prioritizing SAME-AS). The reverse direction works too.

**Implication:** when we add SNOMED CT reference terms to allergen and drug concepts (via Initializer CSVs or CIEL), they will automatically appear in the `AllergyIntolerance.code` and `Medication.code` FHIR resources. No translator work, no plumbing — the data layer is ready. Phase 2 of the design doc (reference-map data pipeline) is *just* a data exercise; no Java code needs to ship to surface SNOMED on existing FHIR resources.

What does NOT exist: **class/hierarchy traversal**. `ConceptTranslator` only emits direct codings. SNOMED `$subsumes` work is genuinely new logic — and that's the load-bearing piece of the design.

## What does NOT exist (genuinely new work)

**No drug-allergy CDS in the reference distribution.** The closest things found:

- **`openmrs-module-drools`** — generic Drools rules engine in the OpenMRS org (release `drools-1.0.0`, Feb 2026). Provides rules + patient flags + alerts framework. Could *host* CDS rules but ships no allergy logic.
- **`Bahmni/openmrs-module-cdss`** — FHIR CDS-Hooks integration (release 1.1.0, Oct 2025). Scope: drug-diagnosis interactions, drug-drug contraindications, dose computation. **Drug-allergy is not in the documented feature set.** Bahmni-flavored, not in the ref distro.
- **`openmrs-module-allergyapi`** — DEPRECATED. Code in core. Do not touch.

So the new module is genuinely net-new. No wheel-reinvention.

## Implications for the design doc

### 1. Phase 2 gets dramatically simpler

The reference-map data pipeline doesn't need a translator layer. Adding SNOMED reference terms to existing CIEL concepts (allergens, drugs) — via CIEL upstream or Initializer CSVs in this RefApp — is sufficient to surface them through the FHIR API. Phase 2 is now "populate data," not "populate data + write code."

### 2. There's a real architectural fork worth flagging to Andrew

**Three host options for the matching logic**, in increasing standards-alignment:

- **A. New standalone module** (current proposal). Smallest scope, fastest to ship. Owns its endpoint and matching logic. Easy to reason about.
- **B. Rules-engine path via `openmrs-module-drools`**. Frame the allergy check as one Drools rule among many. Pro: future CDS features (drug-drug, dose-range, age-appropriateness) compound on the same infrastructure. Con: Drools is a much bigger conceptual surface for a contributor's first feature; also tightly couples this module's release cadence to Drools.
- **C. CDS-Hooks path** (the Bahmni pattern). Implement OpenMRS as a CDS-Hooks *service host* — frontend posts `medication-prescribe` hooks; the service responds with `Card`s containing warnings. Pro: real HL7 standard, plays nicely with other CDS providers, no coupling to OpenMRS internals on the consumer side. Con: heavier to design and explain; we'd be the first ones in the ref distro to adopt it.

Worth surfacing this fork in the Talk thread. My read is **A for shipping, with a clear migration path to C as the feature matures** — but Andrew should make this call.

### 3. A second extension slot exists worth considering

Earlier exploration (file `03-esm-patient-chart-findings.md`) found `order-item-additional-info-slot` on each *basket line*. The agent also found `allergy-list-pills-slot` in the *order form* (where you're filling in details before adding to basket).

These are different UX moments:
- **Order form slot** — warn *while* the clinician is filling in the order, before it joins the basket. More upfront, more friction-y.
- **Basket-line slot** — warn after the drug is in the basket. Lower friction, but the drug is already added.

Veronica said "appear as soon as the drug is added to the basket, not only on save" — that maps to the basket-line slot. But the order-form slot is the more standards-aligned moment (CDS-Hooks `medication-prescribe` fires there). Could contribute extensions to both.

## Concrete shape of the new feature

Given everything above:

- **Backend:** new module `openmrs-module-allergyrxwarning` (name TBD). Exposes one new FHIR operation: `DetectedIssue/$evaluate?patient={uuid}&medication={uuid}`. Internally calls `PatientService.getAllergies(patient)`, extracts SNOMED codings via `ConceptTranslator`, queries a configured Snowstorm instance via `$subsumes` for each allergen×drug pair, returns `DetectedIssue` resources.
- **Frontend:** new package (e.g., `esm-allergy-rx-warning-app`). Registers extensions into `order-item-additional-info-slot` (and possibly `allergy-list-pills-slot`). Each extension calls the new endpoint with patient + drug UUIDs and renders an `<InlineNotification kind="warning">`.
- **Data:** Initializer CSV(s) in this RefApp distro mapping CIEL allergen + drug concepts to SNOMED reference terms — at minimum, enough to cover the worked example end-to-end.
- **No core changes. No `openmrs-module-fhir2` changes. No `esm-patient-medications-app` changes.**

That is a remarkably clean blast radius for a first contribution.
