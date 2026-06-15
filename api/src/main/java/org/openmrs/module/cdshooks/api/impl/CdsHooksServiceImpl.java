/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.api.impl;

import org.openmrs.Allergies;
import org.openmrs.Allergy;
import org.openmrs.AllergyReaction;
import org.openmrs.Patient;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.api.CdsHooksService;
import org.openmrs.module.cdshooks.model.AllergyMatch;
import org.openmrs.module.cdshooks.model.CdsHooksRequest;
import org.openmrs.module.cdshooks.model.CdsHooksResponse;
import org.openmrs.util.PrivilegeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service("cdshooks.CdsHooksService")
public class CdsHooksServiceImpl implements CdsHooksService {

    private static final Logger log = LoggerFactory.getLogger(CdsHooksServiceImpl.class);

    @Autowired
    private PatientService patientService;

    @Autowired
    private AllergyMatcher matcher;

    @Autowired
    private CdsHooksRequestParser parser;

    @Autowired
    private ReferenceCodeExtractor referenceCodeExtractor;

    @Autowired
    private SeverityMapper severityMapper;

    @Autowired
    private CdsAuditLogger auditLogger;

    @Override
    public CdsHooksResponse evaluateDrugAllergy(CdsHooksRequest request) {
        CdsHooksResponse response = new CdsHooksResponse();
        String hookInstance = request == null ? null : request.getHookInstance();
        String patientUuid = parser.extractPatientUuid(request);
        List<AllergyMatcher.DrugInput> drugs = parser.extractDrugs(request);
        if (patientUuid == null || drugs.isEmpty()) {
            auditLogger.logInvocation(hookInstance, patientUuid, drugs, List.of(), CdsAuditLogger.Outcome.NO_DATA);
            return response;
        }

        // Grant the minimum read privileges this service needs for the duration
        // of the call. The production path is bearer-token auth that establishes
        // a real user context — see docs/IMPLEMENTATION_NOTES.md.
        boolean privilegesAdded = addProxyPrivileges();
        List<AllergyMatch> allMatches = new ArrayList<>();
        try {
            Patient patient = patientService.getPatientByUuid(patientUuid);
            if (patient == null) {
                auditLogger.logInvocation(hookInstance, patientUuid, drugs, List.of(), CdsAuditLogger.Outcome.NO_DATA);
                return response;
            }

            List<AllergyMatcher.AllergyInput> allergyInputs =
                    toAllergyInputs(patientService.getAllergies(patient));
            if (allergyInputs.isEmpty()) {
                auditLogger.logInvocation(hookInstance, patientUuid, drugs, List.of(), CdsAuditLogger.Outcome.SUCCESS);
                return response;
            }

            for (AllergyMatcher.DrugInput drug : drugs) {
                allMatches.addAll(matcher.match(drug, allergyInputs));
            }
        } catch (Exception e) {
            // Fail-open: when the algorithm can't run (terminology server
            // unreachable, bug in our code, etc.), tell the clinician the
            // check is unavailable rather than silently returning no Cards.
            // A silent absence would be interpreted as "no warnings" and
            // could mask a real allergy conflict.
            log.warn("CDS-Hooks drug-allergy evaluation failed for patient {}: {}",
                    patientUuid, e.getMessage());
            response.setCards(List.of(buildUnavailableCard(e)));
            auditLogger.logInvocation(hookInstance, patientUuid, drugs, List.of(), CdsAuditLogger.Outcome.UNAVAILABLE);
            return response;
        } finally {
            if (privilegesAdded) removeProxyPrivileges();
        }

        response.setCards(allMatches.stream().map(this::toCard).collect(Collectors.toList()));
        auditLogger.logInvocation(hookInstance, patientUuid, drugs, allMatches, CdsAuditLogger.Outcome.SUCCESS);
        return response;
    }

    private CdsHooksResponse.Card buildUnavailableCard(Exception cause) {
        CdsHooksResponse.Card card = new CdsHooksResponse.Card();
        card.uuid = UUID.randomUUID().toString();
        card.summary = "ℹ Allergy check unavailable";
        card.detail = "The drug-allergy check could not run. Verify recorded allergies "
                + "in the patient chart before proceeding with this order.";
        card.indicator = "info";
        CdsHooksResponse.Card.Source source = new CdsHooksResponse.Card.Source();
        source.label = "OpenMRS Drug-Allergy Alert";
        card.source = source;
        return card;
    }

    private static boolean addProxyPrivileges() {
        try {
            Context.addProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
            Context.addProxyPrivilege(PrivilegeConstants.GET_ALLERGIES);
            Context.addProxyPrivilege(PrivilegeConstants.GET_CONCEPTS);
            return true;
        } catch (Exception e) {
            // No user context — typical in unit tests. Skip privilege management.
            return false;
        }
    }

    private static void removeProxyPrivileges() {
        try {
            Context.removeProxyPrivilege(PrivilegeConstants.GET_CONCEPTS);
            Context.removeProxyPrivilege(PrivilegeConstants.GET_ALLERGIES);
            Context.removeProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private List<AllergyMatcher.AllergyInput> toAllergyInputs(Allergies allergies) {
        List<AllergyMatcher.AllergyInput> inputs = new ArrayList<>();
        if (allergies == null) return inputs;
        for (Allergy allergy : allergies) {
            if (allergy.getAllergen() == null || allergy.getAllergen().getCodedAllergen() == null) {
                continue; // free-text allergies cannot be matched algorithmically
            }
            List<String> codes = referenceCodeExtractor.extract(allergy.getAllergen().getCodedAllergen());
            if (codes.isEmpty()) continue;

            inputs.add(new AllergyMatcher.AllergyInput(
                    conceptDisplay(allergy.getAllergen().getCodedAllergen()),
                    codes,
                    severityMapper.map(allergy.getSeverity()),
                    firstReactionDisplay(allergy)));
        }
        return inputs;
    }

    private static String firstReactionDisplay(Allergy allergy) {
        List<AllergyReaction> reactions = allergy.getReactions();
        if (reactions == null || reactions.isEmpty()) return null;
        AllergyReaction r = reactions.get(0);
        if (r.getReaction() != null) return conceptDisplay(r.getReaction());
        return r.getReactionNonCoded();
    }

    /**
     * Locale-independent concept name accessor. Prefers an English name (the
     * canonical fully-specified language for CIEL); falls back to any
     * available name. Avoids {@link org.openmrs.Concept#getDisplayString()}
     * and the no-arg {@code getName()}, both of which call into the OpenMRS
     * service context for locale resolution.
     *
     * <p>TODO: pick up the OpenMRS default-locale global property at runtime
     * instead of hardcoding English. This is good enough for now.
     */
    private static String conceptDisplay(org.openmrs.Concept concept) {
        if (concept == null) return null;
        if (concept.getNames() != null) {
            String fallback = null;
            for (org.openmrs.ConceptName cn : concept.getNames()) {
                if (cn == null || cn.getName() == null) continue;
                if (cn.getLocale() != null && "en".equals(cn.getLocale().getLanguage())) {
                    return cn.getName();
                }
                if (fallback == null) fallback = cn.getName();
            }
            if (fallback != null) return fallback;
        }
        return concept.getUuid();
    }

    private CdsHooksResponse.Card toCard(AllergyMatch m) {
        CdsHooksResponse.Card card = new CdsHooksResponse.Card();
        card.uuid = UUID.randomUUID().toString();
        card.summary = "⚠ " + m.getAllergenDisplay()
                + (m.getMatchType() == AllergyMatch.MatchType.CLASS ? " (class match)" : "");
        card.detail = m.getExplanation()
                + (m.getReaction() != null ? "\n\nRecorded reaction: " + m.getReaction() : "");
        card.indicator = SeverityMapper.toCardIndicator(m.getSeverity());
        CdsHooksResponse.Card.Source source = new CdsHooksResponse.Card.Source();
        source.label = "OpenMRS Drug-Allergy Alert";
        card.source = source;
        return card;
    }
}
