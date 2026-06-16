/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.web.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openmrs.api.context.Context;
import org.openmrs.module.cdshooks.CdsHooksConstants;
import org.openmrs.module.cdshooks.api.CdsHooksService;
import org.openmrs.module.cdshooks.model.CdsHooksRequest;
import org.openmrs.module.cdshooks.model.CdsHooksResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CDS-Hooks 2.0 service host, packaged as an OpenMRS module servlet.
 *
 * <p>Mounted by OpenMRS at {@code /openmrs/ms/cdsServicesServlet} via the
 * {@code <servlet>} declaration in {@code config.xml}. A forwarding filter
 * for the spec-compliant {@code /ws/cds-services} URL is a follow-up.
 *
 * <ul>
 *   <li>{@code GET  /openmrs/ms/cdsServicesServlet}             — discovery</li>
 *   <li>{@code POST /openmrs/ms/cdsServicesServlet/{serviceId}} — invoke a specific service</li>
 * </ul>
 *
 * Reference: <a href="https://cds-hooks.hl7.org/2.0/">CDS-Hooks 2.0 specification</a>.
 */
public class CdsServicesServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(CdsServicesServlet.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // GET is always discovery. Path beyond the servlet name is ignored.
        // OpenMRS dispatches via ModuleServlet which preserves the original
        // pathInfo, so it will always include /cdsServicesServlet.
        writeDiscovery(resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // Invocation returns patient-specific allergy data, so it must be
        // authenticated. The ForwardingFilter only opens a session for a valid
        // Bearer token; session-cookie callers (the SPA) are bound by the core
        // OpenmrsFilter. Anything that reaches here without an authenticated
        // context is anonymous and must be rejected — otherwise a caller who
        // knows a patient UUID could enumerate recorded allergies by probing
        // drug codes. Discovery (doGet) stays public per the CDS-Hooks spec.
        if (!isAuthenticated()) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return;
        }

        String serviceId = lastPathSegment(req.getPathInfo());
        if (serviceId == null || serviceId.isEmpty() || "cdsServicesServlet".equals(serviceId)) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Service id required");
            return;
        }
        if (!CdsHooksConstants.SERVICE_ID_DRUG_ALLERGY.equals(serviceId)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown service: " + serviceId);
            return;
        }

        CdsHooksRequest request;
        try {
            request = mapper.readValue(req.getInputStream(), CdsHooksRequest.class);
        } catch (IOException e) {
            log.warn("Malformed CDS-Hooks request body: {}", e.getMessage());
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Malformed JSON");
            return;
        }

        CdsHooksService service = lookupService();
        if (service == null) {
            log.error("CdsHooksService bean not available");
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        CdsHooksResponse response = service.evaluateDrugAllergy(request);
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        mapper.writeValue(resp.getOutputStream(), response);
    }

    /* -------------------- helpers -------------------- */

    /**
     * Whether the current request carries an authenticated OpenMRS context.
     * Package-private and overridable so the auth gate can be unit-tested
     * without spinning up an OpenMRS context (mirrors the seam exposed in
     * {@code ForwardingFilter#verifyAndExtractSubject}).
     */
    boolean isAuthenticated() {
        return Context.isAuthenticated();
    }

    private void writeDiscovery(HttpServletResponse resp) throws IOException {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("hook", CdsHooksConstants.HOOK_MEDICATION_PRESCRIBE);
        service.put("title", "Drug-Allergy Alert");
        service.put("description",
                "Warns when an ordered drug conflicts with the patient's recorded "
                + "allergies (ingredient or class match via SNOMED CT).");
        service.put("id", CdsHooksConstants.SERVICE_ID_DRUG_ALLERGY);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("services", List.of(service));

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        mapper.writeValue(resp.getOutputStream(), body);
    }

    /** Returns the last non-empty path segment of a pathInfo like "/a/b/c" -> "c". */
    private static String lastPathSegment(String pathInfo) {
        if (pathInfo == null) return null;
        String[] parts = pathInfo.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isEmpty()) return parts[i];
        }
        return null;
    }

    private static CdsHooksService lookupService() {
        try {
            List<CdsHooksService> beans = Context.getRegisteredComponents(CdsHooksService.class);
            return beans.isEmpty() ? null : beans.get(0);
        } catch (Exception e) {
            log.error("Failed to look up CdsHooksService bean", e);
            return null;
        }
    }
}
