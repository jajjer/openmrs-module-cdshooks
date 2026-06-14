/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.api;

import org.openmrs.module.cdshooks.model.AllergyMatch;

import java.util.List;

public interface AllergyMatcher {

    /**
     * Find ingredient and class matches between the patient's allergies and an
     * ordered drug. Both sides are described by their SNOMED CT codings; the
     * matcher bridges from finding → substance (Causative agent) and product →
     * substance (Has active ingredient) via the configured Snowstorm instance,
     * then runs substance × substance subsumption.
     *
     * @param drug      the drug being prescribed
     * @param allergies the patient's recorded allergies
     * @return zero or more matches; empty if no conflict
     */
    List<AllergyMatch> match(DrugInput drug, List<AllergyInput> allergies);

    final class DrugInput {
        public final String display;
        public final List<String> referenceCodes;

        public DrugInput(String display, List<String> referenceCodes) {
            this.display = display;
            this.referenceCodes = List.copyOf(referenceCodes);
        }
    }

    final class AllergyInput {
        public final String display;
        public final List<String> referenceCodes;
        public final AllergyMatch.Severity severity;
        public final String reactionDisplay;

        public AllergyInput(String display, List<String> referenceCodes,
                            AllergyMatch.Severity severity, String reactionDisplay) {
            this.display = display;
            this.referenceCodes = List.copyOf(referenceCodes);
            this.severity = severity;
            this.reactionDisplay = reactionDisplay;
        }
    }
}
