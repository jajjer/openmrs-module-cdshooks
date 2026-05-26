/**
 * Drug-allergy alert extension. Rendered into
 * {@code order-item-additional-info-slot} (one instance per order line in the
 * medication basket).
 *
 * Receives {@code orderItemUuid} (= drug UUID) via extension slot state and
 * picks the current patient UUID up from the patient-chart context.
 */
import React from "react";
import { useTranslation } from "react-i18next";
import { InlineNotification } from "@carbon/react";
import { usePatient } from "@openmrs/esm-framework";
import { useDrugSnomed } from "../api/medication.resource";
import { useDrugAllergyAlerts, CdsHooksCard } from "../api/cds-hooks.resource";

interface DrugAllergyAlertProps {
  // Extension state passed by the host (`esm-patient-medications-app`).
  orderItemUuid?: string;
}

export default function DrugAllergyAlert({ orderItemUuid }: DrugAllergyAlertProps) {
  const { t } = useTranslation();
  const { patient, isLoading: patientLoading } = usePatient();
  const { info: drug, isLoading: drugLoading } = useDrugSnomed(orderItemUuid);

  const patientUuid = patient?.id ?? null;
  const snomedCode = drug?.snomedCode ?? null;
  const drugDisplay = drug?.display ?? null;

  const { cards, isLoading, error } = useDrugAllergyAlerts(patientUuid, snomedCode, drugDisplay);

  // Don't show anything while loading dependencies — the basket tile already
  // has its own loading affordance.
  if (patientLoading || drugLoading || isLoading) return null;

  // If the drug isn't mapped to SNOMED, we can't do CDS — render nothing.
  if (!snomedCode) return null;

  if (error) {
    // Be visible on error rather than silent — clinicians should know the
    // safety check isn't working.
    return (
      <InlineNotification
        kind="info"
        lowContrast
        hideCloseButton
        title={t("cdshooksError", "Allergy check unavailable")}
        subtitle={t(
          "cdshooksErrorSubtitle",
          "The drug-allergy check failed. Verify allergies in the patient chart before ordering.",
        )}
      />
    );
  }

  if (cards.length === 0) return null;

  return (
    <>
      {cards.map((card) => (
        <InlineNotification
          key={card.uuid}
          kind={mapIndicator(card.indicator)}
          lowContrast={card.indicator !== "critical"}
          hideCloseButton
          title={card.summary}
          subtitle={card.detail}
        />
      ))}
    </>
  );
}

function mapIndicator(indicator: CdsHooksCard["indicator"]) {
  switch (indicator) {
    case "critical":
      return "error" as const;
    case "warning":
      return "warning" as const;
    case "info":
    default:
      return "info" as const;
  }
}
