import { describe, it, expect } from "vitest";
import { buildCdsHooksRequestBody } from "./cds-hooks.resource";

describe("buildCdsHooksRequestBody", () => {
  it("conforms to CDS-Hooks 2.0 medication-prescribe envelope", () => {
    const body = buildCdsHooksRequestBody("patient-uuid", "27658006", "Amoxicillin 500mg");
    expect(body.hook).toBe("medication-prescribe");
    expect(body.hookInstance).toBeTruthy();
    expect(body.context.patientId).toBe("patient-uuid");
  });

  it("packages the drug as a FHIR Bundle of MedicationRequest", () => {
    const body = buildCdsHooksRequestBody("patient-uuid", "27658006", "Amoxicillin");
    expect(body.context.medications.resourceType).toBe("Bundle");
    expect(body.context.medications.entry).toHaveLength(1);

    const med = body.context.medications.entry[0].resource;
    expect(med.resourceType).toBe("MedicationRequest");
    expect(med.medicationCodeableConcept.text).toBe("Amoxicillin");
    expect(med.medicationCodeableConcept.coding).toHaveLength(1);

    const coding = med.medicationCodeableConcept.coding[0];
    expect(coding.system).toBe("http://snomed.info/sct");
    expect(coding.code).toBe("27658006");
    expect(coding.display).toBe("Amoxicillin");
  });

  it("generates a unique hookInstance per call", () => {
    const a = buildCdsHooksRequestBody("p", "27658006", "Amoxicillin");
    const b = buildCdsHooksRequestBody("p", "27658006", "Amoxicillin");
    expect(a.hookInstance).not.toBe(b.hookInstance);
  });
});
