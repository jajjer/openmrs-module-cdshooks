package org.openmrs.module.cdshooks.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SubsumptionOutcomeTest {

    @Test
    public void parsesAllFhirCodes() {
        assertThat(SubsumptionOutcome.fromFhirCode("equivalent"), is(SubsumptionOutcome.EQUIVALENT));
        assertThat(SubsumptionOutcome.fromFhirCode("subsumes"), is(SubsumptionOutcome.SUBSUMES));
        assertThat(SubsumptionOutcome.fromFhirCode("subsumed-by"), is(SubsumptionOutcome.SUBSUMED_BY));
        assertThat(SubsumptionOutcome.fromFhirCode("not-subsumed"), is(SubsumptionOutcome.NOT_SUBSUMED));
    }

    @Test
    public void unknownCodeMapsToUnknown() {
        assertThat(SubsumptionOutcome.fromFhirCode("garbage"), is(SubsumptionOutcome.UNKNOWN));
        assertThat(SubsumptionOutcome.fromFhirCode(""), is(SubsumptionOutcome.UNKNOWN));
        assertThat(SubsumptionOutcome.fromFhirCode(null), is(SubsumptionOutcome.UNKNOWN));
    }

    @Test
    public void caseSensitive_mirrorsFhirSpec() {
        // FHIR uses lowercase codes; uppercase isn't valid per spec, so we
        // intentionally don't normalize.
        assertThat(SubsumptionOutcome.fromFhirCode("SUBSUMES"), is(SubsumptionOutcome.UNKNOWN));
    }

    @Test
    public void indicatesAncestry_isTrueOnlyForSubsumesAndEquivalent() {
        assertThat(SubsumptionOutcome.SUBSUMES.indicatesAncestry(), is(true));
        assertThat(SubsumptionOutcome.EQUIVALENT.indicatesAncestry(), is(true));
        assertThat(SubsumptionOutcome.SUBSUMED_BY.indicatesAncestry(), is(false));
        assertThat(SubsumptionOutcome.NOT_SUBSUMED.indicatesAncestry(), is(false));
        assertThat(SubsumptionOutcome.UNKNOWN.indicatesAncestry(), is(false));
    }
}
