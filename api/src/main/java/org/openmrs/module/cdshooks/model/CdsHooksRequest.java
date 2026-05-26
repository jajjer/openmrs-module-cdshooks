package org.openmrs.module.cdshooks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Minimal CDS-Hooks 2.0 request envelope. See
 * https://cds-hooks.hl7.org/2.0/#http-request_1 for the full schema.
 *
 * <p>Per the spec, the {@code context} object's shape varies by hook. For
 * {@code medication-prescribe}, it contains {@code userId}, {@code patientId},
 * {@code encounterId}, and {@code medications} (a Bundle of draft
 * MedicationRequests).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CdsHooksRequest {

    private String hook;
    private String hookInstance;
    private String fhirServer;
    private Map<String, Object> context;
    private Map<String, Object> prefetch;

    public String getHook() { return hook; }
    public void setHook(String hook) { this.hook = hook; }

    public String getHookInstance() { return hookInstance; }
    public void setHookInstance(String hookInstance) { this.hookInstance = hookInstance; }

    public String getFhirServer() { return fhirServer; }
    public void setFhirServer(String fhirServer) { this.fhirServer = fhirServer; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }

    public Map<String, Object> getPrefetch() { return prefetch; }
    public void setPrefetch(Map<String, Object> prefetch) { this.prefetch = prefetch; }
}
