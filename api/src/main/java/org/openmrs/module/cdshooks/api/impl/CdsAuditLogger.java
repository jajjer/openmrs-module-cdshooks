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
import org.openmrs.api.context.Context;
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.model.AllergyMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes one structured JSON line per CDS-Hooks invocation to the dedicated
 * {@code cdshooks.audit} SLF4J logger. Production deployments route this
 * logger separately (syslog, Splunk, etc.) so the audit trail is queryable
 * for HIPAA / clinical governance review without interleaving with regular
 * application logs.
 *
 * <p>The audit shape is deliberately small and stable. Adding fields is fine;
 * removing or renaming them is a breaking change for downstream consumers.
 */
@Component
public class CdsAuditLogger {

    /** Outcome of a CDS-Hooks invocation, as recorded in the audit trail. */
    public enum Outcome {
        /** Cards were returned (zero is a valid result). */
        SUCCESS,
        /** Patient or drug context missing/invalid — nothing to check. */
        NO_DATA,
        /** Algorithm could not run — terminology server unreachable, etc. */
        UNAVAILABLE
    }

    private static final Logger auditLog = LoggerFactory.getLogger("cdshooks.audit");
    private static final ObjectMapper M = new ObjectMapper();

    public void logInvocation(String hookInstance,
                               String patientUuid,
                               List<AllergyMatcher.DrugInput> drugs,
                               List<AllergyMatch> matches,
                               Outcome outcome) {
        if (!auditLog.isInfoEnabled()) return; // ops opted out

        Map<String, Object> record = buildRecord(hookInstance, patientUuid, drugs, matches, outcome);
        try {
            auditLog.info(M.writeValueAsString(record));
        } catch (Exception e) {
            // Last-resort fallback — never let audit logging break the request.
            auditLog.info("cdshooks_audit_serialization_failed hookInstance={} outcome={}",
                    hookInstance, outcome);
        }
    }

    /** Package-private for unit testing. */
    Map<String, Object> buildRecord(String hookInstance,
                                     String patientUuid,
                                     List<AllergyMatcher.DrugInput> drugs,
                                     List<AllergyMatch> matches,
                                     Outcome outcome) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", Instant.now().toString());
        record.put("hookInstance", hookInstance);
        record.put("outcome", outcome.name());
        record.put("userId", safeAuthenticatedUserSystemId());
        record.put("patientUuid", patientUuid);
        record.put("drugs", drugs == null ? List.of()
                : drugs.stream()
                       .map(d -> Map.of("display", d.display, "referenceCodes", d.referenceCodes))
                       .collect(Collectors.toList()));
        record.put("matches", matches == null ? List.of()
                : matches.stream().map(m -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("allergen", m.getAllergenDisplay());
                    out.put("matchType", m.getMatchType().name());
                    out.put("severity", m.getSeverity() == null ? "UNKNOWN" : m.getSeverity().name());
                    return out;
                }).collect(Collectors.toList()));
        record.put("matchCount", matches == null ? 0 : matches.size());
        return record;
    }

    /**
     * Returns the {@code systemId} of the currently authenticated OpenMRS user,
     * or {@code "anonymous"} if no user context exists (unit tests, anonymous
     * requests). Never throws — audit must not break the request path.
     */
    private static String safeAuthenticatedUserSystemId() {
        try {
            if (Context.isAuthenticated() && Context.getAuthenticatedUser() != null) {
                return Context.getAuthenticatedUser().getSystemId();
            }
        } catch (Exception ignored) {
            // No user context (test or pre-auth) — fall through.
        }
        return "anonymous";
    }
}
