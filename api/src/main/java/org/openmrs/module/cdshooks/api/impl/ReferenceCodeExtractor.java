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
import org.openmrs.ConceptMap;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts terminology reference codes from an OpenMRS {@link Concept} by
 * walking its concept mappings and keeping the ones from sources the matcher
 * understands.
 *
 * <p>Originally SNOMED-only. Per Andrew Kanter's Talk feedback, the matcher now
 * also resolves drug→class links from RxClass/RxNORM edges loaded into
 * {@code concept_reference_term_map}, so this extractor surfaces SNOMED CT,
 * RxNORM and RxClass codes alike. The codes are returned together; each
 * configured {@link org.openmrs.module.cdshooks.terminology.TerminologyBackend}
 * resolves the ones it recognises and ignores the rest (a SNOMED server simply
 * returns "no data" for an RxNORM CUI, and the reference-map backend returns
 * {@code UNKNOWN} for a code it has no edges for).
 *
 * <p>Sources are matched by name token or HL7 code, case-insensitively, so the
 * common naming variants in CIEL and custom dictionaries ("SNOMED CT",
 * "SNOMED", "RxNORM", "RxCUI", "RxClass", "NUI") are all recognised.
 */
@Component
public class ReferenceCodeExtractor {

    /** Name fragments / HL7 codes identifying a source the matcher can use. */
    private static final Set<String> RECOGNISED_TOKENS = new LinkedHashSet<>(List.of(
            "SNOMED", "SCT",          // SNOMED CT
            "RXNORM", "RXCUI",        // RxNORM
            "RXCLASS", "NUI"));       // RxClass

    public List<String> extract(Concept concept) {
        List<String> codes = new ArrayList<>();
        if (concept == null) return codes;
        Set<String> seen = new LinkedHashSet<>();
        for (ConceptMap mapping : concept.getConceptMappings()) {
            ConceptReferenceTerm term = mapping.getConceptReferenceTerm();
            if (term == null || term.getCode() == null) continue;
            if (isRecognisedSource(term.getConceptSource())) {
                String code = term.getCode().trim();
                if (!code.isEmpty() && seen.add(code)) {
                    codes.add(code);
                }
            }
        }
        return codes;
    }

    private static boolean isRecognisedSource(ConceptSource source) {
        if (source == null) return false;
        String name = upper(source.getName());
        String hl7 = upper(source.getHl7Code());
        for (String token : RECOGNISED_TOKENS) {
            if (hl7.equals(token) || name.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
