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
import org.openmrs.module.cdshooks.model.SnomedConcept;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private SnowstormClient snowstorm;

    @InjectMocks
    private AllergyMatcherImpl matcher;

    @Before
    public void setUp() {
        // Allergen finding → causative agents
        when(snowstorm.getAttributeValues(FINDING_PENICILLIN_ALLERGY,
                CdsHooksConstants.SCTID_CAUSATIVE_AGENT))
            .thenReturn(List.of(new SnomedConcept(SUBSTANCE_PENICILLIN, "Penicillin")));

        // Drug product → active ingredients
        when(snowstorm.getAttributeValues(PRODUCT_AMOXICILLIN,
                CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(new SnomedConcept(SUBSTANCE_AMOXICILLIN, "Amoxicillin (substance)")));
        when(snowstorm.getAttributeValues(PRODUCT_ACETAMINOPHEN,
                CdsHooksConstants.SCTID_HAS_ACTIVE_INGREDIENT))
            .thenReturn(List.of(new SnomedConcept("90332006", "Acetaminophen (substance)")));

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
