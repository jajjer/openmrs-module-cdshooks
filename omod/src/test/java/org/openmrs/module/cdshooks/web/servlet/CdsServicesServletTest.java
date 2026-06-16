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

import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: ISSUE-001 — unauthenticated PHI disclosure via the CDS-Hooks
 * invocation endpoint.
 * Found by /qa on 2026-06-15.
 * Report: .gstack/qa-reports/qa-report-localhost-8081-2026-06-15.md
 *
 * <p>POST /ws/cds-services/drug-allergy used to return a patient's recorded
 * allergy cards with no authentication, because the servlet never checked the
 * OpenMRS context (the ForwardingFilter only authenticates Bearer tokens).
 * doPost must reject anonymous callers with 401 before reading any request
 * body or touching patient data.
 *
 * <p>The auth check is exercised through the package-private {@code
 * isAuthenticated()} seam so no OpenMRS context or servlet container is needed.
 */
public class CdsServicesServletTest {

    /** A servlet whose auth gate is forced to a fixed answer. */
    private static CdsServicesServlet servletWithAuth(final boolean authenticated) {
        return new CdsServicesServlet() {
            @Override
            boolean isAuthenticated() {
                return authenticated;
            }
        };
    }

    @Test
    public void doPost_anonymous_returns401_andNeverReadsBody() throws Exception {
        CdsServicesServlet servlet = servletWithAuth(false);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        servlet.doPost(req, resp);

        // Rejected with 401 ...
        verify(resp).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
        // ... before any attempt to parse the path or read the request body,
        // so an anonymous caller learns nothing about valid service ids or data.
        verify(req, never()).getPathInfo();
        verify(req, never()).getInputStream();
    }

    @Test
    public void doPost_authenticated_passesGate_andValidatesServiceId() throws Exception {
        // When authenticated, the gate lets the request through to service-id
        // validation. A request with no service id is a 400 — proving the gate
        // did not short-circuit the authenticated path.
        CdsServicesServlet servlet = servletWithAuth(true);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(req.getPathInfo()).thenReturn("/cdsServicesServlet");

        servlet.doPost(req, resp);

        // A 400 (not a 401) proves the authenticated request cleared the gate
        // and reached service-id validation.
        verify(resp).sendError(HttpServletResponse.SC_BAD_REQUEST, "Service id required");
    }
}
