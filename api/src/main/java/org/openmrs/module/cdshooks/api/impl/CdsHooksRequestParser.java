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

import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.model.CdsHooksRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts the patient UUID and the ordered drugs from a CDS-Hooks
 * {@code medication-prescribe} request.
 *
 * <p>The request's {@code context.medications} field is a FHIR Bundle of draft
 * MedicationRequest resources. Each MedicationRequest may carry a
 * {@code medicationCodeableConcept.coding} array; we collect entries whose
 * system is SNOMED CT (or has no system, treated as the request-default).
 *
 * <p>This parser is deliberately lenient — CDS-Hooks payloads vary by EHR.
 * Unknown fields are ignored; missing fields produce empty results rather
 * than exceptions.
 */
@Component
public class CdsHooksRequestParser {

    private static final String SNOMED_SYSTEM_PREFIX = "http://snomed.info/sct";

    public String extractPatientUuid(CdsHooksRequest request) {
        if (request == null || request.getContext() == null) return null;
        Object pid = request.getContext().get("patientId");
        return pid == null ? null : pid.toString();
    }

    @SuppressWarnings("unchecked")
    public List<AllergyMatcher.DrugInput> extractDrugs(CdsHooksRequest request) {
        List<AllergyMatcher.DrugInput> drugs = new ArrayList<>();
        if (request == null || request.getContext() == null) return drugs;

        Object medications = request.getContext().get("medications");
        if (!(medications instanceof Map)) return drugs;
        Object entries = ((Map<String, Object>) medications).get("entry");
        if (!(entries instanceof List)) return drugs;

        for (Object entryObj : (List<Object>) entries) {
            if (!(entryObj instanceof Map)) continue;
            Object resourceObj = ((Map<String, Object>) entryObj).get("resource");
            if (!(resourceObj instanceof Map)) continue;
            AllergyMatcher.DrugInput drug = parseMedicationRequest((Map<String, Object>) resourceObj);
            if (drug != null) drugs.add(drug);
        }
        return drugs;
    }

    @SuppressWarnings("unchecked")
    private AllergyMatcher.DrugInput parseMedicationRequest(Map<String, Object> resource) {
        Object codeableObj = resource.get("medicationCodeableConcept");
        if (!(codeableObj instanceof Map)) return null;
        Map<String, Object> codeable = (Map<String, Object>) codeableObj;
        String display = stringOrNull(codeable.get("text"));

        Object codingObj = codeable.get("coding");
        if (!(codingObj instanceof List)) return null;

        // Preserve insertion order; dedupe by SCTID.
        Map<String, Boolean> sctids = new LinkedHashMap<>();
        for (Object cObj : (List<Object>) codingObj) {
            if (!(cObj instanceof Map)) continue;
            Map<String, Object> coding = (Map<String, Object>) cObj;
            String system = stringOrNull(coding.get("system"));
            String code = stringOrNull(coding.get("code"));
            if (code == null) continue;
            if (system == null || system.startsWith(SNOMED_SYSTEM_PREFIX)) {
                sctids.put(code.trim(), Boolean.TRUE);
                if (display == null) {
                    display = stringOrNull(coding.get("display"));
                }
            }
        }
        if (sctids.isEmpty()) return null;
        if (display == null) display = "Ordered medication";
        return new AllergyMatcher.DrugInput(display, new ArrayList<>(sctids.keySet()));
    }

    private static String stringOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
