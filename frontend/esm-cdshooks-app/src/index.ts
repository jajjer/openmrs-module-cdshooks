/**
 * OpenMRS ESM entry point for the cdshooks-app microfrontend.
 *
 * Registers a single extension that contributes drug-allergy alert Cards
 * into {@code order-item-additional-info-slot} — the slot that
 * {@code esm-patient-medications-app} exposes on each line of the medication
 * order basket (see
 * {@code packages/esm-patient-medications-app/src/drug-order-basket-panel/order-basket-item-tile.component.tsx}).
 *
 * The extension calls the backend {@code openmrs-module-cdshooks} service at
 * {@code /ws/cds-services/drug-allergy} and renders a Carbon
 * {@code InlineNotification} for each Card returned.
 */
import { getAsyncLifecycle, defineConfigSchema } from "@openmrs/esm-framework";

const moduleName = "@openmrs/esm-cdshooks-app";

const options = {
  featureName: "cdshooks",
  moduleName,
};

export const importTranslation = require.context(
  "../translations",
  false,
  /.json$/,
  "lazy"
);

export const drugAllergyAlert = getAsyncLifecycle(
  () => import("./components/drug-allergy-alert.extension"),
  options
);

export function startupApp() {
  defineConfigSchema(moduleName, {});
}
