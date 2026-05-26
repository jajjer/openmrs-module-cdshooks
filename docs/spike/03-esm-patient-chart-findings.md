# esm-patient-chart exploration findings

Mapping where the allergy/Rx warning would land in the frontend. Repo cloned to `.context/clones/openmrs-esm-patient-chart`.

## Headline finding: the extension slot already exists

`packages/esm-patient-medications-app/src/drug-order-basket-panel/order-basket-item-tile.component.tsx:112-116`:

```tsx
<ExtensionSlot
  name="order-item-additional-info-slot"
  state={additionalInfoSlotState}
  className={styles.additionalInfoContainer}
/>
```

`additionalInfoSlotState` is `{ orderItemUuid: orderBasketItem.drug.uuid }` (line 27-32).

**Implication:** the new feature does *not* fork or modify `esm-patient-medications-app`. It ships as a separate module that contributes an extension into this existing slot. This is the canonical OpenMRS extensibility pattern and is exactly the surface we want.

The extension receives the drug UUID as state. We'd combine that with the patient UUID (available via context in the patient chart) and call the new FHIR `DetectedIssue` endpoint from the backend module.

## Allergy fetch pattern

`packages/esm-patient-allergies-app/src/allergies/allergy-intolerance.resource.ts:18-33` exports `useAllergies(patientUuid)`:

```ts
const allergiesUrl = `${fhirBaseUrl}/AllergyIntolerance?patient=${patientUuid}`;
const { data, error, ... } = useSWR(allergiesUrl, openmrsFetch);
```

Confirms two things:

1. **Allergies are already exposed via FHIR `AllergyIntolerance`** in the OpenMRS backend — the new endpoint we add (`DetectedIssue`) joins an existing FHIR surface, doesn't introduce one.
2. **SWR is the standard caching layer.** The frontend extension will use the same pattern.

`useAllergies` is **not** currently exported from `esm-patient-common-lib` — it's internal to `esm-patient-allergies-app`. That means the new extension shouldn't import it directly. Two clean paths:

- **Preferred: the backend handles it.** The frontend extension sends `{patientUuid, drugUuid}` to `DetectedIssue/$evaluate`; the backend fetches the patient's allergies internally and runs the matching. Frontend stays simple, no duplicate allergy-fetching logic.
- Alternative: lift `useAllergies` to `esm-patient-common-lib` in a small upstream PR. Useful long-term but unnecessary for this feature.

## Monorepo packages relevant to this work

```
esm-patient-allergies-app    — owns the allergy list + AllergyIntolerance fetch
esm-patient-medications-app  — owns the order basket and add-drug-order workspace
esm-patient-orders-app       — broader orders surface (not the medication-specific basket)
esm-patient-common-lib       — shared types (DrugOrderBasketItem lives here)
```

The new module's frontend code lives in **its own new package** (e.g., `esm-allergy-rx-warning-app` or similar). It does not need to touch any of the above.

## What the new frontend extension does

In ~30 lines of React:

1. Read `orderItemUuid` (= drug UUID) from extension slot state.
2. Read `patientUuid` from patient context (`usePatient()` from `@openmrs/esm-framework`).
3. Call the new backend endpoint with both UUIDs.
4. Render `<InlineNotification kind="warning">` (Carbon component, already used elsewhere in the chart) with the warning template from the design doc.
5. Render nothing when there are no matches.

## Implication for the design doc

The proposed architecture survives contact with the real frontend codebase. One small clarification to add: **the frontend is a separate npm package, not a modification of `esm-patient-medications-app`.** It plugs in via the existing `order-item-additional-info-slot` extension slot. This was implicit in the doc but worth making explicit so reviewers understand the blast radius is small.
