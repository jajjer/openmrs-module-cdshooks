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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.model.CdsHooksRequest;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class CdsHooksRequestParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CdsHooksRequestParser parser = new CdsHooksRequestParser();

    private CdsHooksRequest parse(String json) throws Exception {
        return mapper.readValue(json, CdsHooksRequest.class);
    }

    @Test
    public void extractsPatientUuidFromContext() throws Exception {
        CdsHooksRequest req = parse("{ \"hook\": \"medication-prescribe\", \"context\": { \"patientId\": \"abc-123\" } }");
        assertThat(parser.extractPatientUuid(req), is("abc-123"));
    }

    @Test
    public void extractsSnomedCodedMedication() throws Exception {
        String json = "{"
                + "  \"hook\": \"medication-prescribe\","
                + "  \"context\": {"
                + "    \"patientId\": \"p1\","
                + "    \"medications\": {"
                + "      \"resourceType\": \"Bundle\","
                + "      \"entry\": [{"
                + "        \"resource\": {"
                + "          \"resourceType\": \"MedicationRequest\","
                + "          \"medicationCodeableConcept\": {"
                + "            \"text\": \"Amoxicillin 500mg\","
                + "            \"coding\": ["
                + "              { \"system\": \"https://cielterminology.org\", \"code\": \"71160\" },"
                + "              { \"system\": \"http://snomed.info/sct\", \"code\": \"27658006\", \"display\": \"Amoxicillin\" }"
                + "            ]"
                + "          }"
                + "        }"
                + "      }]"
                + "    }"
                + "  }"
                + "}";

        List<AllergyMatcher.DrugInput> drugs = parser.extractDrugs(parse(json));

        assertThat(drugs, notNullValue());
        assertThat(drugs.size(), is(1));
        AllergyMatcher.DrugInput drug = drugs.get(0);
        assertThat(drug.display, is("Amoxicillin 500mg"));
        assertThat(drug.referenceCodes, contains("27658006"));
    }

    @Test
    public void ignoresUnrecognisedSystemCodings() throws Exception {
        // CIEL isn't a hierarchy the matcher resolves, so a CIEL-only coding is dropped.
        String json = "{ \"context\": { \"medications\": { \"entry\": [{ \"resource\": {"
                + "  \"medicationCodeableConcept\": {"
                + "    \"coding\": [{ \"system\": \"https://cielterminology.org\", \"code\": \"71160\" }]"
                + "  } } }] } } }";

        assertThat(parser.extractDrugs(parse(json)), is(empty()));
    }

    @Test
    public void acceptsRxNormCoding() throws Exception {
        // CIEL maps drugs to both SNOMED and RxNORM; an RxNORM-coded order must reach the matcher.
        String json = "{ \"context\": { \"medications\": { \"entry\": [{ \"resource\": {"
                + "  \"medicationCodeableConcept\": { \"text\": \"Amoxicillin\","
                + "    \"coding\": [{ \"system\": \"http://www.nlm.nih.gov/research/umls/rxnorm\", \"code\": \"723\" }]"
                + "  } } }] } } }";

        assertThat(parser.extractDrugs(parse(json)).get(0).referenceCodes, contains("723"));
    }

    @Test
    public void acceptsCodingWithNoSystem() throws Exception {
        String json = "{ \"context\": { \"medications\": { \"entry\": [{ \"resource\": {"
                + "  \"medicationCodeableConcept\": { \"coding\": [{ \"code\": \"723\" }] } } }] } } }";

        assertThat(parser.extractDrugs(parse(json)).get(0).referenceCodes, contains("723"));
    }

    @Test
    public void collectsBothSnomedAndRxNormCodings() throws Exception {
        String json = "{ \"context\": { \"medications\": { \"entry\": [{ \"resource\": {"
                + "  \"medicationCodeableConcept\": { \"text\": \"Amoxicillin\", \"coding\": ["
                + "    { \"system\": \"http://snomed.info/sct\", \"code\": \"27658006\" },"
                + "    { \"system\": \"http://www.nlm.nih.gov/research/umls/rxnorm\", \"code\": \"723\" }"
                + "  ] } } }] } } }";

        assertThat(parser.extractDrugs(parse(json)).get(0).referenceCodes,
                containsInAnyOrder("27658006", "723"));
    }

    @Test
    public void emptyRequestReturnsEmpty() {
        assertThat(parser.extractPatientUuid(null), is(nullValue()));
        assertThat(parser.extractDrugs(null), is(empty()));
        assertThat(parser.extractDrugs(new CdsHooksRequest()), is(empty()));
    }

    @Test
    public void handlesMultipleMedicationsInBundle() throws Exception {
        String json = "{ \"context\": {"
                + "  \"patientId\": \"p1\","
                + "  \"medications\": { \"entry\": ["
                + "    { \"resource\": { \"medicationCodeableConcept\": { \"text\": \"Amoxicillin\","
                + "        \"coding\": [{ \"system\": \"http://snomed.info/sct\", \"code\": \"27658006\" }] } } },"
                + "    { \"resource\": { \"medicationCodeableConcept\": { \"text\": \"Acetaminophen\","
                + "        \"coding\": [{ \"system\": \"http://snomed.info/sct\", \"code\": \"777067000\" }] } } }"
                + "  ] }"
                + "} }";

        List<AllergyMatcher.DrugInput> drugs = parser.extractDrugs(parse(json));
        assertThat(drugs.size(), is(2));
        assertThat(drugs.get(0).display, is("Amoxicillin"));
        assertThat(drugs.get(1).display, is("Acetaminophen"));
    }
}
