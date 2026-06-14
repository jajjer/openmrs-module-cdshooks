/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

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
 *
 * <p>Matching is on exact case-insensitive equality with the recognized
 * severity labels. Substring matching would incorrectly classify clinical
 * findings like "Severe sepsis" or "Severe malaria" as SEVERE.
 */
@Component
public class SeverityMapper {

    public AllergyMatch.Severity map(Concept severityConcept) {
        if (severityConcept == null) return AllergyMatch.Severity.UNKNOWN;
        String name = displayName(severityConcept);
        if (name == null) return AllergyMatch.Severity.UNKNOWN;
        String n = name.toLowerCase(Locale.ROOT).trim();
        switch (n) {
            case "severe":   return AllergyMatch.Severity.SEVERE;
            case "moderate": return AllergyMatch.Severity.MODERATE;
            case "mild":     return AllergyMatch.Severity.MILD;
            default:         return AllergyMatch.Severity.UNKNOWN;
        }
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
        if (c.getNames() == null) return null;
        String fallback = null;
        for (org.openmrs.ConceptName cn : c.getNames()) {
            if (cn == null || cn.getName() == null) continue;
            if (cn.getLocale() != null && "en".equals(cn.getLocale().getLanguage())) {
                return cn.getName();
            }
            if (fallback == null) fallback = cn.getName();
        }
        return fallback;
    }
}
