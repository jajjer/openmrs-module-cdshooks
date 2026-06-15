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
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptReferenceTermMap;
import org.openmrs.ConceptSource;
import org.openmrs.api.ConceptService;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code concept_reference_term_map} backend answers parent-child
 * questions from locally loaded reference-term edges — the RxClass NUI/CUI
 * class relationships the reference-map path is built on.
 *
 * <p>Worked example mirrors the SNOMED matching example but with RxNorm/RxClass codes:
 * amoxicillin (RxNORM CUI) is {@code NARROWER-THAN} penicillins (RxClass NUI),
 * so an amoxicillin order should subsume into a penicillin allergy class.
 */
@RunWith(MockitoJUnitRunner.class)
public class ConceptReferenceTermMapBackendTest {

    private static final String CUI_AMOXICILLIN = "723";          // RxNORM ingredient CUI
    private static final String NUI_PENICILLINS = "N0000175503";  // RxClass class NUI
    private static final String CUI_CEFAZOLIN = "2180";           // unrelated ingredient
    private static final String CUI_AMOXICILLIN_SNOMED = "372687004"; // a SAME-AS duplicate term

    private final ConceptSource rxnorm = source("RxNORM");
    private final ConceptSource rxclass = source("RxClass");

    private final ConceptMapType narrowerThan = mapType("NARROWER-THAN");
    private final ConceptMapType broaderThan = mapType("BROADER-THAN");
    private final ConceptMapType sameAs = mapType("SAME-AS");

    @Mock
    private ConceptService conceptService;

    @InjectMocks
    private ConceptReferenceTermMapBackend backend;

    private ConceptReferenceTerm amoxicillin;
    private ConceptReferenceTerm penicillins;
    private ConceptReferenceTerm cefazolin;

    @Before
    public void setUp() {
        amoxicillin = term(CUI_AMOXICILLIN, rxnorm);
        penicillins = term(NUI_PENICILLINS, rxclass);
        cefazolin = term(CUI_CEFAZOLIN, rxnorm);

        // amoxicillin --NARROWER-THAN--> penicillins
        link(amoxicillin, penicillins, narrowerThan);

        stubResolve(amoxicillin);
        stubResolve(penicillins);
        stubResolve(cefazolin);

        // Default: no incoming (term-as-B) edges unless a test wires them.
        when(conceptService.getReferenceTermMappingsTo(any())).thenReturn(Collections.emptyList());
    }

    @Test
    public void classMatch_amoxicillinNarrowerThanPenicillins_subsumes() {
        SubsumptionOutcome outcome = backend.subsumes(NUI_PENICILLINS, CUI_AMOXICILLIN);
        assertThat(outcome, is(SubsumptionOutcome.SUBSUMES));
    }

    @Test
    public void noEdge_unrelatedIngredient_notSubsumed() {
        SubsumptionOutcome outcome = backend.subsumes(NUI_PENICILLINS, CUI_CEFAZOLIN);
        assertThat(outcome, is(SubsumptionOutcome.NOT_SUBSUMED));
    }

    @Test
    public void sameCode_isEquivalent() {
        SubsumptionOutcome outcome = backend.subsumes(CUI_AMOXICILLIN, CUI_AMOXICILLIN);
        assertThat(outcome, is(SubsumptionOutcome.EQUIVALENT));
    }

    @Test
    public void unknownCode_returnsUnknown() {
        // A code absent from concept_reference_term_map: backend has no opinion,
        // so a router can fall through to another source rather than assume safe.
        when(conceptService.getConceptReferenceTerms(eq("99999"), any(), any(), any(), anyBoolean()))
                .thenReturn(Collections.emptyList());
        SubsumptionOutcome outcome = backend.subsumes(NUI_PENICILLINS, "99999");
        assertThat(outcome, is(SubsumptionOutcome.UNKNOWN));
    }

    @Test
    public void broaderThanEdge_traversedViaIncomingMap_subsumes() {
        // Model the same relationship from the other direction:
        // penicillins --BROADER-THAN--> amoxicillin, stored as an incoming map on amoxicillin.
        ConceptReferenceTerm amox = term("AMOX2", rxnorm);
        ConceptReferenceTerm pen = term("PEN2", rxclass);
        ConceptReferenceTermMap incoming = rawMap(pen, amox, broaderThan);
        when(conceptService.getReferenceTermMappingsTo(amox)).thenReturn(List.of(incoming));
        stubResolve(amox);
        stubResolve(pen);

        assertThat(backend.subsumes("PEN2", "AMOX2"), is(SubsumptionOutcome.SUBSUMES));
    }

    @Test
    public void sameAsHop_thenClassEdge_subsumes() {
        // cefazolin has no direct class edge of its own; it is only SAME-AS a
        // duplicate term that is NARROWER-THAN penicillins. Reaching the class
        // through the equivalence hop must still count as a (strict) subsumption.
        ConceptReferenceTerm cefazolinDup = term(CUI_AMOXICILLIN_SNOMED, source("SNOMED CT"));
        link(cefazolin, cefazolinDup, sameAs);
        link(cefazolinDup, penicillins, narrowerThan);

        assertThat(backend.subsumes(NUI_PENICILLINS, CUI_CEFAZOLIN), is(SubsumptionOutcome.SUBSUMES));
    }

    @Test
    public void getAttributeValues_alwaysEmpty() {
        assertThat(backend.getAttributeValues(CUI_AMOXICILLIN, "127489000"), is(empty()));
    }

    /* -------------------- helpers -------------------- */

    private void stubResolve(ConceptReferenceTerm term) {
        stubResolveTerm(term.getCode(), term);
    }

    private void stubResolveTerm(String code, ConceptReferenceTerm term) {
        when(conceptService.getConceptReferenceTerms(eq(code), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(term));
    }

    private static void link(ConceptReferenceTerm a, ConceptReferenceTerm b, ConceptMapType type) {
        ConceptReferenceTermMap map = new ConceptReferenceTermMap();
        map.setTermB(b);
        map.setConceptMapType(type);
        a.addConceptReferenceTermMap(map); // sets termA = a and adds to a's outgoing set
    }

    private static ConceptReferenceTermMap rawMap(ConceptReferenceTerm a, ConceptReferenceTerm b,
                                                  ConceptMapType type) {
        ConceptReferenceTermMap map = new ConceptReferenceTermMap();
        map.setTermA(a);
        map.setTermB(b);
        map.setConceptMapType(type);
        return map;
    }

    private static ConceptReferenceTerm term(String code, ConceptSource src) {
        ConceptReferenceTerm t = new ConceptReferenceTerm();
        t.setCode(code);
        t.setConceptSource(src);
        t.setRetired(false);
        return t;
    }

    private static ConceptSource source(String name) {
        ConceptSource s = new ConceptSource();
        s.setName(name);
        return s;
    }

    private static ConceptMapType mapType(String name) {
        ConceptMapType t = new ConceptMapType();
        t.setName(name);
        return t;
    }
}
