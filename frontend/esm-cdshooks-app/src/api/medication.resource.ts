/**
 * Resolve an OpenMRS drug UUID to its SNOMED CT coding via the FHIR2 module's
 * Medication resource. CIEL drug concepts come out with the SNOMED reference
 * term included in {@code code.coding} when the underlying concept is mapped.
 */
import { openmrsFetch, fhirBaseUrl } from "@openmrs/esm-framework";
import useSWR from "swr";

const SNOMED_SYSTEM_PREFIX = "http://snomed.info/sct";

interface FhirCoding {
  system?: string;
  code?: string;
  display?: string;
}

interface FhirMedication {
  resourceType: "Medication";
  code?: {
    text?: string;
    coding?: FhirCoding[];
  };
}

export interface DrugSnomedInfo {
  /** The first SNOMED CT code on the Medication; null if unmapped. */
  snomedCode: string | null;
  /** Best human-readable name available. */
  display: string;
}

export function useDrugSnomed(drugUuid: string | null | undefined) {
  const url = drugUuid ? `${fhirBaseUrl}/Medication/${drugUuid}` : null;
  const { data, error, isLoading } = useSWR<{ data: FhirMedication }, Error>(url, openmrsFetch, {
    revalidateOnFocus: false,
    revalidateIfStale: false,
  });

  const info: DrugSnomedInfo | null = data?.data ? extract(data.data) : null;
  return { info, error, isLoading };
}

function extract(med: FhirMedication): DrugSnomedInfo {
  const codings = med.code?.coding ?? [];
  const snomed = codings.find(
    (c) => c.system && c.system.startsWith(SNOMED_SYSTEM_PREFIX) && c.code,
  );
  const display =
    med.code?.text ?? snomed?.display ?? codings.find((c) => c.display)?.display ?? "Ordered drug";
  return { snomedCode: snomed?.code ?? null, display };
}
