/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CdsHooksConstants {

    private CdsHooksConstants() {}

    public static final String MODULE_ID = "cdshooks";

    public static final String GP_SNOWSTORM_URL = "cdshooks.snowstormUrl";
    public static final String GP_SNOMED_SYSTEM = "cdshooks.snomedSystem";
    public static final String GP_CACHE_TTL_SECONDS = "cdshooks.cacheTtlSeconds";
    public static final String GP_CACHE_MAX_ENTRIES = "cdshooks.cacheMaxEntries";

    /**
     * Selects the terminology source for parent-child / subsumption lookups:
     * {@code referenceMap} (default — local {@code concept_reference_term_map},
     * e.g. RxClass NUI ↔ RxNORM CUI class edges), {@code snowstorm} (live FHIR),
     * or {@code both}. The reference-map path is the primary, first-class
     * matcher; the SNOMED finding/product attribute bridge is a secondary,
     * "long-term completeness" augmentation (see {@link #SCTID_CAUSATIVE_AGENT}).
     */
    public static final String GP_TERMINOLOGY_BACKEND = "cdshooks.terminologyBackend";

    /** HMAC secret used to verify bearer-token (HS256) signatures. Empty disables bearer auth. */
    public static final String GP_BEARER_HMAC_SECRET = "cdshooks.bearerHmacSecret";

    /** Expected {@code iss} claim on bearer tokens. Empty disables issuer check. */
    public static final String GP_BEARER_EXPECTED_ISSUER = "cdshooks.bearerExpectedIssuer";

    /**
     * Comma-separated list of reference codes that are too broad to be useful
     * as a class-match ancestor. A class match whose matched ancestor is one of
     * these is suppressed to avoid alert fatigue (e.g. an allergen finding whose
     * causative agent is the root "Substance" would otherwise subsume — and warn
     * on — every drug). Empty value falls back to {@link #DEFAULT_EXCLUDED_CLASS_CODES}.
     */
    public static final String GP_CLASS_MATCH_EXCLUDED_CODES = "cdshooks.classMatchExcludedCodes";

    /**
     * Default overly-broad SNOMED CT roots suppressed from class matching.
     * Override/extend via {@link #GP_CLASS_MATCH_EXCLUDED_CODES}.
     */
    public static final Set<String> DEFAULT_EXCLUDED_CLASS_CODES;
    static {
        Set<String> roots = new LinkedHashSet<>();
        roots.add("105590001");  // Substance (root of the substance hierarchy)
        roots.add("373873005");  // Pharmaceutical / biologic product
        roots.add("763158003");  // Medicinal product
        roots.add("410942007");  // Drug or medicament
        DEFAULT_EXCLUDED_CLASS_CODES = Collections.unmodifiableSet(roots);
    }

    /**
     * SNOMED CT attribute: a finding's causative agent. Used only by the
     * <em>secondary</em> SNOMED finding→substance bridge — the primary path is
     * direct reference-code subsumption (RxNORM CUI → RxClass NUI). See
     * {@code AllergyMatcherImpl}.
     */
    public static final String SCTID_CAUSATIVE_AGENT = "246075003";

    /**
     * SNOMED CT attribute: a medicinal product's active ingredient. Used only by
     * the <em>secondary</em> SNOMED product→substance bridge; see
     * {@link #SCTID_CAUSATIVE_AGENT}.
     */
    public static final String SCTID_HAS_ACTIVE_INGREDIENT = "127489000";

    /** CDS-Hooks service identifier for the drug-allergy alert. */
    public static final String SERVICE_ID_DRUG_ALLERGY = "drug-allergy";

    /** CDS-Hooks hook name for medication prescribing. */
    public static final String HOOK_MEDICATION_PRESCRIBE = "medication-prescribe";
}
