package org.openmrs.module.cdshooks.api.impl;

import org.junit.Test;
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.model.AllergyMatch;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests the audit record shape. The actual SLF4J emission is exercised
 * implicitly via service-impl tests; what we pin here is the schema, since
 * downstream consumers will parse these records.
 */
public class CdsAuditLoggerTest {

    private final CdsAuditLogger logger = new CdsAuditLogger();

    @Test
    public void successRecord_includesAllRequiredFields() {
        Map<String, Object> record = logger.buildRecord(
            "hook-instance-1",
            "patient-uuid",
            List.of(new AllergyMatcher.DrugInput("Amoxicillin", List.of("27658006"))),
            List.of(new AllergyMatch(
                "Allergy to penicillin",
                AllergyMatch.MatchType.CLASS,
                AllergyMatch.Severity.SEVERE,
                "Hepatic toxicity",
                "Amoxicillin contains Amoxicillin (substance), which is a Penicillin."
            )),
            CdsAuditLogger.Outcome.SUCCESS
        );

        assertThat(record, hasEntry(is("hookInstance"), is("hook-instance-1")));
        assertThat(record, hasEntry(is("patientUuid"), is("patient-uuid")));
        assertThat(record, hasEntry(is("outcome"), is("SUCCESS")));
        assertThat(record, hasEntry(is("matchCount"), is(1)));
        assertThat(record.get("timestamp"), is(notNullValue()));
        assertThat(record.get("userId"), is(notNullValue()));
        assertThat(record.get("drugs"), is(notNullValue()));
        assertThat(record.get("matches"), is(notNullValue()));
    }

    @Test
    public void noDataRecord_isStillWellFormed() {
        Map<String, Object> record = logger.buildRecord(
            "hook-instance-2", null, null, List.of(), CdsAuditLogger.Outcome.NO_DATA);
        assertThat(record, hasEntry(is("outcome"), is("NO_DATA")));
        assertThat((List<?>) record.get("drugs"), is(empty()));
        assertThat((List<?>) record.get("matches"), is(empty()));
        assertThat(record, hasEntry(is("matchCount"), is(0)));
    }

    @Test
    public void unavailableOutcome_isPreserved() {
        Map<String, Object> record = logger.buildRecord(
            "h", "p",
            List.of(new AllergyMatcher.DrugInput("Amoxicillin", List.of("27658006"))),
            List.of(),
            CdsAuditLogger.Outcome.UNAVAILABLE);
        assertThat(record, hasEntry(is("outcome"), is("UNAVAILABLE")));
    }

    @Test
    public void nullSeverity_emitsUnknownInRecord() {
        Map<String, Object> record = logger.buildRecord(
            "h", "p", List.of(),
            List.of(new AllergyMatch("A", AllergyMatch.MatchType.CLASS, null, null, "X")),
            CdsAuditLogger.Outcome.SUCCESS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) record.get("matches");
        assertThat(matches.get(0), hasEntry(is("severity"), is("UNKNOWN")));
    }

    @Test
    public void multipleDrugs_allRecorded() {
        Map<String, Object> record = logger.buildRecord(
            "h", "p",
            List.of(
                new AllergyMatcher.DrugInput("Amoxicillin", List.of("27658006")),
                new AllergyMatcher.DrugInput("Acetaminophen", List.of("777067000"))
            ),
            List.of(),
            CdsAuditLogger.Outcome.SUCCESS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> drugs = (List<Map<String, Object>>) record.get("drugs");
        assertThat(drugs.size(), is(2));
        assertThat(drugs.stream().map(d -> d.get("display")).toList(),
                containsInAnyOrder("Amoxicillin", "Acetaminophen"));
    }
}
