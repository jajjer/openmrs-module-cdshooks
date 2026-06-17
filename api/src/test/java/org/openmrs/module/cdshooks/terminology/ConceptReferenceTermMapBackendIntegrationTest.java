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

import org.junit.Before;
import org.junit.Test;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptReferenceTermMap;
import org.openmrs.ConceptSource;
import org.openmrs.api.ConceptService;
import org.openmrs.api.context.Context;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;
import org.openmrs.test.BaseModuleContextSensitiveTest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Context-sensitive integration test for {@link ConceptReferenceTermMapBackend}:
 * it exercises the subsumption walk against a real OpenMRS {@link ConceptService}
 * and the in-memory H2 schema, rather than mocks. This is the test that proves
 * the backend works against actual {@code concept_reference_term_map} persistence
 * — the RxClass NUI/CUI class edges the reference-map path relies on.
 *
 * <p>Builds its own fixture via the API (no fragile hardcoded standard-dataset
 * IDs): an RxNORM ingredient CUI mapped {@code NARROWER-THAN} an RxClass class
 * NUI, then asserts the class subsumption resolves.
 *
 * <p>Requires the full OpenMRS test harness (Java 8/11, openmrs-test); run via
 * {@code mvn test}.
 */
public class ConceptReferenceTermMapBackendIntegrationTest extends BaseModuleContextSensitiveTest {

    private static final String CUI_AMOXICILLIN = "723";
    private static final String NUI_PENICILLINS = "N0000175497";

    private ConceptReferenceTermMapBackend backend;

    @Before
    public void setUp() {
        ConceptService cs = Context.getConceptService();

        backend = new ConceptReferenceTermMapBackend();
        backend.setConceptService(cs);

        ConceptSource rxnorm = ensureSource(cs, "RxNORM");
        ConceptSource rxclass = ensureSource(cs, "RxClass");
        ConceptMapType narrowerThan = ensureMapType(cs, "NARROWER-THAN");

        ConceptReferenceTerm amoxicillin = ensureTerm(cs, CUI_AMOXICILLIN, rxnorm, "amoxicillin");
        ConceptReferenceTerm penicillins = ensureTerm(cs, NUI_PENICILLINS, rxclass, "Penicillins");

        // amoxicillin --NARROWER-THAN--> penicillins
        ConceptReferenceTermMap map = new ConceptReferenceTermMap();
        map.setTermB(penicillins);
        map.setConceptMapType(narrowerThan);
        amoxicillin.addConceptReferenceTermMap(map);
        cs.saveConceptReferenceTerm(amoxicillin);

        Context.flushSession();
    }

    @Test
    public void classMatch_resolvesFromReferenceTermMap() {
        assertThat(backend.subsumes(NUI_PENICILLINS, CUI_AMOXICILLIN),
                is(SubsumptionOutcome.SUBSUMES));
    }

    @Test
    public void sameTerm_isEquivalent() {
        assertThat(backend.subsumes(CUI_AMOXICILLIN, CUI_AMOXICILLIN),
                is(SubsumptionOutcome.EQUIVALENT));
    }

    @Test
    public void unmappedCode_isUnknown() {
        assertThat(backend.subsumes(NUI_PENICILLINS, "no-such-code-999999"),
                is(SubsumptionOutcome.UNKNOWN));
    }

    @Test
    public void unrelatedDirection_notSubsumed() {
        // The drug does not subsume its own class.
        assertThat(backend.subsumes(CUI_AMOXICILLIN, NUI_PENICILLINS),
                is(SubsumptionOutcome.NOT_SUBSUMED));
    }

    /* -------------------- fixture helpers -------------------- */

    private static ConceptSource ensureSource(ConceptService cs, String name) {
        ConceptSource existing = cs.getConceptSourceByName(name);
        if (existing != null) {
            return existing;
        }
        ConceptSource source = new ConceptSource();
        source.setName(name);
        source.setDescription(name + " (test)");
        return cs.saveConceptSource(source);
    }

    private static ConceptMapType ensureMapType(ConceptService cs, String name) {
        ConceptMapType existing = cs.getConceptMapTypeByName(name);
        if (existing != null) {
            return existing;
        }
        ConceptMapType type = new ConceptMapType();
        type.setName(name);
        return cs.saveConceptMapType(type);
    }

    private static ConceptReferenceTerm ensureTerm(ConceptService cs, String code,
                                                   ConceptSource source, String name) {
        ConceptReferenceTerm existing = cs.getConceptReferenceTermByCode(code, source);
        if (existing != null) {
            return existing;
        }
        ConceptReferenceTerm term = new ConceptReferenceTerm();
        term.setCode(code);
        term.setConceptSource(source);
        term.setName(name);
        return cs.saveConceptReferenceTerm(term);
    }
}
