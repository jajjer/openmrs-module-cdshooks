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

import org.openmrs.api.context.Context;
import org.openmrs.module.cdshooks.CdsHooksConstants;
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.model.AllergyMatch;
import org.openmrs.module.cdshooks.model.CodedConcept;
import org.openmrs.module.cdshooks.terminology.TerminologyBackend;
import org.openmrs.module.cdshooks.terminology.TerminologyBackendRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Drug-allergy matcher built around <b>direct reference-code subsumption</b> as
 * the primary path, with the SNOMED finding/product attribute bridge layered on
 * as a secondary augmentation.
 *
 * <p><b>Primary — direct code comparison.</b> For each (allergen code, drug
 * code) pair the matcher asks the terminology backend directly: equal codes are
 * an ingredient match; an allergen code that subsumes the drug code is a class
 * match. This is exactly the RxNORM CUI → RxClass NUI class lookup the
 * {@code referenceMap} backend (the default) is built for — amoxicillin (CUI)
 * {@code NARROWER-THAN} penicillins (NUI), walked through
 * {@code concept_reference_term_map}.
 *
 * <p><b>Secondary — SNOMED attribute bridge.</b> When the backend exposes SNOMED
 * attribute relationships (i.e. Snowstorm), the candidate set for each side is
 * additionally expanded: an allergen finding contributes its {@code Causative
 * agent} substances and a drug product contributes its {@code Has active
 * ingredient} substances ({@link #expandCandidates}). The backend used by
 * default ({@code referenceMap}) returns no attribute values, so this expansion
 * is a no-op there; it adds SNOMED-modelled coverage where the loaded
 * reference-map edges are thin. Both the self code (primary) and any bridged
 * substances (secondary) are compared, so an allergen that matches on both the
 * ingredient and the class surfaces both.
 */
@Service("cdshooks.AllergyMatcher")
public class AllergyMatcherImpl implements AllergyMatcher {

    @Autowired
    @Qualifier(TerminologyBackendRouter.BEAN_NAME)
    private TerminologyBackend terminology;

    @Override
    public List<AllergyMatch> match(DrugInput drug, List<AllergyInput> allergies) {
        if (drug == null || allergies == null || allergies.isEmpty()) {
            return List.of();
        }

        Set<CodedConcept> drugCandidates = expandCandidates(
                drug.referenceCodes, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT);
        if (drugCandidates.isEmpty()) {
            return List.of();
        }

        List<AllergyMatch> matches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> excludedClassCodes = excludedClassCodes();

        for (AllergyInput allergy : allergies) {
            Set<CodedConcept> allergenCandidates = expandCandidates(
                    allergy.referenceCodes, CdsHooksConstants.SCTID_CAUSATIVE_AGENT);

            for (CodedConcept allergenSubstance : allergenCandidates) {
                for (CodedConcept drugSubstance : drugCandidates) {
                    AllergyMatch m = compareSubstances(
                            allergy, drug, allergenSubstance, drugSubstance, excludedClassCodes);
                    if (m == null) continue;
                    String dedupKey = allergy.display + "|" + m.getMatchType() + "|" + m.getExplanation();
                    if (seen.add(dedupKey)) {
                        matches.add(m);
                    }
                }
            }
        }
        return matches;
    }

    /**
     * Build the set of candidate codes to compare for one side (drug or
     * allergen).
     *
     * <p><b>Primary:</b> every seed reference code is included as-is, so the
     * default {@code referenceMap} path compares the drug and allergen codes
     * directly (the RxNORM CUI → RxClass NUI class lookup).
     *
     * <p><b>Secondary:</b> each seed is also expanded through the SNOMED
     * bridging attribute — {@code Causative agent} on allergen findings,
     * {@code Has active ingredient} on drug products — and any resulting
     * substances are added to the candidate set. Backends without attribute
     * relationships (the default {@code referenceMap}) return nothing here, so
     * this contributes only when Snowstorm is in play. Over-broad bridged
     * substances are not a problem for ingredient matching (codes must be
     * equal) and are screened out of class matching by
     * {@link #excludedClassCodes()}.
     */
    private Set<CodedConcept> expandCandidates(List<String> seedCodes, String attributeSctid) {
        Set<CodedConcept> out = new LinkedHashSet<>();
        if (seedCodes == null) return out;
        for (String code : seedCodes) {
            // Primary: the code itself.
            out.add(new CodedConcept(code, null));
            // Secondary: SNOMED attribute bridge (no-op for referenceMap).
            out.addAll(terminology.getAttributeValues(code, attributeSctid));
        }
        return out;
    }

    private AllergyMatch compareSubstances(AllergyInput allergy, DrugInput drug,
                                           CodedConcept allergenSubstance, CodedConcept drugSubstance,
                                           Set<String> excludedClassCodes) {
        if (allergenSubstance.getCode().equals(drugSubstance.getCode())) {
            return new AllergyMatch(
                    allergy.display,
                    AllergyMatch.MatchType.INGREDIENT,
                    allergy.severity,
                    allergy.reactionDisplay,
                    explain(drug, drugSubstance, allergy, allergenSubstance, AllergyMatch.MatchType.INGREDIENT));
        }
        // A class match anchored on an overly-broad root (e.g. "Substance") is
        // noise — it would subsume nearly every drug. Suppress it.
        if (excludedClassCodes.contains(allergenSubstance.getCode())) {
            return null;
        }
        if (terminology.subsumes(allergenSubstance.getCode(), drugSubstance.getCode()).indicatesAncestry()) {
            return new AllergyMatch(
                    allergy.display,
                    AllergyMatch.MatchType.CLASS,
                    allergy.severity,
                    allergy.reactionDisplay,
                    explain(drug, drugSubstance, allergy, allergenSubstance, AllergyMatch.MatchType.CLASS));
        }
        return null;
    }

    /**
     * Reads the configured set of class-match ancestor codes to suppress,
     * falling back to {@link CdsHooksConstants#DEFAULT_EXCLUDED_CLASS_CODES}.
     * Read defensively (no user/service context in unit tests).
     */
    private Set<String> excludedClassCodes() {
        String configured = null;
        try {
            configured = Context.getAdministrationService()
                    .getGlobalProperty(CdsHooksConstants.GP_CLASS_MATCH_EXCLUDED_CODES);
        } catch (Exception | LinkageError ignored) {
            // Best-effort read. No service context (unit tests) or an
            // unexpected infra error must never break the safety check —
            // fall back to the built-in defaults.
        }
        if (configured == null || configured.isBlank()) {
            return CdsHooksConstants.DEFAULT_EXCLUDED_CLASS_CODES;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String part : configured.split(",")) {
            String code = part.trim();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes.isEmpty() ? CdsHooksConstants.DEFAULT_EXCLUDED_CLASS_CODES : codes;
    }

    /**
     * Terminology-neutral explanation. Avoids SNOMED-model vocabulary
     * ("causative agent") so the text reads correctly on the primary RxClass
     * path as well as the secondary SNOMED bridge.
     */
    private static String explain(DrugInput drug, CodedConcept drugSubstance,
                                   AllergyInput allergy, CodedConcept allergenSubstance,
                                   AllergyMatch.MatchType type) {
        String ingredientDisplay = displayOrCode(drugSubstance);
        String classDisplay = displayOrCode(allergenSubstance);
        if (type == AllergyMatch.MatchType.INGREDIENT) {
            return drug.display + " contains " + ingredientDisplay
                    + ", which the patient is allergic to.";
        }
        return drug.display + " is a " + classDisplay
                + "; the patient has a recorded allergy to " + classDisplay + ".";
    }

    private static String displayOrCode(CodedConcept c) {
        return (c.getDisplay() != null && !c.getDisplay().isBlank()) ? c.getDisplay() : c.getCode();
    }
}
