package org.openmrs.module.cdshooks.web.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.module.cdshooks.CdsHooksConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
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
import java.nio.charset.StandardCharsets;

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
    private static final String BEARER_PREFIX = "Bearer ";

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

        // CDS-Hooks 2.0 bearer-token authentication (optional). When the
        // request carries an Authorization: Bearer header, we validate the
        // JWT here and open an OpenMRS session for the token's subject. If
        // the header is absent or has a different scheme, we pass through
        // so existing OpenMRS auth mechanisms (basic, session cookie) still
        // work.
        BearerAuthResult bearer = authenticateBearerIfPresent(req, res);
        if (bearer == BearerAuthResult.REJECTED) {
            return; // response already populated with 401
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

    /* -------------------- bearer-token auth -------------------- */

    private enum BearerAuthResult {
        ABSENT,        // no Authorization header or not Bearer scheme
        AUTHENTICATED, // valid token, OpenMRS session opened
        REJECTED       // token present but invalid — response already set
    }

    private BearerAuthResult authenticateBearerIfPresent(HttpServletRequest req,
                                                          HttpServletResponse res) throws IOException {
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null
                || !authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return BearerAuthResult.ABSENT;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        String secret = gp(CdsHooksConstants.GP_BEARER_HMAC_SECRET);
        if (secret == null || secret.isBlank()) {
            log.warn("Bearer token presented but {} is not configured; rejecting",
                    CdsHooksConstants.GP_BEARER_HMAC_SECRET);
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Bearer auth not configured");
            return BearerAuthResult.REJECTED;
        }

        String expectedIssuer = gp(CdsHooksConstants.GP_BEARER_EXPECTED_ISSUER);
        String username;
        try {
            username = verifyAndExtractSubject(token, secret, expectedIssuer);
        } catch (JwtException e) {
            log.warn("Rejected bearer token: {}", e.getMessage());
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
            return BearerAuthResult.REJECTED;
        }
        if (username == null || username.isBlank()) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token has no subject");
            return BearerAuthResult.REJECTED;
        }

        if (!openSessionAs(username)) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unknown user: " + username);
            return BearerAuthResult.REJECTED;
        }
        return BearerAuthResult.AUTHENTICATED;
    }

    /** Package-private for unit testing. */
    static String verifyAndExtractSubject(String token, String secret, String expectedIssuer) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Jws<Claims> parsed = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token);
        Claims claims = parsed.getBody();

        if (expectedIssuer != null && !expectedIssuer.isBlank()
                && !expectedIssuer.trim().equals(claims.getIssuer())) {
            throw new JwtException("Issuer mismatch: " + claims.getIssuer());
        }
        return claims.getSubject();
    }

    private static boolean openSessionAs(String username) {
        try {
            Context.openSession();
            User user = Context.getUserService().getUserByUsername(username);
            if (user == null) {
                Context.closeSession();
                return false;
            }
            Context.becomeUser(user.getSystemId());
            return true;
        } catch (Exception e) {
            log.warn("Failed to open session for {}: {}", username, e.getMessage());
            try { Context.closeSession(); } catch (Exception ignored) {}
            return false;
        }
    }

    private static String gp(String key) {
        try {
            return Context.getAdministrationService().getGlobalProperty(key);
        } catch (Exception e) {
            return null;
        }
    }
}
