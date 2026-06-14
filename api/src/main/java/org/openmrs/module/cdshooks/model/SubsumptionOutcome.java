/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

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
