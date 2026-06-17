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
import org.openmrs.ConceptMap;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

public class ReferenceCodeExtractorTest {

    private final ReferenceCodeExtractor extractor = new ReferenceCodeExtractor();

    @Test
    public void nullConcept_returnsEmpty() {
        assertThat(extractor.extract(null), is(empty()));
    }

    @Test
    public void conceptWithNoMappings_returnsEmpty() {
        assertThat(extractor.extract(new Concept()), is(empty()));
    }

    @Test
    public void picksUpSnomedCtByName() {
        Concept c = withMapping("SNOMED CT", null, "91936005");
        assertThat(extractor.extract(c), contains("91936005"));
    }

    @Test
    public void picksUpHl7Code_SCT() {
        // OpenMRS sometimes labels SNOMED sources by their HL7 code "SCT".
        Concept c = withMapping("Some Other Name", "SCT", "67890");
        assertThat(extractor.extract(c), contains("67890"));
    }

    @Test
    public void caseInsensitiveSourceName() {
        Concept c = withMapping("snomed ct", null, "1");
        assertThat(extractor.extract(c), contains("1"));
    }

    @Test
    public void picksUpRxNorm() {
        // RxNORM is now recognised — it drives the reference-map / RxClass path.
        Concept c = withMapping("RxNORM", null, "723");
        assertThat(extractor.extract(c), contains("723"));
    }

    @Test
    public void picksUpRxClassNui() {
        Concept c = withMapping("RxClass", null, "N0000175497");
        assertThat(extractor.extract(c), contains("N0000175497"));
    }

    @Test
    public void ignoresUnrelatedSources() {
        // CIEL/WHOATC/AMPATH/ICD aren't hierarchies this matcher resolves.
        Concept c = new Concept();
        c.addConceptMapping(buildMapping("CIEL", null, "149071"));
        c.addConceptMapping(buildMapping("WHOATC", null, "N02BE01"));
        c.addConceptMapping(buildMapping("AMPATH", null, "453"));
        c.addConceptMapping(buildMapping("ICD-11-WHO", null, "QC44.2"));
        assertThat(extractor.extract(c), is(empty()));
    }

    @Test
    public void collectsSnomedAndRxNormTogether() {
        // A richly-mapped CIEL drug (e.g. Acetaminophen) carries both.
        Concept c = new Concept();
        c.addConceptMapping(buildMapping("CIEL", null, "70116"));
        c.addConceptMapping(buildMapping("SNOMED CT", null, "777067000"));
        c.addConceptMapping(buildMapping("RxNORM", null, "161"));
        assertThat(extractor.extract(c), containsInAnyOrder("777067000", "161"));
    }

    @Test
    public void deduplicatesRepeatedCodes() {
        Concept c = new Concept();
        c.addConceptMapping(buildMapping("SNOMED CT", null, "91936005"));
        c.addConceptMapping(buildMapping("SNOMED", null, "91936005"));
        assertThat(extractor.extract(c), contains("91936005"));
    }

    @Test
    public void trimsWhitespaceInCode() {
        Concept c = withMapping("SNOMED CT", null, "  91936005  ");
        assertThat(extractor.extract(c), contains("91936005"));
    }

    @Test
    public void skipsMappingsWithNullTermOrCode() {
        Concept c = new Concept();
        ConceptMap nullTermMap = new ConceptMap();
        nullTermMap.setConceptMapType(new ConceptMapType());
        c.addConceptMapping(nullTermMap);

        c.addConceptMapping(buildMapping("SNOMED CT", null, null));
        c.addConceptMapping(buildMapping("SNOMED CT", null, "91936005"));
        assertThat(extractor.extract(c), contains("91936005"));
    }

    /* -------------------- helpers -------------------- */

    private static Concept withMapping(String sourceName, String hl7Code, String code) {
        Concept c = new Concept();
        c.addConceptMapping(buildMapping(sourceName, hl7Code, code));
        return c;
    }

    private static ConceptMap buildMapping(String sourceName, String hl7Code, String code) {
        ConceptSource source = new ConceptSource();
        source.setName(sourceName);
        source.setHl7Code(hl7Code);
        ConceptReferenceTerm term = new ConceptReferenceTerm();
        term.setConceptSource(source);
        term.setCode(code);
        ConceptMap map = new ConceptMap();
        map.setConceptReferenceTerm(term);
        map.setConceptMapType(new ConceptMapType());
        return map;
    }
}
