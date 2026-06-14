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
import org.openmrs.module.cdshooks.model.AllergyMatch;
import org.openmrs.module.cdshooks.model.CodedConcept;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;
import org.openmrs.module.cdshooks.terminology.TerminologyBackend;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Verifies the Java port reproduces the v2 Python spike result for the
 * worked example: a patient with a penicillin allergy and an Amoxicillin
 * order should produce a CLASS match.
 */
@RunWith(MockitoJUnitRunner.class)
public class AllergyMatcherImplTest {

    // SNOMED SCTIDs from the spike data check
    private static final String FINDING_PENICILLIN_ALLERGY = "91936005";
    private static final String SUBSTANCE_PENICILLIN = "764146007";
    private static final String PRODUCT_AMOXICILLIN = "27658006";
    private static final String SUBSTANCE_AMOXICILLIN = "372687004";
    private static final String PRODUCT_ACETAMINOPHEN = "777067000";

    @Mock
    private TerminologyBackend snowstorm;

    @InjectMocks
    private AllergyMatcherImpl matcher;

    @Before
    public void setUp() {
        // Allergen finding → causative agents
        when(snowstorm.getAttributeValues(FINDING_PENICILLIN_ALLERGY,
                CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_PENICILLIN, "Penicillin")));

        // Drug product → active ingredients
        when(snowstorm.getAttributeValues(PRODUCT_AMOXICILLIN,
                CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(new CodedConcept(SUBSTANCE_AMOXICILLIN, "Amoxicillin (substance)")));
        when(snowstorm.getAttributeValues(PRODUCT_ACETAMINOPHEN,
                CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(new CodedConcept("90332006", "Acetaminophen (substance)")));

        // All other getAttributeValues calls return an empty List (Mockito default for List returns).

        // Default subsumes: not-subsumed
        when(snowstorm.subsumes(any(), any())).thenReturn(SubsumptionOutcome.NOT_SUBSUMED);
        // Positive case: Penicillin substance subsumes Amoxicillin substance
        when(snowstorm.subsumes(eq(SUBSTANCE_PENICILLIN), eq(SUBSTANCE_AMOXICILLIN)))
            .thenReturn(SubsumptionOutcome.SUBSUMES);
    }

    @Test
    public void positive_amoxicillinVsPenicillinAllergy_returnsClassMatch() {
        AllergyMatcher.DrugInput amoxicillin =
                new AllergyMatcher.DrugInput("Amoxicillin", List.of(PRODUCT_AMOXICILLIN));
        AllergyMatcher.AllergyInput penicillinAllergy = new AllergyMatcher.AllergyInput(
                "Allergy to penicillin",
                List.of(FINDING_PENICILLIN_ALLERGY),
                AllergyMatch.Severity.SEVERE,
                "Hepatotoxicity");

        List<AllergyMatch> matches = matcher.match(amoxicillin, List.of(penicillinAllergy));

        assertThat(matches, hasSize(1));
        AllergyMatch m = matches.get(0);
        assertThat(m.getMatchType(), is(AllergyMatch.MatchType.CLASS));
        assertThat(m.getSeverity(), is(AllergyMatch.Severity.SEVERE));
        assertThat(m.getAllergenDisplay(), is("Allergy to penicillin"));
    }

    @Test
    public void classMatchAgainstBroadRoot_isSuppressed() {
        // Allergen finding whose causative agent is the SNOMED root "Substance"
        // (105590001), which subsumes essentially every drug. Such a match is
        // noise and must be filtered (default cdshooks.classMatchExcludedCodes).
        String findingBroadAllergy = "300916003";
        String substanceRoot = "105590001";
        when(snowstorm.getAttributeValues(findingBroadAllergy,
                CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(List.of(new CodedConcept(substanceRoot, "Substance")));
        // Subsumption WOULD report ancestry; the exclusion filter must suppress
        // the match before it is ever consulted (hence lenient — never called).
        lenient().when(snowstorm.subsumes(eq(substanceRoot), eq(SUBSTANCE_AMOXICILLIN)))
            .thenReturn(SubsumptionOutcome.SUBSUMES);

        AllergyMatcher.DrugInput amoxicillin =
                new AllergyMatcher.DrugInput("Amoxicillin", List.of(PRODUCT_AMOXICILLIN));
        AllergyMatcher.AllergyInput broadAllergy = new AllergyMatcher.AllergyInput(
                "Allergy to substance",
                List.of(findingBroadAllergy),
                AllergyMatch.Severity.SEVERE,
                null);

        assertThat(matcher.match(amoxicillin, List.of(broadAllergy)), is(empty()));
    }

    @Test
    public void negative_acetaminophenVsPenicillinAllergy_noMatch() {
        AllergyMatcher.DrugInput acetaminophen =
                new AllergyMatcher.DrugInput("Acetaminophen", List.of(PRODUCT_ACETAMINOPHEN));
        AllergyMatcher.AllergyInput penicillinAllergy = new AllergyMatcher.AllergyInput(
                "Allergy to penicillin",
                List.of(FINDING_PENICILLIN_ALLERGY),
                AllergyMatch.Severity.SEVERE,
                "Hepatotoxicity");

        List<AllergyMatch> matches = matcher.match(acetaminophen, List.of(penicillinAllergy));

        assertThat(matches, is(empty()));
    }

    @Test
    public void emptyAllergies_returnsEmpty() {
        AllergyMatcher.DrugInput amoxicillin =
                new AllergyMatcher.DrugInput("Amoxicillin", List.of(PRODUCT_AMOXICILLIN));
        assertThat(matcher.match(amoxicillin, List.of()), is(empty()));
    }
}
