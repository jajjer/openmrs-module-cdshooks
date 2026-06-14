/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.terminology;

import org.openmrs.api.context.Context;
import org.openmrs.module.cdshooks.CdsHooksConstants;
import org.openmrs.module.cdshooks.client.SnowstormClient;
import org.openmrs.module.cdshooks.model.CodedConcept;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link TerminologyBackend} the matcher actually talks to. It selects
 * among the concrete backends per the {@code cdshooks.terminologyBackend}
 * global property, read defensively on each call so a deployment can switch
 * sources without a module restart:
 *
 * <ul>
 *   <li>{@code snowstorm} (default) — live FHIR {@code $lookup}/{@code $subsumes}
 *       against a Snowstorm-compatible server. Backward-compatible with the
 *       original behaviour.</li>
 *   <li>{@code referenceMap} — local {@code concept_reference_term_map} lookups
 *       only. No terminology server required.</li>
 *   <li>{@code both} — ask the local reference map first; if it has no
 *       opinion (or says no), confirm against Snowstorm. Any positive
 *       (ancestry-indicating) answer from either source wins, because a missed
 *       drug-allergy conflict is the costliest error.</li>
 * </ul>
 */
@Component(TerminologyBackendRouter.BEAN_NAME)
public class TerminologyBackendRouter implements TerminologyBackend {

    public static final String BEAN_NAME = "cdshooks.terminologyBackend";

    private static final Logger log = LoggerFactory.getLogger(TerminologyBackendRouter.class);

    @Autowired
    @Qualifier(SnowstormClient.BEAN_NAME)
    private TerminologyBackend snowstorm;

    @Autowired
    @Qualifier(ConceptReferenceTermMapBackend.BEAN_NAME)
    private TerminologyBackend referenceMap;

    @Override
    public String name() {
        return "router(" + mode() + ")";
    }

    @Override
    public List<CodedConcept> getAttributeValues(String conceptCode, String attributeCode) {
        switch (mode()) {
            case REFERENCE_MAP:
                return referenceMap.getAttributeValues(conceptCode, attributeCode);
            case BOTH: {
                // referenceMap exposes no attribute relationships, but keep the
                // union shape so a future map-backed attribute bridge composes.
                List<CodedConcept> merged = new ArrayList<>(
                        snowstorm.getAttributeValues(conceptCode, attributeCode));
                for (CodedConcept c : referenceMap.getAttributeValues(conceptCode, attributeCode)) {
                    if (!merged.contains(c)) {
                        merged.add(c);
                    }
                }
                return merged;
            }
            case SNOWSTORM:
            default:
                return snowstorm.getAttributeValues(conceptCode, attributeCode);
        }
    }

    @Override
    public SubsumptionOutcome subsumes(String ancestorCode, String descendantCode) {
        switch (mode()) {
            case REFERENCE_MAP:
                return referenceMap.subsumes(ancestorCode, descendantCode);
            case BOTH: {
                SubsumptionOutcome local = referenceMap.subsumes(ancestorCode, descendantCode);
                if (local.indicatesAncestry()) {
                    return local;
                }
                SubsumptionOutcome remote = snowstorm.subsumes(ancestorCode, descendantCode);
                if (remote.indicatesAncestry()) {
                    return remote;
                }
                // Neither found ancestry. Prefer a definite "no" over "unknown".
                return local != SubsumptionOutcome.UNKNOWN ? local : remote;
            }
            case SNOWSTORM:
            default:
                return snowstorm.subsumes(ancestorCode, descendantCode);
        }
    }

    @Override
    public String displayFor(String code) {
        switch (mode()) {
            case REFERENCE_MAP:
                return referenceMap.displayFor(code);
            case BOTH: {
                String label = referenceMap.displayFor(code);
                return label != null ? label : snowstorm.displayFor(code);
            }
            case SNOWSTORM:
            default:
                return snowstorm.displayFor(code);
        }
    }

    /* -------------------- mode selection -------------------- */

    private enum Mode { SNOWSTORM, REFERENCE_MAP, BOTH }

    private Mode mode() {
        String configured = configuredBackend();
        if (configured == null || configured.isBlank()) {
            return Mode.SNOWSTORM;
        }
        switch (configured.trim().toLowerCase()) {
            case "referencemap":
            case "reference_map":
            case "reference-map":
                return Mode.REFERENCE_MAP;
            case "both":
                return Mode.BOTH;
            case "snowstorm":
                return Mode.SNOWSTORM;
            default:
                log.warn("Unrecognised {} value '{}'; defaulting to snowstorm",
                        CdsHooksConstants.GP_TERMINOLOGY_BACKEND, configured);
                return Mode.SNOWSTORM;
        }
    }

    /**
     * The raw {@code cdshooks.terminologyBackend} value. Package-private seam so
     * tests can drive mode selection without an OpenMRS service context. Read
     * defensively; an unreadable property defaults to {@code snowstorm}.
     */
    String configuredBackend() {
        try {
            return Context.getAdministrationService()
                    .getGlobalProperty(CdsHooksConstants.GP_TERMINOLOGY_BACKEND);
        } catch (Exception | LinkageError e) {
            log.debug("Could not read global property {}: {}",
                    CdsHooksConstants.GP_TERMINOLOGY_BACKEND, e.getMessage());
            return null;
        }
    }

    /* -------------------- test seams -------------------- */

    void setSnowstorm(TerminologyBackend snowstorm) {
        this.snowstorm = snowstorm;
    }

    void setReferenceMap(TerminologyBackend referenceMap) {
        this.referenceMap = referenceMap;
    }
}
