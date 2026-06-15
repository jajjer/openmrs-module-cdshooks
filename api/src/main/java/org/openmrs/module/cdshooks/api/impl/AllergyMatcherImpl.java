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
 * Cross-hierarchy SNOMED matcher: bridges the allergen *finding* hierarchy and
 * the drug *product* hierarchy via SNOMED attribute relationships, comparing
 * like-to-like in the *substance* hierarchy.
 *
 * <p>For each allergy, the matcher collects the set of "candidate substances"
 * implicated by the allergen — the allergen's own SNOMED code, plus the values
 * of its {@code Causative agent} attribute if it is a finding. For the drug,
 * it collects the substances pointed to by {@code Has active ingredient} plus
 * the drug's own SNOMED code. Then it checks every (allergen-substance,
 * drug-substance) pair for ingredient or class match.
 *
 * <p>The "include self" union handles both common mapping shapes: an OpenMRS
 * allergen mapped directly to a SNOMED substance, or one mapped to a finding
 * whose causative agent points to the substance.
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

        Set<CodedConcept> drugSubstances = expandToSubstances(
                drug.referenceCodes, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT);
        if (drugSubstances.isEmpty()) {
            return List.of();
        }

        List<AllergyMatch> matches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> excludedClassCodes = excludedClassCodes();

        for (AllergyInput allergy : allergies) {
            Set<CodedConcept> allergenSubstances = expandToSubstances(
                    allergy.referenceCodes, CdsHooksConstants.SCTID_CAUSATIVE_AGENT);

            for (CodedConcept allergenSubstance : allergenSubstances) {
                for (CodedConcept drugSubstance : drugSubstances) {
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
     * Resolve a list of seed SCTIDs to a deduplicated set of "substance
     * candidates" for matching.
     *
     * <p>Strategy: for each seed, query the bridging attribute (Causative
     * agent on allergen findings, Has active ingredient on drug products).
     * If the seed has bridging values, use those. If it has none, fall back
     * to the seed itself — this handles the case where an OpenMRS concept is
     * mapped directly to a SNOMED substance code rather than to a finding or
     * product. The fall-back is conditional rather than unconditional to
     * avoid spurious matches between root-of-hierarchy concepts (e.g., an
     * allergen finding listing "Pharmaceutical / biologic product" as one of
     * its causative agents would otherwise trivially subsume every drug
     * product).
     */
    private Set<CodedConcept> expandToSubstances(List<String> seedSctids, String attributeSctid) {
        Set<CodedConcept> out = new LinkedHashSet<>();
        if (seedSctids == null) return out;
        for (String sctid : seedSctids) {
            List<CodedConcept> bridged = terminology.getAttributeValues(sctid, attributeSctid);
            if (bridged.isEmpty()) {
                out.add(new CodedConcept(sctid, null));
            } else {
                out.addAll(bridged);
            }
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

    private static String explain(DrugInput drug, CodedConcept drugSubstance,
                                   AllergyInput allergy, CodedConcept allergenSubstance,
                                   AllergyMatch.MatchType type) {
        String ingredientDisplay = displayOrSctid(drugSubstance);
        String classDisplay = displayOrSctid(allergenSubstance);
        if (type == AllergyMatch.MatchType.INGREDIENT) {
            return drug.display + " contains " + ingredientDisplay
                    + ", the causative agent of " + allergy.display + ".";
        }
        return drug.display + " contains " + ingredientDisplay
                + ", which is a " + classDisplay
                + " — the causative-agent class for " + allergy.display + ".";
    }

    private static String displayOrSctid(CodedConcept c) {
        return (c.getDisplay() != null && !c.getDisplay().isBlank()) ? c.getDisplay() : c.getCode();
    }
}
