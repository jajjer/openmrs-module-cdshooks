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
     * ordered drug. Both sides are described by their terminology reference
     * codes (RxNORM CUIs, RxClass NUIs, SNOMED CT codes, …).
     *
     * <p><b>Primary path</b> — direct reference-code subsumption: the drug and
     * allergen codes are compared head-on. Equal codes are an ingredient match;
     * an allergen code that subsumes a drug code (e.g. an RxClass class NUI over
     * an RxNORM ingredient CUI, walked through {@code concept_reference_term_map})
     * is a class match. This is the first-class matcher.
     *
     * <p><b>Secondary path</b> — the SNOMED finding/product attribute bridge:
     * when the configured backend exposes SNOMED attribute relationships, the
     * matcher additionally expands allergen findings to their {@code Causative
     * agent} substances and drug products to their {@code Has active ingredient}
     * substances and compares those too. This augments coverage where SNOMED
     * modelling is richer than the loaded reference-map edges; it is a
     * "long-term completeness" path, not the lead.
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
