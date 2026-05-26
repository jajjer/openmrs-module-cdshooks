package org.openmrs.module.cdshooks.api.impl;

import org.openmrs.Concept;
import org.openmrs.module.cdshooks.model.AllergyMatch;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Maps an OpenMRS allergy-severity {@link Concept} to the matcher's severity
 * enum. Falls back to {@code UNKNOWN} for unrecognized concepts.
 *
 * <p>OpenMRS stores severity as a Concept reference. CIEL dictionaries vary,
 * but display names tend to be "Mild", "Moderate", "Severe". A more robust
 * implementation could match by SNOMED reference term — left as a follow-up
 * once a canonical severity-concept set is agreed with the terminology team.
 */
@Component
public class SeverityMapper {

    public AllergyMatch.Severity map(Concept severityConcept) {
        if (severityConcept == null) return AllergyMatch.Severity.UNKNOWN;
        String name = displayName(severityConcept);
        if (name == null) return AllergyMatch.Severity.UNKNOWN;
        String n = name.toLowerCase(Locale.ROOT).trim();
        if (n.contains("severe")) return AllergyMatch.Severity.SEVERE;
        if (n.contains("moderate")) return AllergyMatch.Severity.MODERATE;
        if (n.contains("mild")) return AllergyMatch.Severity.MILD;
        return AllergyMatch.Severity.UNKNOWN;
    }

    /** Maps our severity enum to the CDS-Hooks Card {@code indicator} field. */
    public static String toCardIndicator(AllergyMatch.Severity severity) {
        if (severity == null) return "info";
        switch (severity) {
            case SEVERE:   return "critical";
            case MODERATE: return "warning";
            case MILD:     return "info";
            default:       return "warning";
        }
    }

    private static String displayName(Concept c) {
        if (c.getName() != null && c.getName().getName() != null) {
            return c.getName().getName();
        }
        return c.getDisplayString();
    }
}
