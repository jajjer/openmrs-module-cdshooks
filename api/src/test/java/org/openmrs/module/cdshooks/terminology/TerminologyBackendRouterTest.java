/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.terminology;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openmrs.module.cdshooks.model.CodedConcept;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the backend selection and {@code both}-mode composition logic
 * in {@link TerminologyBackendRouter}. The {@code cdshooks.terminologyBackend}
 * value is supplied by overriding the {@code configuredBackend()} seam, so no
 * OpenMRS service context is needed.
 */
@RunWith(MockitoJUnitRunner.class)
public class TerminologyBackendRouterTest {

    @Mock
    private TerminologyBackend snowstorm;

    @Mock
    private TerminologyBackend referenceMap;

    private TerminologyBackendRouter router(String mode) {
        TerminologyBackendRouter r = new TerminologyBackendRouter() {
            @Override
            String configuredBackend() {
                return mode;
            }
        };
        r.setSnowstorm(snowstorm);
        r.setReferenceMap(referenceMap);
        return r;
    }

    @Test
    public void nullMode_defaultsToReferenceMap() {
        when(referenceMap.subsumes("a", "b")).thenReturn(SubsumptionOutcome.SUBSUMES);
        assertThat(router(null).subsumes("a", "b"), is(SubsumptionOutcome.SUBSUMES));
        verifyNoInteractions(snowstorm);
    }

    @Test
    public void snowstormMode_delegatesToSnowstorm() {
        when(snowstorm.subsumes("a", "b")).thenReturn(SubsumptionOutcome.NOT_SUBSUMED);
        assertThat(router("snowstorm").subsumes("a", "b"), is(SubsumptionOutcome.NOT_SUBSUMED));
        verifyNoInteractions(referenceMap);
    }

    @Test
    public void referenceMapMode_delegatesToReferenceMap() {
        when(referenceMap.subsumes("a", "b")).thenReturn(SubsumptionOutcome.SUBSUMES);
        assertThat(router("referenceMap").subsumes("a", "b"), is(SubsumptionOutcome.SUBSUMES));
        verifyNoInteractions(snowstorm);
    }

    @Test
    public void unrecognisedMode_defaultsToReferenceMap() {
        when(referenceMap.subsumes("a", "b")).thenReturn(SubsumptionOutcome.SUBSUMES);
        assertThat(router("wat").subsumes("a", "b"), is(SubsumptionOutcome.SUBSUMES));
        verifyNoInteractions(snowstorm);
    }

    @Test
    public void both_localAncestry_shortCircuitsSnowstorm() {
        when(referenceMap.subsumes("a", "b")).thenReturn(SubsumptionOutcome.SUBSUMES);
        assertThat(router("both").subsumes("a", "b"), is(SubsumptionOutcome.SUBSUMES));
        verify(snowstorm, never()).subsumes(any(), any());
    }

    @Test
    public void both_localEquivalent_shortCircuitsSnowstorm() {
        when(referenceMap.subsumes("a", "b")).thenReturn(SubsumptionOutcome.EQUIVALENT);
        assertThat(router("both").subsumes("a", "b"), is(SubsumptionOutcome.EQUIVALENT));
        verify(snowstorm, never()).subsumes(any(), any());
    }

    @Test
    public void both_localUnknown_fallsBackToSnowstormPositive() {
        when(referenceMap.subsumes("a", "b")).thenReturn(SubsumptionOutcome.UNKNOWN);
        when(snowstorm.subsumes("a", "b")).thenReturn(SubsumptionOutcome.SUBSUMES);
        assertThat(router("both").subsumes("a", "b"), is(SubsumptionOutcome.SUBSUMES));
    }

    @Test
    public void both_localDefiniteNo_preferredOverRemoteUnknown() {
        when(referenceMap.subsumes("a", "b")).thenReturn(SubsumptionOutcome.NOT_SUBSUMED);
        when(snowstorm.subsumes("a", "b")).thenReturn(SubsumptionOutcome.UNKNOWN);
        assertThat(router("both").subsumes("a", "b"), is(SubsumptionOutcome.NOT_SUBSUMED));
    }

    @Test
    public void both_bothUnknown_returnsUnknown() {
        when(referenceMap.subsumes("a", "b")).thenReturn(SubsumptionOutcome.UNKNOWN);
        when(snowstorm.subsumes("a", "b")).thenReturn(SubsumptionOutcome.UNKNOWN);
        assertThat(router("both").subsumes("a", "b"), is(SubsumptionOutcome.UNKNOWN));
    }

    @Test
    public void getAttributeValues_snowstormMode_delegates() {
        CodedConcept c = new CodedConcept("123", "Substance");
        when(snowstorm.getAttributeValues("p", "attr")).thenReturn(List.of(c));
        assertThat(router("snowstorm").getAttributeValues("p", "attr"), contains(c));
        verifyNoInteractions(referenceMap);
    }

    @Test
    public void getAttributeValues_both_mergesWithoutDuplicates() {
        CodedConcept fromSnowstorm = new CodedConcept("123", "Substance");
        when(snowstorm.getAttributeValues("p", "attr")).thenReturn(List.of(fromSnowstorm));
        // referenceMap exposes no attribute relationships
        lenient().when(referenceMap.getAttributeValues("p", "attr")).thenReturn(List.of());
        assertThat(router("both").getAttributeValues("p", "attr"), contains(fromSnowstorm));
    }
}
