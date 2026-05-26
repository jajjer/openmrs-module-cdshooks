package org.openmrs.module.cdshooks.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.openmrs.module.cdshooks.model.SnomedConcept;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;

/**
 * Direct tests for the JSON-parsing logic of SnowstormClient. The HTTP layer
 * is exercised by the spike's curl scripts and by AllergyMatcherImplTest with
 * a mocked client; this test pins the FHIR Parameters response interpretation.
 */
public class SnowstormClientParseTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String CAUSATIVE_AGENT = "246075003";

    @Test
    public void parseSubsumesOutcome_subsumes() throws Exception {
        JsonNode body = json("{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"outcome\",\"valueCode\":\"subsumes\"}]}");
        assertThat(SnowstormClient.parseSubsumesOutcome(body), is(SubsumptionOutcome.SUBSUMES));
    }

    @Test
    public void parseSubsumesOutcome_notSubsumed() throws Exception {
        JsonNode body = json("{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"outcome\",\"valueCode\":\"not-subsumed\"}]}");
        assertThat(SnowstormClient.parseSubsumesOutcome(body), is(SubsumptionOutcome.NOT_SUBSUMED));
    }

    @Test
    public void parseSubsumesOutcome_nullBody_isUnknown() {
        assertThat(SnowstormClient.parseSubsumesOutcome(null), is(SubsumptionOutcome.UNKNOWN));
    }

    @Test
    public void parseSubsumesOutcome_missingOutcome_isUnknown() throws Exception {
        JsonNode body = json("{\"resourceType\":\"Parameters\",\"parameter\":[]}");
        assertThat(SnowstormClient.parseSubsumesOutcome(body), is(SubsumptionOutcome.UNKNOWN));
    }

    @Test
    public void parseSubsumesOutcome_unknownCode_isUnknown() throws Exception {
        JsonNode body = json("{\"parameter\":[{\"name\":\"outcome\",\"valueCode\":\"banana\"}]}");
        assertThat(SnowstormClient.parseSubsumesOutcome(body), is(SubsumptionOutcome.UNKNOWN));
    }

    @Test
    public void parseAttributeValues_extractsMatchingAttribute() throws Exception {
        // Real-shape Snowstorm $lookup response excerpt: the penicillin-allergy
        // finding (91936005) has multiple Causative agent (246075003) values.
        JsonNode body = json(
            "{\"resourceType\":\"Parameters\",\"parameter\":[" +
            "  {\"name\":\"display\",\"valueString\":\"Allergy to penicillin\"}," +
            "  {\"name\":\"property\",\"part\":[" +
            "    {\"name\":\"code\",\"valueCode\":\"246075003\"}," +
            "    {\"name\":\"value\",\"valueCode\":\"764146007\"}," +
            "    {\"name\":\"description\",\"valueString\":\"Penicillin\"}" +
            "  ]}," +
            "  {\"name\":\"property\",\"part\":[" +
            "    {\"name\":\"code\",\"valueCode\":\"246075003\"}," +
            "    {\"name\":\"value\",\"valueCode\":\"6369005\"}," +
            "    {\"name\":\"description\",\"valueString\":\"Penicillin antibacterial agent\"}" +
            "  ]}," +
            "  {\"name\":\"property\",\"part\":[" +
            "    {\"name\":\"code\",\"valueCode\":\"363698007\"}," +
            "    {\"name\":\"value\",\"valueCode\":\"116003000\"}," +
            "    {\"name\":\"description\",\"valueString\":\"Immune system\"}" +
            "  ]}" +
            "]}");

        List<SnomedConcept> values = SnowstormClient.parseAttributeValues(body, CAUSATIVE_AGENT);

        // Only the two Causative-agent properties; Finding-site (363698007) excluded.
        assertThat(values, containsInAnyOrder(
            hasProperty("sctid", is("764146007")),
            hasProperty("sctid", is("6369005"))
        ));
    }

    @Test
    public void parseAttributeValues_displayCapturedFromDescription() throws Exception {
        JsonNode body = json(
            "{\"parameter\":[{\"name\":\"property\",\"part\":[" +
            "  {\"name\":\"code\",\"valueCode\":\"246075003\"}," +
            "  {\"name\":\"value\",\"valueCode\":\"764146007\"}," +
            "  {\"name\":\"description\",\"valueString\":\"Penicillin\"}" +
            "]}]}");

        List<SnomedConcept> values = SnowstormClient.parseAttributeValues(body, CAUSATIVE_AGENT);
        assertThat(values, contains(hasProperty("display", is("Penicillin"))));
    }

    @Test
    public void parseAttributeValues_emptyOnNullBody() {
        assertThat(SnowstormClient.parseAttributeValues(null, CAUSATIVE_AGENT), is(empty()));
    }

    @Test
    public void parseAttributeValues_emptyOnNoProperties() throws Exception {
        JsonNode body = json("{\"parameter\":[]}");
        assertThat(SnowstormClient.parseAttributeValues(body, CAUSATIVE_AGENT), is(empty()));
    }

    @Test
    public void parseAttributeValues_skipsPropertiesMissingValue() throws Exception {
        JsonNode body = json(
            "{\"parameter\":[{\"name\":\"property\",\"part\":[" +
            "  {\"name\":\"code\",\"valueCode\":\"246075003\"}," +
            "  {\"name\":\"description\",\"valueString\":\"orphan\"}" +
            "]}]}");
        assertThat(SnowstormClient.parseAttributeValues(body, CAUSATIVE_AGENT), is(empty()));
    }

    /* helper */
    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }
}
