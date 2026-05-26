package org.openmrs.module.cdshooks.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openmrs.Allergen;
import org.openmrs.AllergenType;
import org.openmrs.Allergies;
import org.openmrs.Allergy;
import org.openmrs.Concept;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptName;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptSource;
import org.openmrs.Patient;
import org.openmrs.api.PatientService;
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.model.AllergyMatch;
import org.openmrs.module.cdshooks.model.CdsHooksRequest;
import org.openmrs.module.cdshooks.model.CdsHooksResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Mocked end-to-end tests: CDS-Hooks request -> patient lookup -> allergy
 * extraction -> matcher -> Card response. The actual matching is mocked; the
 * point of these tests is the wiring around the matcher (request parsing,
 * patient/allergy fetch, SNOMED-mapping extraction, severity mapping, Card
 * construction).
 */
@RunWith(MockitoJUnitRunner.class)
public class CdsHooksServiceImplTest {

    private static final String PATIENT_UUID = "fbb64b8d-ada6-4182-8b90-ccce27b8a000";

    @Mock private PatientService patientService;
    @Mock private AllergyMatcher matcher;

    private final CdsHooksRequestParser parser = new CdsHooksRequestParser();
    private final SnomedMappingExtractor snomedExtractor = new SnomedMappingExtractor();
    private final SeverityMapper severityMapper = new SeverityMapper();

    @InjectMocks private CdsHooksServiceImpl service;

    @Before
    public void wireRealCollaborators() {
        // Mockito only injects @Mock fields; wire the non-mock helpers manually.
        service.getClass(); // ensures @InjectMocks ran
        // We need to set the non-mocked fields via reflection because they're @Autowired private.
        setField(service, "parser", parser);
        setField(service, "snomedExtractor", snomedExtractor);
        setField(service, "severityMapper", severityMapper);
    }

    @Test
    public void unknownPatient_returnsEmpty() throws Exception {
        when(patientService.getPatientByUuid(PATIENT_UUID)).thenReturn(null);

        CdsHooksResponse resp = service.evaluateDrugAllergy(buildRequest(PATIENT_UUID, "27658006"));

        assertThat(resp.getCards(), is(empty()));
    }

    @Test
    public void patientWithNoAllergies_returnsEmpty() throws Exception {
        Patient patient = new Patient();
        when(patientService.getPatientByUuid(PATIENT_UUID)).thenReturn(patient);
        when(patientService.getAllergies(patient)).thenReturn(new Allergies());

        CdsHooksResponse resp = service.evaluateDrugAllergy(buildRequest(PATIENT_UUID, "27658006"));

        assertThat(resp.getCards(), is(empty()));
    }

    @Test
    public void matchProducesCriticalCard_whenSeverityIsSevere() throws Exception {
        Patient patient = new Patient();
        when(patientService.getPatientByUuid(PATIENT_UUID)).thenReturn(patient);

        Allergies allergies = new Allergies();
        allergies.add(buildAllergy("Allergy to penicillin", "Severe", "Hepatotoxicity"));
        when(patientService.getAllergies(patient)).thenReturn(allergies);

        when(matcher.match(any(), any())).thenReturn(List.of(new AllergyMatch(
                "Allergy to penicillin",
                AllergyMatch.MatchType.CLASS,
                AllergyMatch.Severity.SEVERE,
                "Hepatotoxicity",
                "Amoxicillin contains Amoxicillin (substance), which is a Penicillin.")));

        CdsHooksResponse resp = service.evaluateDrugAllergy(buildRequest(PATIENT_UUID, "27658006"));

        assertThat(resp.getCards(), hasSize(1));
        CdsHooksResponse.Card card = resp.getCards().get(0);
        assertThat(card.indicator, is("critical"));
        assertThat(card.summary, startsWith("⚠ Allergy to penicillin"));
        assertThat(card.detail, startsWith("Amoxicillin contains Amoxicillin (substance)"));
    }

    /* -------------------- helpers -------------------- */

    private CdsHooksRequest buildRequest(String patientUuid, String drugSctid) throws Exception {
        String json = "{ \"hook\": \"medication-prescribe\", \"context\": {"
                + "  \"patientId\": \"" + patientUuid + "\","
                + "  \"medications\": { \"entry\": [{ \"resource\": {"
                + "    \"medicationCodeableConcept\": { \"text\": \"Amoxicillin\","
                + "      \"coding\": [{ \"system\": \"http://snomed.info/sct\", \"code\": \"" + drugSctid + "\" }] } } }] }"
                + "} }";
        return new ObjectMapper().readValue(json, CdsHooksRequest.class);
    }

    /**
     * Build a minimal Allergy where the allergen concept has a SNOMED mapping
     * sufficient for the extractor to find it, and the severity concept has
     * the named display.
     */
    private Allergy buildAllergy(String allergenName, String severityName, String reactionName) {
        // The service skips allergies whose allergen concept has no SNOMED mapping,
        // so the test concept needs at least one.
        Concept allergenConcept = conceptNamed(allergenName);
        attachSnomedMapping(allergenConcept, "91936005");

        Allergen allergen = new Allergen(AllergenType.DRUG, allergenConcept, null);
        Allergy allergy = new Allergy();
        allergy.setAllergen(allergen);
        allergy.setSeverity(conceptNamed(severityName));
        return allergy;
    }

    private static Concept conceptNamed(String name) {
        Concept c = new Concept();
        ConceptName cn = new ConceptName(name, Locale.ENGLISH);
        c.addName(cn);
        c.setFullySpecifiedName(cn);
        return c;
    }

    private static void attachSnomedMapping(Concept concept, String sctid) {
        ConceptSource source = new ConceptSource();
        source.setName("SNOMED CT");
        ConceptReferenceTerm term = new ConceptReferenceTerm();
        term.setConceptSource(source);
        term.setCode(sctid);
        ConceptMap map = new ConceptMap();
        map.setConceptReferenceTerm(term);
        map.setConceptMapType(new ConceptMapType());
        concept.addConceptMapping(map);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
