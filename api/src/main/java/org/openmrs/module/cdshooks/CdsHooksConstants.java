package org.openmrs.module.cdshooks;

public final class CdsHooksConstants {

    private CdsHooksConstants() {}

    public static final String MODULE_ID = "cdshooks";

    public static final String GP_SNOWSTORM_URL = "cdshooks.snowstormUrl";
    public static final String GP_SNOMED_SYSTEM = "cdshooks.snomedSystem";
    public static final String GP_CACHE_TTL_SECONDS = "cdshooks.cacheTtlSeconds";

    /** HMAC secret used to verify bearer-token (HS256) signatures. Empty disables bearer auth. */
    public static final String GP_BEARER_HMAC_SECRET = "cdshooks.bearerHmacSecret";

    /** Expected {@code iss} claim on bearer tokens. Empty disables issuer check. */
    public static final String GP_BEARER_EXPECTED_ISSUER = "cdshooks.bearerExpectedIssuer";

    /** SNOMED CT attribute: a finding's causative agent. */
    public static final String SCTID_CAUSATIVE_AGENT = "246075003";

    /** SNOMED CT attribute: a medicinal product's active ingredient. */
    public static final String SCTID_HAS_ACTIVE_INGREDIENT = "127489000";

    /** CDS-Hooks service identifier for the drug-allergy alert. */
    public static final String SERVICE_ID_DRUG_ALLERGY = "drug-allergy";

    /** CDS-Hooks hook name for medication prescribing. */
    public static final String HOOK_MEDICATION_PRESCRIBE = "medication-prescribe";
}
