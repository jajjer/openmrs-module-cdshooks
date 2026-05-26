# @openmrs/esm-cdshooks-app

OpenMRS 3 SPA microfrontend that surfaces drug-allergy alerts from the
backend `openmrs-module-cdshooks` service.

## What it does

Registers an extension into `order-item-additional-info-slot` — the slot
that `esm-patient-medications-app` exposes on every line in the medication
order basket. When a drug is added to the basket:

1. The extension picks up the drug UUID from the slot state and the patient
   UUID from the patient-chart context.
2. Resolves the drug to its SNOMED CT code by fetching the FHIR
   `Medication/{uuid}` resource and reading `code.coding[]`.
3. POSTs a CDS-Hooks 2.0 `medication-prescribe` request to
   `/openmrs/ws/cds-services/drug-allergy`.
4. Renders each returned Card as a Carbon `<InlineNotification>` tiered by
   severity (critical → error notification, warning → warning, info → info).
5. Surfaces a visible "allergy check unavailable" notification on error
   rather than failing silently — patient safety beats UI cleanliness.

## Status

**Builds cleanly.** Source compiles and produces a real SPA-loadable bundle.
Not yet wired into a running RefApp — that's the deploy step.

```bash
cd frontend/esm-cdshooks-app
npm install --legacy-peer-deps   # peer-dep mismatch on react 19; legacy resolution is fine
npm run build                    # produces dist/openmrs-esm-cdshooks-app.js (~4 KiB)
```

Then add the built `dist/openmrs-esm-cdshooks-app.js` to your SPA's import
map. See https://o3-docs.openmrs.org/ for the SPA assembly workflow.

> **Note on tsc errors against `node_modules/@openmrs/*`:** running
> `tsc --noEmit` traces the framework's `.ts` source files and produces
> noisy errors (missing `rxjs` types, etc.). These come from the framework's
> dev configuration and don't affect this package — the production build via
> `webpack`/`rspack` correctly externalizes everything OpenMRS-related, so
> the framework sources are never compiled here.

## File map

```
src/
├── index.ts                                # ESM entry: exports setup + extension lifecycle
├── routes.json                             # extension-to-slot binding declaration
├── components/
│   └── drug-allergy-alert.extension.tsx    # the React component
└── api/
    ├── cds-hooks.resource.ts               # POST /ws/cds-services/drug-allergy + SWR caching
    └── medication.resource.ts              # GET /ws/fhir2/R4/Medication/{uuid} -> SNOMED code
translations/
└── en.json
```

## Behavior in edge cases

- **Drug has no SNOMED mapping** — extension renders nothing. The backend
  can't match without SNOMED on both sides; no warning is better than a
  false-negative warning that trains clinicians to ignore the slot. Document
  in implementer onboarding so they know to populate SNOMED mappings on
  commonly-ordered drugs.
- **Patient context missing** — extension renders nothing. The slot fires
  outside the order basket too in theory; this guards against that.
- **Backend error** — extension renders an `info`-style `InlineNotification`
  saying the check is unavailable, so clinicians know not to trust the
  absence of a warning.

## Open work

- Build + ship a real `dist/` artifact and add to the RefApp's
  `spa-assemble-config.json`.
- Add unit tests with React Testing Library mocking `useDrugSnomed` and
  `useDrugAllergyAlerts`.
- E2E test: load patient chart, add Amoxicillin to basket for an allergic
  patient, assert the critical-severity notification appears.
- Severity-override workflow: clinicians who proceed past a critical
  warning should record a reason. Currently the extension is read-only.
