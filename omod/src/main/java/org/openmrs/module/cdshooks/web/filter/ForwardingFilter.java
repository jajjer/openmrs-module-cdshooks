package org.openmrs.module.cdshooks.web.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Forwards public CDS-Hooks URLs ({@code /openmrs/ws/cds-services[/...]}) to the
 * underlying module servlet at {@code /openmrs/ms/cdsServicesServlet[/...]}.
 *
 * <p>Modeled after {@code openmrs-module-fhir2}'s {@code ForwardingFilter}. The
 * spec-compliant URL ({@code /cds-services} per
 * <a href="https://cds-hooks.hl7.org/2.0/">CDS-Hooks 2.0</a>) is what external
 * clients should call. The {@code /ws/} prefix is already CSRF-exempt in
 * OpenMRS (see {@code csrfguard.properties}: {@code unprotected.WS}), which
 * means POSTs work without additional configuration. Internally the request is
 * forwarded to {@code /ms/cdsServicesServlet}, where the servlet declared in
 * {@code config.xml} actually lives.
 */
public class ForwardingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ForwardingFilter.class);

    private static final String PUBLIC_PREFIX = "/ws/cds-services";
    private static final String INTERNAL_PREFIX = "/ms/cdsServicesServlet";

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String requestURI = req.getRequestURI();
        String contextPath = req.getContextPath();
        String prefix = contextPath + PUBLIC_PREFIX;

        if (!requestURI.startsWith(prefix)) {
            // Not for us; pass through.
            chain.doFilter(req, res);
            return;
        }

        String suffix = requestURI.substring(prefix.length());
        String newURI = contextPath + INTERNAL_PREFIX + suffix;

        log.debug("ForwardingFilter rewriting {} -> {}", requestURI, newURI);

        // Strip the context path before dispatching — RequestDispatcher.forward
        // expects a path relative to the servlet context.
        String dispatchPath = newURI.substring(contextPath.length());
        RequestDispatcher dispatcher = req.getRequestDispatcher(dispatchPath);
        if (dispatcher == null) {
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        dispatcher.forward(req, res);
    }
}
