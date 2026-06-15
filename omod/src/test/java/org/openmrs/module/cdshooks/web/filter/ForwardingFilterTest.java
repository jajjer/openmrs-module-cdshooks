/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.web.filter;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

/**
 * Tests the JWT verification path of ForwardingFilter without spinning up
 * the OpenMRS context or a servlet container. The {@link
 * ForwardingFilter#verifyAndExtractSubject} method is exposed
 * package-private specifically for this purpose.
 */
public class ForwardingFilterTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final String OTHER_SECRET = "different-different-different-different-different";
    private static final String ISSUER = "https://openmrs.example.org/cds-hooks";

    @Test
    public void validToken_returnsSubject() {
        String token = mintToken(SECRET, "clinician-jane", ISSUER, plusHours(1));
        assertThat(ForwardingFilter.verifyAndExtractSubject(token, SECRET, ISSUER), is("clinician-jane"));
    }

    @Test
    public void validToken_noIssuerCheck_returnsSubject() {
        String token = mintToken(SECRET, "clinician-jane", ISSUER, plusHours(1));
        assertThat(ForwardingFilter.verifyAndExtractSubject(token, SECRET, null), is("clinician-jane"));
        assertThat(ForwardingFilter.verifyAndExtractSubject(token, SECRET, ""), is("clinician-jane"));
    }

    @Test
    public void badSignature_throws() {
        String token = mintToken(SECRET, "clinician", ISSUER, plusHours(1));
        assertThrows(JwtException.class,
                () -> ForwardingFilter.verifyAndExtractSubject(token, OTHER_SECRET, ISSUER));
    }

    @Test
    public void expiredToken_throws() {
        String token = mintToken(SECRET, "clinician", ISSUER, plusHours(-1));
        assertThrows(JwtException.class,
                () -> ForwardingFilter.verifyAndExtractSubject(token, SECRET, ISSUER));
    }

    @Test
    public void issuerMismatch_throws() {
        String token = mintToken(SECRET, "clinician", "https://attacker.example.com", plusHours(1));
        assertThrows(JwtException.class,
                () -> ForwardingFilter.verifyAndExtractSubject(token, SECRET, ISSUER));
    }

    @Test
    public void malformedToken_throws() {
        assertThrows(JwtException.class,
                () -> ForwardingFilter.verifyAndExtractSubject("not-a-jwt", SECRET, ISSUER));
    }

    @Test
    public void tokenWithoutSubject_returnsNull() {
        String token = Jwts.builder()
                .setIssuer(ISSUER)
                .setExpiration(plusHours(1))
                .signWith(key(SECRET))
                .compact();
        assertThat(ForwardingFilter.verifyAndExtractSubject(token, SECRET, ISSUER), is(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    public void tamperedClaims_throws() {
        // Mint a valid token, then mutate one character of the body.
        String token = mintToken(SECRET, "clinician-jane", ISSUER, plusHours(1));
        String[] parts = token.split("\\.");
        assertThat(parts.length, is(3));
        String tampered = parts[0] + "." + (parts[1].charAt(0) == 'A' ? "B" : "A") + parts[1].substring(1) + "." + parts[2];
        assertThrows(JwtException.class,
                () -> ForwardingFilter.verifyAndExtractSubject(tampered, SECRET, ISSUER));
    }

    /* -------------------- helpers -------------------- */

    private static String mintToken(String secret, String subject, String issuer, Date expiry) {
        return Jwts.builder()
                .setSubject(subject)
                .setIssuer(issuer)
                .setIssuedAt(new Date())
                .setExpiration(expiry)
                .signWith(key(secret))
                .compact();
    }

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static Date plusHours(int hours) {
        return new Date(System.currentTimeMillis() + hours * 3600L * 1000L);
    }
}
