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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openmrs.module.cdshooks.CdsHooksConstants;
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.client.SnowstormClient;
import org.openmrs.module.cdshooks.model.AllergyMatch;
import org.openmrs.module.cdshooks.model.CodedConcept;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Edge-case coverage for the matcher: multi-allergy, multi-ingredient,
 * equivalent-outcome, direct substance mapping, and dedup.
 */
@RunWith(MockitoJUnitRunner.class)
public class AllergyMatcherEdgeCasesTest {

    private static final String FINDING_PENICILLIN_ALLERGY = "91936005";
    private static final String FINDING_LATEX_ALLERGY = "300916003";
    private static final String SUBSTANCE_PENICILLIN = "764146007";
    private static final String SUBSTANCE_LATEX = "111088007";
    private static final String PRODUCT_AMOXICILLIN = "27658006";
    private static final String PRODUCT_COMBO = "fake-combo-product"; // Amoxicillin + Clavulanate
    private static final String SUBSTANCE_AMOXICILLIN = "372687004";
    private static final String SUBSTANCE_CLAVULANATE = "fake-clavulanate";

    @Mock private SnowstormClient snowstorm;
    @InjectMocks private AllergyMatcherImpl matcher;

    @Before
    public void defaultStubs() {
        // Default: nothing subsumes anything unless explicitly stubbed.
        when(snowstorm.subsumes(any(), any())).thenReturn(SubsumptionOutcome.NOT_SUBSUMED);
    }

    @Test
    public void multipleAllergies_onlyMatchingOneProducesCard() {
        when(snowstorm.getAttributeValues(FINDING_PENICILLIN_ALLERGY, CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_PENICILLIN, "Penicillin")));
        when(snowstorm.getAttributeValues(FINDING_LATEX_ALLERGY, CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_LATEX, "Latex")));
        when(snowstorm.getAttributeValues(PRODUCT_AMOXICILLIN, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_AMOXICILLIN, "Amoxicillin")));
        when(snowstorm.subsumes(eq(SUBSTANCE_PENICILLIN), eq(SUBSTANCE_AMOXICILLIN)))
            .thenReturn(SubsumptionOutcome.SUBSUMES);
        // Latex does NOT subsume Amoxicillin; default stub returns NOT_SUBSUMED.

        AllergyMatcher.DrugInput drug = new AllergyMatcher.DrugInput("Amoxicillin", List.of(PRODUCT_AMOXICILLIN));
        AllergyMatcher.AllergyInput penicillin = allergy("Allergy to penicillin", FINDING_PENICILLIN_ALLERGY, AllergyMatch.Severity.SEVERE);
        AllergyMatcher.AllergyInput latex = allergy("Allergy to latex", FINDING_LATEX_ALLERGY, AllergyMatch.Severity.MODERATE);

        List<AllergyMatch> matches = matcher.match(drug, List.of(penicillin, latex));

        assertThat(matches, hasSize(1));
        assertThat(matches.get(0).getAllergenDisplay(), is("Allergy to penicillin"));
        assertThat(matches.get(0).getMatchType(), is(AllergyMatch.MatchType.CLASS));
    }

    @Test
    public void multiIngredientDrug_matchesOnAnyIngredient() {
        when(snowstorm.getAttributeValues(FINDING_PENICILLIN_ALLERGY, CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_PENICILLIN, "Penicillin")));
        when(snowstorm.getAttributeValues(PRODUCT_COMBO, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(
                new CodedConcept(SUBSTANCE_AMOXICILLIN, "Amoxicillin"),
                new CodedConcept(SUBSTANCE_CLAVULANATE, "Clavulanate")));
        when(snowstorm.subsumes(eq(SUBSTANCE_PENICILLIN), eq(SUBSTANCE_AMOXICILLIN)))
            .thenReturn(SubsumptionOutcome.SUBSUMES);
        // Penicillin does NOT subsume Clavulanate.

        AllergyMatcher.DrugInput combo = new AllergyMatcher.DrugInput("Amoxicillin + Clavulanate", List.of(PRODUCT_COMBO));
        AllergyMatcher.AllergyInput penicillin = allergy("Allergy to penicillin", FINDING_PENICILLIN_ALLERGY, AllergyMatch.Severity.SEVERE);

        List<AllergyMatch> matches = matcher.match(combo, List.of(penicillin));

        assertThat(matches, hasSize(1));
        assertThat(matches.get(0).getExplanation(), is(org.hamcrest.Matchers.containsString("Amoxicillin")));
    }

    @Test
    public void equivalentOutcome_alsoCountsAsMatch() {
        // FHIR $subsumes can return 'equivalent' (the two codes are the same
        // hierarchy node). Per SubsumptionOutcome.indicatesAncestry that
        // counts as a match.
        when(snowstorm.getAttributeValues(FINDING_PENICILLIN_ALLERGY, CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_PENICILLIN, "Penicillin")));
        when(snowstorm.getAttributeValues(PRODUCT_AMOXICILLIN, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_AMOXICILLIN, "Amoxicillin")));
        when(snowstorm.subsumes(eq(SUBSTANCE_PENICILLIN), eq(SUBSTANCE_AMOXICILLIN)))
            .thenReturn(SubsumptionOutcome.EQUIVALENT);

        AllergyMatcher.DrugInput drug = new AllergyMatcher.DrugInput("Amoxicillin", List.of(PRODUCT_AMOXICILLIN));
        AllergyMatcher.AllergyInput penicillin = allergy("Allergy to penicillin", FINDING_PENICILLIN_ALLERGY, AllergyMatch.Severity.SEVERE);

        List<AllergyMatch> matches = matcher.match(drug, List.of(penicillin));

        assertThat(matches, hasSize(1));
        assertThat(matches.get(0).getMatchType(), is(AllergyMatch.MatchType.CLASS));
    }

    @Test
    public void directSubstanceAllergen_usesSelfAsCandidate() {
        // Allergen concept mapped DIRECTLY to a substance SCTID, with no
        // Causative agent attribute on that substance. The matcher should
        // fall back to the seed SCTID as the candidate.
        String allergenIsSubstance = SUBSTANCE_PENICILLIN;
        when(snowstorm.getAttributeValues(allergenIsSubstance, CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(Collections.emptyList()); // no bridging attribute
        when(snowstorm.getAttributeValues(PRODUCT_AMOXICILLIN, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_AMOXICILLIN, "Amoxicillin")));
        when(snowstorm.subsumes(eq(SUBSTANCE_PENICILLIN), eq(SUBSTANCE_AMOXICILLIN)))
            .thenReturn(SubsumptionOutcome.SUBSUMES);

        AllergyMatcher.DrugInput drug = new AllergyMatcher.DrugInput("Amoxicillin", List.of(PRODUCT_AMOXICILLIN));
        AllergyMatcher.AllergyInput allergy = allergy("Penicillin", allergenIsSubstance, AllergyMatch.Severity.SEVERE);

        List<AllergyMatch> matches = matcher.match(drug, List.of(allergy));

        assertThat(matches, hasSize(1));
    }

    @Test
    public void directSubstanceDrug_usesSelfAsCandidate() {
        // Drug concept mapped DIRECTLY to a substance SCTID — same fall-back.
        String drugIsSubstance = SUBSTANCE_AMOXICILLIN;
        when(snowstorm.getAttributeValues(FINDING_PENICILLIN_ALLERGY, CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_PENICILLIN, "Penicillin")));
        when(snowstorm.getAttributeValues(drugIsSubstance, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(Collections.emptyList());
        when(snowstorm.subsumes(eq(SUBSTANCE_PENICILLIN), eq(SUBSTANCE_AMOXICILLIN)))
            .thenReturn(SubsumptionOutcome.SUBSUMES);

        AllergyMatcher.DrugInput drug = new AllergyMatcher.DrugInput("Amoxicillin", List.of(drugIsSubstance));
        AllergyMatcher.AllergyInput allergy = allergy("Allergy to penicillin", FINDING_PENICILLIN_ALLERGY, AllergyMatch.Severity.SEVERE);

        List<AllergyMatch> matches = matcher.match(drug, List.of(allergy));

        assertThat(matches, hasSize(1));
    }

    @Test
    public void ingredientMatch_takesPrecedenceOverClass() {
        // When the drug substance equals the allergen substance, that's an
        // INGREDIENT match (not CLASS). The matcher checks ingredient first.
        when(snowstorm.getAttributeValues(FINDING_PENICILLIN_ALLERGY, CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_AMOXICILLIN, "Amoxicillin")));
        when(snowstorm.getAttributeValues(PRODUCT_AMOXICILLIN, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_AMOXICILLIN, "Amoxicillin")));

        AllergyMatcher.DrugInput drug = new AllergyMatcher.DrugInput("Amoxicillin", List.of(PRODUCT_AMOXICILLIN));
        AllergyMatcher.AllergyInput allergy = allergy("Allergy to amoxicillin", FINDING_PENICILLIN_ALLERGY, AllergyMatch.Severity.SEVERE);

        List<AllergyMatch> matches = matcher.match(drug, List.of(allergy));

        assertThat(matches, hasSize(1));
        assertThat(matches.get(0).getMatchType(), is(AllergyMatch.MatchType.INGREDIENT));
    }

    @Test
    public void deduplicates_sameMatchViaDifferentPaths() {
        // If the allergen has TWO causative-agent values that BOTH subsume the
        // drug substance, we only want one card per (allergen, drugSubstance).
        when(snowstorm.getAttributeValues(FINDING_PENICILLIN_ALLERGY, CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(List.of(
                new CodedConcept(SUBSTANCE_PENICILLIN, "Penicillin"),
                new CodedConcept("6369005", "Penicillin antibacterial agent")));
        when(snowstorm.getAttributeValues(PRODUCT_AMOXICILLIN, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_AMOXICILLIN, "Amoxicillin")));
        when(snowstorm.subsumes(eq(SUBSTANCE_PENICILLIN), eq(SUBSTANCE_AMOXICILLIN)))
            .thenReturn(SubsumptionOutcome.SUBSUMES);
        when(snowstorm.subsumes(eq("6369005"), eq(SUBSTANCE_AMOXICILLIN)))
            .thenReturn(SubsumptionOutcome.SUBSUMES);

        AllergyMatcher.DrugInput drug = new AllergyMatcher.DrugInput("Amoxicillin", List.of(PRODUCT_AMOXICILLIN));
        AllergyMatcher.AllergyInput allergy = allergy("Allergy to penicillin", FINDING_PENICILLIN_ALLERGY, AllergyMatch.Severity.SEVERE);

        List<AllergyMatch> matches = matcher.match(drug, List.of(allergy));

        // Two distinct class explanations are kept (different ancestor displays),
        // but we should not get four matches just because the explanation
        // dedup key folds them by (allergen, matchType, explanation).
        assertThat(matches.size(), is(2));
    }

    @Test
    public void drugWithNoSctids_returnsEmpty() {
        AllergyMatcher.DrugInput drug = new AllergyMatcher.DrugInput("Unknown", List.of());
        AllergyMatcher.AllergyInput allergy = allergy("Allergy to penicillin", FINDING_PENICILLIN_ALLERGY, AllergyMatch.Severity.SEVERE);
        assertThat(matcher.match(drug, List.of(allergy)), is(empty()));
    }

    @Test
    public void allergyWithNoSctids_neverMatches() {
        // The matcher pulls the allergen's bridging substances from snowstorm.
        // An empty allergy.snomedSctids means no substance candidates → no match.
        AllergyMatcher.DrugInput drug = new AllergyMatcher.DrugInput("Amoxicillin", List.of(PRODUCT_AMOXICILLIN));
        AllergyMatcher.AllergyInput allergy = new AllergyMatcher.AllergyInput(
            "Free-text allergy", List.of(), AllergyMatch.Severity.SEVERE, null);
        when(snowstorm.getAttributeValues(PRODUCT_AMOXICILLIN, CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_AMOXICILLIN, "Amoxicillin")));
        assertThat(matcher.match(drug, List.of(allergy)), is(empty()));
    }

    @Test
    public void nullInputs_returnEmpty() {
        AllergyMatcher.DrugInput drug = new AllergyMatcher.DrugInput("Amoxicillin", List.of(PRODUCT_AMOXICILLIN));
        AllergyMatcher.AllergyInput allergy = allergy("Allergy", FINDING_PENICILLIN_ALLERGY, AllergyMatch.Severity.SEVERE);
        assertThat(matcher.match(null, List.of(allergy)), is(empty()));
        assertThat(matcher.match(drug, null), is(empty()));
    }

    /* helpers */
    private AllergyMatcher.AllergyInput allergy(String display, String sctid, AllergyMatch.Severity severity) {
        return new AllergyMatcher.AllergyInput(display, List.of(sctid), severity, null);
    }
}
