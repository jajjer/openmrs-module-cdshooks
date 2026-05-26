/**
 * Client for the openmrs-module-cdshooks drug-allergy service.
 *
 * See https://cds-hooks.hl7.org/2.0/ for the request/response envelope.
 */
import { openmrsFetch } from "@openmrs/esm-framework";
import useSWR from "swr";

export interface CdsHooksCard {
  uuid: string;
  summary: string;
  detail: string;
  indicator: "info" | "warning" | "critical";
  source: { label: string; url?: string; icon?: string };
  links: Array<{ label: string; url: string; type: string }>;
}

export interface CdsHooksResponse {
  cards: CdsHooksCard[];
  systemActions: unknown[];
}

const SERVICE_URL = "/ws/cds-services/drug-allergy";
const SNOMED_SYSTEM = "http://snomed.info/sct";

/**
 * Fetch drug-allergy alerts for a given patient + drug.
 *
 * @param patientUuid OpenMRS patient UUID
 * @param drugSnomedCode SNOMED CT code for the ordered medication
 *                       (usually the CIEL concept's mapped SNOMED reference term)
 * @param drugDisplay  Human-readable display string for the drug
 */
export function useDrugAllergyAlerts(
  patientUuid: string | null | undefined,
  drugSnomedCode: string | null | undefined,
  drugDisplay: string | null | undefined,
) {
  // Only fetch when we have both pieces of context.
  const shouldFetch = Boolean(patientUuid && drugSnomedCode);

  const body = shouldFetch
    ? buildRequestBody(patientUuid!, drugSnomedCode!, drugDisplay ?? "Ordered drug")
    : null;

  const { data, error, isLoading, mutate } = useSWR<{ data: CdsHooksResponse }, Error>(
    shouldFetch ? `${SERVICE_URL}|${patientUuid}|${drugSnomedCode}` : null,
    () =>
      openmrsFetch(SERVICE_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
      }),
    {
      // Allergy/Rx alerts shouldn't fire on every keystroke. Revalidate only
      // when the (patient, drug) tuple changes.
      revalidateOnFocus: false,
      revalidateIfStale: false,
      revalidateOnReconnect: false,
    },
  );

  return {
    cards: data?.data?.cards ?? [],
    error,
    isLoading,
    refresh: mutate,
  };
}

function buildRequestBody(patientUuid: string, sctid: string, display: string) {
  return {
    hook: "medication-prescribe",
    hookInstance: cryptoRandomId(),
    context: {
      patientId: patientUuid,
      medications: {
        resourceType: "Bundle",
        entry: [
          {
            resource: {
              resourceType: "MedicationRequest",
              medicationCodeableConcept: {
                text: display,
                coding: [{ system: SNOMED_SYSTEM, code: sctid, display }],
              },
            },
          },
        ],
      },
    },
  };
}

function cryptoRandomId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `cds-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}
