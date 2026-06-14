/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

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
