package org.openmrs.module.cdshooks.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.model.CdsHooksRequest;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
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
        assertThat(drug.snomedSctids, contains("27658006"));
    }

    @Test
    public void ignoresNonSnomedCodings() throws Exception {
        String json = "{ \"context\": { \"medications\": { \"entry\": [{ \"resource\": {"
                + "  \"medicationCodeableConcept\": {"
                + "    \"coding\": [{ \"system\": \"https://cielterminology.org\", \"code\": \"71160\" }]"
                + "  } } }] } } }";

        assertThat(parser.extractDrugs(parse(json)), is(empty()));
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
