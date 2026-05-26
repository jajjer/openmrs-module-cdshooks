package org.openmrs.module.cdshooks.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openmrs.api.context.Context;
import org.openmrs.module.cdshooks.CdsHooksConstants;
import org.openmrs.module.cdshooks.model.SnomedConcept;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;
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
@Component
public class SnowstormClient {

    private static final long DEFAULT_TTL_SECONDS = 3600L;

    private static final Logger log = LoggerFactory.getLogger(SnowstormClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile TtlCache<String, List<SnomedConcept>> attributeCache;
    private volatile TtlCache<Map.Entry<String, String>, SubsumptionOutcome> subsumesCache;

    /**
     * Returns the values of a given SNOMED attribute on a concept — e.g. all
     * causative-agent values on a finding, or all active-ingredient values on
     * a product. Empty list if the attribute is absent.
     */
    public List<SnomedConcept> getAttributeValues(String conceptSctid, String attributeSctid) {
        String cacheKey = conceptSctid + "|" + attributeSctid;
        return attributeCacheLazy().get(cacheKey, k -> loadAttributeValues(conceptSctid, attributeSctid));
    }

    public SubsumptionOutcome subsumes(String ancestorSctid, String descendantSctid) {
        Map.Entry<String, String> pair = new AbstractMap.SimpleImmutableEntry<>(ancestorSctid, descendantSctid);
        return subsumesCacheLazy().get(pair, p -> loadSubsumes(p.getKey(), p.getValue()));
    }

    /* -------------------- lazy cache init -------------------- */

    private TtlCache<String, List<SnomedConcept>> attributeCacheLazy() {
        TtlCache<String, List<SnomedConcept>> local = attributeCache;
        if (local != null) return local;
        synchronized (this) {
            if (attributeCache == null) {
                attributeCache = new TtlCache<>(cacheTtlMillis());
            }
            return attributeCache;
        }
    }

    private TtlCache<Map.Entry<String, String>, SubsumptionOutcome> subsumesCacheLazy() {
        TtlCache<Map.Entry<String, String>, SubsumptionOutcome> local = subsumesCache;
        if (local != null) return local;
        synchronized (this) {
            if (subsumesCache == null) {
                subsumesCache = new TtlCache<>(cacheTtlMillis());
            }
            return subsumesCache;
        }
    }

    private long cacheTtlMillis() {
        return TimeUnit.SECONDS.toMillis(parseLong(
                gp(CdsHooksConstants.GP_CACHE_TTL_SECONDS), DEFAULT_TTL_SECONDS));
    }

    /* -------------------- internals -------------------- */

    private List<SnomedConcept> loadAttributeValues(String conceptSctid, String attributeSctid) {
        JsonNode body = fhirGet(baseUrl() + "/CodeSystem/$lookup"
                + "?system=" + enc(snomedSystem())
                + "&code=" + enc(conceptSctid));
        return parseAttributeValues(body, attributeSctid);
    }

    /** Pure function — exposed package-private for testing. */
    static List<SnomedConcept> parseAttributeValues(JsonNode body, String attributeSctid) {
        if (body == null) return Collections.emptyList();
        List<SnomedConcept> values = new ArrayList<>();
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
                values.add(new SnomedConcept(value, description));
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/fhir+json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return mapper.readTree(response.body());
            }
            log.warn("Terminology server returned {} for {}", response.statusCode(), url);
        } catch (Exception e) {
            log.warn("Terminology call failed for {}: {}", url, e.getMessage());
            Thread.currentThread().interrupt();
        }
        return null;
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
