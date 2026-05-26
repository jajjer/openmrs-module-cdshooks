package org.openmrs.module.cdshooks.model;

/**
 * FHIR {@code CodeSystem/$subsumes} possible outcomes.
 * See https://www.hl7.org/fhir/codesystem-operation-subsumes.html.
 */
public enum SubsumptionOutcome {
    EQUIVALENT,
    SUBSUMES,
    SUBSUMED_BY,
    NOT_SUBSUMED,
    UNKNOWN;

    public static SubsumptionOutcome fromFhirCode(String code) {
        if (code == null) return UNKNOWN;
        switch (code) {
            case "equivalent":   return EQUIVALENT;
            case "subsumes":     return SUBSUMES;
            case "subsumed-by":  return SUBSUMED_BY;
            case "not-subsumed": return NOT_SUBSUMED;
            default:             return UNKNOWN;
        }
    }

    /** True when the ancestor argument actually subsumes the descendant. */
    public boolean indicatesAncestry() {
        return this == SUBSUMES || this == EQUIVALENT;
    }
}
