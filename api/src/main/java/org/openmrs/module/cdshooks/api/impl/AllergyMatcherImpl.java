package org.openmrs.module.cdshooks.api.impl;

import org.openmrs.module.cdshooks.CdsHooksConstants;
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.client.SnowstormClient;
import org.openmrs.module.cdshooks.model.AllergyMatch;
import org.openmrs.module.cdshooks.model.SnomedConcept;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Java port of the v2 spike algorithm in {@code .context/spike/06-match-v2.py}.
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
    private SnowstormClient snowstorm;

    @Override
    public List<AllergyMatch> match(DrugInput drug, List<AllergyInput> allergies) {
        if (drug == null || allergies == null || allergies.isEmpty()) {
            return List.of();
        }

        Set<SnomedConcept> drugSubstances = expandToSubstances(
                drug.snomedSctids, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT);
        if (drugSubstances.isEmpty()) {
            return List.of();
        }

        List<AllergyMatch> matches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (AllergyInput allergy : allergies) {
            Set<SnomedConcept> allergenSubstances = expandToSubstances(
                    allergy.snomedSctids, CdsHooksConstants.SCTID_CAUSATIVE_AGENT);

            for (SnomedConcept allergenSubstance : allergenSubstances) {
                for (SnomedConcept drugSubstance : drugSubstances) {
                    AllergyMatch m = compareSubstances(allergy, drug, allergenSubstance, drugSubstance);
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

    private Set<SnomedConcept> expandToSubstances(List<String> seedSctids, String attributeSctid) {
        Set<SnomedConcept> out = new LinkedHashSet<>();
        if (seedSctids == null) return out;
        for (String sctid : seedSctids) {
            // Include the seed itself: handles direct substance mappings.
            out.add(new SnomedConcept(sctid, null));
            // Plus any values of the bridging attribute (Causative agent / Has active ingredient).
            out.addAll(snowstorm.getAttributeValues(sctid, attributeSctid));
        }
        return out;
    }

    private AllergyMatch compareSubstances(AllergyInput allergy, DrugInput drug,
                                           SnomedConcept allergenSubstance, SnomedConcept drugSubstance) {
        if (allergenSubstance.getSctid().equals(drugSubstance.getSctid())) {
            return new AllergyMatch(
                    allergy.display,
                    AllergyMatch.MatchType.INGREDIENT,
                    allergy.severity,
                    allergy.reactionDisplay,
                    explain(drug, drugSubstance, allergy, allergenSubstance, AllergyMatch.MatchType.INGREDIENT));
        }
        if (snowstorm.subsumes(allergenSubstance.getSctid(), drugSubstance.getSctid()).indicatesAncestry()) {
            return new AllergyMatch(
                    allergy.display,
                    AllergyMatch.MatchType.CLASS,
                    allergy.severity,
                    allergy.reactionDisplay,
                    explain(drug, drugSubstance, allergy, allergenSubstance, AllergyMatch.MatchType.CLASS));
        }
        return null;
    }

    private static String explain(DrugInput drug, SnomedConcept drugSubstance,
                                   AllergyInput allergy, SnomedConcept allergenSubstance,
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

    private static String displayOrSctid(SnomedConcept c) {
        return (c.getDisplay() != null && !c.getDisplay().isBlank()) ? c.getDisplay() : c.getSctid();
    }
}
