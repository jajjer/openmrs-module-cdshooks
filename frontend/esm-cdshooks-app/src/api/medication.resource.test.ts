import { describe, it, expect } from "vitest";
import { extractDrugSnomed } from "./medication.resource";

describe("extractDrugSnomed", () => {
  it("returns the first SNOMED coding and the text as display", () => {
    const med = {
      resourceType: "Medication" as const,
      code: {
        text: "Amoxicillin 500mg",
        coding: [
          { system: "https://cielterminology.org", code: "71160" },
          { system: "http://snomed.info/sct", code: "27658006", display: "Amoxicillin" },
        ],
      },
    };
    expect(extractDrugSnomed(med)).toEqual({
      snomedCode: "27658006",
      display: "Amoxicillin 500mg",
    });
  });

  it("falls back to SNOMED coding display when text is missing", () => {
    const med = {
      resourceType: "Medication" as const,
      code: {
        coding: [
          { system: "http://snomed.info/sct", code: "27658006", display: "Amoxicillin" },
        ],
      },
    };
    expect(extractDrugSnomed(med)).toEqual({
      snomedCode: "27658006",
      display: "Amoxicillin",
    });
  });

  it("falls back to first coding display when neither text nor SNOMED display present", () => {
    const med = {
      resourceType: "Medication" as const,
      code: {
        coding: [
          { system: "https://cielterminology.org", code: "71160", display: "Amoxicillin (CIEL)" },
        ],
      },
    };
    expect(extractDrugSnomed(med)).toEqual({
      snomedCode: null,
      display: "Amoxicillin (CIEL)",
    });
  });

  it("returns snomedCode: null when no SNOMED coding is present", () => {
    const med = {
      resourceType: "Medication" as const,
      code: {
        text: "Acetaminophen",
        coding: [{ system: "https://cielterminology.org", code: "70116" }],
      },
    };
    expect(extractDrugSnomed(med).snomedCode).toBeNull();
  });

  it("defaults display to 'Ordered drug' when nothing else available", () => {
    const med = { resourceType: "Medication" as const };
    expect(extractDrugSnomed(med).display).toBe("Ordered drug");
  });

  it("handles empty coding array", () => {
    const med = { resourceType: "Medication" as const, code: { text: "X", coding: [] } };
    expect(extractDrugSnomed(med)).toEqual({ snomedCode: null, display: "X" });
  });

  it("does not pick up a SNOMED-looking system without a code", () => {
    const med = {
      resourceType: "Medication" as const,
      code: { coding: [{ system: "http://snomed.info/sct" }] },
    };
    expect(extractDrugSnomed(med).snomedCode).toBeNull();
  });
});
