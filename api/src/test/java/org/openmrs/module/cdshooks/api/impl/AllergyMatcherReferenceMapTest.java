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
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.model.AllergyMatch;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;
import org.openmrs.module.cdshooks.terminology.TerminologyBackend;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Primary-path coverage: the matcher against the {@code referenceMap} backend,
 * matching purely on direct reference-code subsumption (RxNORM CUI → RxClass
 * NUI through {@code concept_reference_term_map}). No SNOMED attribute bridge is
 * involved — the backend exposes no attribute values, so the candidate set is
 * just the drug and allergen codes themselves.
 *
 * <p>The {@link org.openmrs.module.cdshooks.api.impl.AllergyMatcherImplTest} and
 * {@link org.openmrs.module.cdshooks.api.impl.AllergyMatcherEdgeCasesTest}
 * classes cover the secondary SNOMED finding/product bridge.
 */
@RunWith(MockitoJUnitRunner.class)
public class AllergyMatcherReferenceMapTest {

    // RxNORM ingredient CUIs and RxClass class NUIs (shapes, not live values).
    private static final String CUI_AMOXICILLIN = "723";
    private static final String CUI_CEFAZOLIN = "2180";
    private static final String NUI_PENICILLINS = "N0000175503";

    @Mock
    private TerminologyBackend referenceMap;

    @InjectMocks
    private AllergyMatcherImpl matcher;

    @Before
    public void defaultStubs() {
        // referenceMap returns no attribute relationships — getAttributeValues
        // defaults to an empty List, so only the codes themselves are compared.
        // Default subsumption is "no opinion" unless a pair is explicitly wired.
        lenient().when(referenceMap.subsumes(any(), any())).thenReturn(SubsumptionOutcome.UNKNOWN);
    }

    @Test
    public void ingredientMatch_sameCui() {
        AllergyMatcher.DrugInput amoxicillin =
                new AllergyMatcher.DrugInput("Amoxicillin", List.of(CUI_AMOXICILLIN));
        AllergyMatcher.AllergyInput amoxicillinAllergy = allergy(
                "Allergy to amoxicillin", CUI_AMOXICILLIN);

        List<AllergyMatch> matches = matcher.match(amoxicillin, List.of(amoxicillinAllergy));

        assertThat(matches, hasSize(1));
        assertThat(matches.get(0).getMatchType(), is(AllergyMatch.MatchType.INGREDIENT));
    }

    @Test
    public void classMatch_cuiNarrowerThanNui() {
        // amoxicillin (CUI) NARROWER-THAN penicillins (NUI): the allergen NUI
        // subsumes the drug CUI.
        when(referenceMap.subsumes(eq(NUI_PENICILLINS), eq(CUI_AMOXICILLIN)))
                .thenReturn(SubsumptionOutcome.SUBSUMES);

        AllergyMatcher.DrugInput amoxicillin =
                new AllergyMatcher.DrugInput("Amoxicillin", List.of(CUI_AMOXICILLIN));
        AllergyMatcher.AllergyInput penicillinAllergy = allergy(
                "Allergy to penicillins", NUI_PENICILLINS);

        List<AllergyMatch> matches = matcher.match(amoxicillin, List.of(penicillinAllergy));

        assertThat(matches, hasSize(1));
        assertThat(matches.get(0).getMatchType(), is(AllergyMatch.MatchType.CLASS));
    }

    @Test
    public void noMatch_unrelatedClass() {
        // Patient allergic to penicillins; cefazolin (a cephalosporin) is not a
        // penicillin, so the NUI does not subsume the drug CUI.
        AllergyMatcher.DrugInput cefazolin =
                new AllergyMatcher.DrugInput("Cefazolin", List.of(CUI_CEFAZOLIN));
        AllergyMatcher.AllergyInput penicillinAllergy = allergy(
                "Allergy to penicillins", NUI_PENICILLINS);

        assertThat(matcher.match(cefazolin, List.of(penicillinAllergy)), is(empty()));
    }

    @Test
    public void bothIngredientAndClass_onOneAllergen() {
        // The allergen carries both the ingredient CUI and the class NUI, so the
        // drug matches it twice: once on ingredient (equal CUI) and once on
        // class (NUI subsumes CUI). Andrew Kanter: the warning should surface
        // both.
        when(referenceMap.subsumes(eq(NUI_PENICILLINS), eq(CUI_AMOXICILLIN)))
                .thenReturn(SubsumptionOutcome.SUBSUMES);

        AllergyMatcher.DrugInput amoxicillin =
                new AllergyMatcher.DrugInput("Amoxicillin", List.of(CUI_AMOXICILLIN));
        AllergyMatcher.AllergyInput allergy = new AllergyMatcher.AllergyInput(
                "Allergy to amoxicillin / penicillins",
                List.of(CUI_AMOXICILLIN, NUI_PENICILLINS),
                AllergyMatch.Severity.SEVERE,
                null);

        List<AllergyMatch> matches = matcher.match(amoxicillin, List.of(allergy));

        assertThat(matches, hasSize(2));
        assertThat(matches.stream().map(AllergyMatch::getMatchType).collect(java.util.stream.Collectors.toList()),
                containsInAnyOrder(AllergyMatch.MatchType.INGREDIENT, AllergyMatch.MatchType.CLASS));
    }

    @Test
    public void classMatchExplanation_avoidsCausativeAgentVocabulary() {
        when(referenceMap.subsumes(eq(NUI_PENICILLINS), eq(CUI_AMOXICILLIN)))
                .thenReturn(SubsumptionOutcome.SUBSUMES);

        AllergyMatcher.DrugInput amoxicillin =
                new AllergyMatcher.DrugInput("Amoxicillin", List.of(CUI_AMOXICILLIN));
        AllergyMatcher.AllergyInput penicillinAllergy = allergy(
                "Allergy to penicillins", NUI_PENICILLINS);

        List<AllergyMatch> matches = matcher.match(amoxicillin, List.of(penicillinAllergy));

        assertThat(matches, hasSize(1));
        String explanation = matches.get(0).getExplanation();
        assertThat(explanation.toLowerCase().contains("causative agent"), is(false));
        assertThat(explanation.contains("recorded allergy"), is(true));
    }

    private static AllergyMatcher.AllergyInput allergy(String display, String code) {
        return new AllergyMatcher.AllergyInput(
                display, List.of(code), AllergyMatch.Severity.SEVERE, null);
    }
}
