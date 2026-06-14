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

import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.module.cdshooks.model.AllergyMatch;

import java.util.Locale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SeverityMapperTest {

    private final SeverityMapper mapper = new SeverityMapper();

    @Test
    public void nullConcept_isUnknown() {
        assertThat(mapper.map(null), is(AllergyMatch.Severity.UNKNOWN));
    }

    @Test
    public void conceptWithNoNames_isUnknown() {
        assertThat(mapper.map(new Concept()), is(AllergyMatch.Severity.UNKNOWN));
    }

    @Test
    public void severe_mapsToSevere() {
        assertThat(mapper.map(named("Severe")), is(AllergyMatch.Severity.SEVERE));
    }

    @Test
    public void moderate_mapsToModerate() {
        assertThat(mapper.map(named("Moderate")), is(AllergyMatch.Severity.MODERATE));
    }

    @Test
    public void mild_mapsToMild() {
        assertThat(mapper.map(named("Mild")), is(AllergyMatch.Severity.MILD));
    }

    @Test
    public void caseInsensitive() {
        assertThat(mapper.map(named("SEVERE")), is(AllergyMatch.Severity.SEVERE));
        assertThat(mapper.map(named("severe")), is(AllergyMatch.Severity.SEVERE));
        assertThat(mapper.map(named("Severe")), is(AllergyMatch.Severity.SEVERE));
    }

    @Test
    public void whitespaceTrimmed() {
        assertThat(mapper.map(named("  Severe  ")), is(AllergyMatch.Severity.SEVERE));
    }

    /**
     * Patient-safety critical: the previous implementation used String.contains
     * and would have classified "Severe sepsis" (CIEL 112480) and "Severe
     * anaemia" (CIEL 162044) as severity=SEVERE, escalating their associated
     * Cards from warning to critical and altering clinical priority. Exact-
     * match is the right semantics for severity Concepts.
     */
    @Test
    public void clinicalFindingsContainingTheWordSevere_areNotMappedToSevere() {
        assertThat(mapper.map(named("Severe sepsis")), is(AllergyMatch.Severity.UNKNOWN));
        assertThat(mapper.map(named("Severe anaemia")), is(AllergyMatch.Severity.UNKNOWN));
        assertThat(mapper.map(named("Severe microstomia")), is(AllergyMatch.Severity.UNKNOWN));
        assertThat(mapper.map(named("Severe malaria")), is(AllergyMatch.Severity.UNKNOWN));
    }

    @Test
    public void prefersEnglishOverOtherLocales() {
        Concept c = new Concept();
        c.addName(new ConceptName("Sévère", Locale.FRENCH));
        c.addName(new ConceptName("Severe", Locale.ENGLISH));
        assertThat(mapper.map(c), is(AllergyMatch.Severity.SEVERE));
    }

    @Test
    public void fallsBackToNonEnglishWhenNoEnglishPresent() {
        // Without an English name, the mapper picks the first available — which
        // won't match the English labels and so degrades to UNKNOWN rather than
        // silently falling back to a wrong language match.
        Concept c = new Concept();
        c.addName(new ConceptName("Sévère", Locale.FRENCH));
        assertThat(mapper.map(c), is(AllergyMatch.Severity.UNKNOWN));
    }

    @Test
    public void unrecognizedNameIsUnknown() {
        assertThat(mapper.map(named("Catastrophic")), is(AllergyMatch.Severity.UNKNOWN));
        assertThat(mapper.map(named("")), is(AllergyMatch.Severity.UNKNOWN));
    }

    /* -------------------- indicator mapping -------------------- */

    @Test
    public void toCardIndicator_mapsSeverity() {
        assertThat(SeverityMapper.toCardIndicator(AllergyMatch.Severity.SEVERE), is("critical"));
        assertThat(SeverityMapper.toCardIndicator(AllergyMatch.Severity.MODERATE), is("warning"));
        assertThat(SeverityMapper.toCardIndicator(AllergyMatch.Severity.MILD), is("info"));
    }

    @Test
    public void toCardIndicator_unknownAndNullDefaultToWarning() {
        // Conservative default: an unknown severity errs on the side of being
        // visible to the clinician, not invisible.
        assertThat(SeverityMapper.toCardIndicator(AllergyMatch.Severity.UNKNOWN), is("warning"));
        assertThat(SeverityMapper.toCardIndicator(null), is("info"));
    }

    /* -------------------- helpers -------------------- */

    private static Concept named(String name) {
        Concept c = new Concept();
        c.addName(new ConceptName(name, Locale.ENGLISH));
        return c;
    }
}
