/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openmrs.api.context.Context;
import org.openmrs.module.cdshooks.CdsHooksConstants;
import org.openmrs.module.cdshooks.model.CodedConcept;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;
import org.openmrs.module.cdshooks.terminology.TerminologyBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Wraps the FHIR terminology operations we need against a Snowstorm-compatible
 * server: {@code CodeSystem/$lookup} (for attribute relationships) and
 * {@code CodeSystem/$subsumes}.
 *
 * <p>Results are cached with a TTL pulled from the {@code cdshooks.cacheTtlSeconds}
 * global property. The cache instances are created lazily on first use rather
 * than in a {@code @PostConstruct} — calling
 * {@code Context.getAdministrationService()} during bean creation can deadlock
 * module startup, since the OpenMRS service context is not guaranteed to be
 * fully initialized when module beans are being wired.
 */
@Component(SnowstormClient.BEAN_NAME)
public class SnowstormClient implements TerminologyBackend {

    public static final String BEAN_NAME = "cdshooks.snowstormBackend";

    /** Backend identifier for the {@code cdshooks.terminologyBackend} selector. */
    public static final String BACKEND_NAME = "snowstorm";

    private static final long DEFAULT_TTL_SECONDS = 3600L;

    private static final Logger log = LoggerFactory.getLogger(SnowstormClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile TtlCache<String, List<CodedConcept>> attributeCache;
    private volatile TtlCache<Map.Entry<String, String>, SubsumptionOutcome> subsumesCache;

    /**
     * Returns the values of a given SNOMED attribute on a concept — e.g. all
     * causative-agent values on a finding, or all active-ingredient values on
     * a product. Empty list if the attribute is absent.
     */
    @Override
    public String name() {
        return BACKEND_NAME;
    }

    @Override
    public List<CodedConcept> getAttributeValues(String conceptSctid, String attributeSctid) {
        String cacheKey = conceptSctid + "|" + attributeSctid;
        return attributeCacheLazy().get(cacheKey, k -> loadAttributeValues(conceptSctid, attributeSctid));
    }

    @Override
    public SubsumptionOutcome subsumes(String ancestorSctid, String descendantSctid) {
        Map.Entry<String, String> pair = new AbstractMap.SimpleImmutableEntry<>(ancestorSctid, descendantSctid);
        return subsumesCacheLazy().get(pair, p -> loadSubsumes(p.getKey(), p.getValue()));
    }

    /* -------------------- lazy cache init -------------------- */

    private TtlCache<String, List<CodedConcept>> attributeCacheLazy() {
        TtlCache<String, List<CodedConcept>> local = attributeCache;
        if (local != null) return local;
        synchronized (this) {
            if (attributeCache == null) {
                attributeCache = new TtlCache<>(cacheTtlMillis(), cacheMaxEntries());
            }
            return attributeCache;
        }
    }

    private TtlCache<Map.Entry<String, String>, SubsumptionOutcome> subsumesCacheLazy() {
        TtlCache<Map.Entry<String, String>, SubsumptionOutcome> local = subsumesCache;
        if (local != null) return local;
        synchronized (this) {
            if (subsumesCache == null) {
                subsumesCache = new TtlCache<>(cacheTtlMillis(), cacheMaxEntries());
            }
            return subsumesCache;
        }
    }

    private long cacheTtlMillis() {
        return TimeUnit.SECONDS.toMillis(parseLong(
                gp(CdsHooksConstants.GP_CACHE_TTL_SECONDS), DEFAULT_TTL_SECONDS));
    }

    private int cacheMaxEntries() {
        long configured = parseLong(gp(CdsHooksConstants.GP_CACHE_MAX_ENTRIES),
                                    TtlCache.DEFAULT_MAX_ENTRIES);
        if (configured <= 0 || configured > Integer.MAX_VALUE) {
            return TtlCache.DEFAULT_MAX_ENTRIES;
        }
        return (int) configured;
    }

    /* -------------------- internals -------------------- */

    private List<CodedConcept> loadAttributeValues(String conceptSctid, String attributeSctid) {
        JsonNode body = fhirGet(baseUrl() + "/CodeSystem/$lookup"
                + "?system=" + enc(snomedSystem())
                + "&code=" + enc(conceptSctid));
        return parseAttributeValues(body, attributeSctid);
    }

    /** Pure function — exposed package-private for testing. */
    static List<CodedConcept> parseAttributeValues(JsonNode body, String attributeSctid) {
        if (body == null) return Collections.emptyList();
        List<CodedConcept> values = new ArrayList<>();
        for (JsonNode param : body.path("parameter")) {
            if (!"property".equals(param.path("name").asText())) continue;
            String code = null;
            String value = null;
            String description = null;
            for (JsonNode part : param.path("part")) {
                String partName = part.path("name").asText();
                if ("code".equals(partName)) {
                    code = part.path("valueCode").asText(null);
                } else if ("value".equals(partName)) {
                    value = part.path("valueCode").asText(null);
                } else if ("description".equals(partName)) {
                    description = part.path("valueString").asText(null);
                }
            }
            if (attributeSctid.equals(code) && value != null) {
                values.add(new CodedConcept(value, description));
            }
        }
        return values;
    }

    private SubsumptionOutcome loadSubsumes(String ancestor, String descendant) {
        JsonNode body = fhirGet(baseUrl() + "/CodeSystem/$subsumes"
                + "?system=" + enc(snomedSystem())
                + "&codeA=" + enc(ancestor)
                + "&codeB=" + enc(descendant));
        return parseSubsumesOutcome(body);
    }

    /** Pure function — exposed package-private for testing. */
    static SubsumptionOutcome parseSubsumesOutcome(JsonNode body) {
        if (body == null) return SubsumptionOutcome.UNKNOWN;
        for (JsonNode param : body.path("parameter")) {
            if ("outcome".equals(param.path("name").asText())) {
                return SubsumptionOutcome.fromFhirCode(param.path("valueCode").asText(null));
            }
        }
        return SubsumptionOutcome.UNKNOWN;
    }

    private JsonNode fhirGet(String url) {
        log.debug("SnowstormClient GET {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/fhir+json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return mapper.readTree(response.body());
            }
            // 4xx is a definitive "no data" answer from the terminology server
            // (concept doesn't exist, operation unsupported, etc.). Not a
            // failure of the server itself — return null so the caller treats
            // it as empty data.
            if (status >= 400 && status < 500) {
                log.debug("Terminology server returned {} for {} — treating as no data", status, url);
                return null;
            }
            // 5xx or anything else — server is unhealthy. Surface to caller so
            // the request handler can fail-open with an "unavailable" Card.
            log.warn("Terminology server returned {} for {}", status, url);
            throw new TerminologyUnavailableException("Terminology server returned " + status);
        } catch (java.io.IOException | InterruptedException e) {
            log.warn("Terminology call failed for {}: {}", url, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TerminologyUnavailableException("Terminology call failed: " + e.getMessage(), e);
        }
    }

    private String baseUrl() {
        String value = gp(CdsHooksConstants.GP_SNOWSTORM_URL);
        return value != null && !value.isBlank() ? value.trim() : "https://tx.fhir.org/r4";
    }

    private String snomedSystem() {
        String value = gp(CdsHooksConstants.GP_SNOMED_SYSTEM);
        return value != null && !value.isBlank() ? value.trim() : "http://snomed.info/sct";
    }

    /** Reads a global property defensively — never called during bean creation. */
    private static String gp(String key) {
        try {
            return Context.getAdministrationService().getGlobalProperty(key);
        } catch (Exception e) {
            log.debug("Could not read global property {}: {}", key, e.getMessage());
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
