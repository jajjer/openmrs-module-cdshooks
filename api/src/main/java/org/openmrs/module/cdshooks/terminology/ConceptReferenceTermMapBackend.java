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

import org.openmrs.ConceptMapType;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptReferenceTermMap;
import org.openmrs.api.ConceptService;
import org.openmrs.api.context.Context;
import org.openmrs.module.cdshooks.CdsHooksConstants;
import org.openmrs.module.cdshooks.client.TtlCache;
import org.openmrs.module.cdshooks.model.CodedConcept;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A {@link TerminologyBackend} that answers parent-child questions from the
 * OpenMRS {@code concept_reference_term_map} table instead of a live
 * terminology server.
 *
 * <p>This is the "service which can use that knowledge to provide the
 * parent-child links" that Andrew Kanter described on the Talk thread. The
 * intended data shape is RxClass class edges loaded into
 * {@code concept_reference_term_map}: an RxNORM ingredient CUI mapped
 * {@code NARROWER-THAN} an RxClass NUI (the drug class), e.g.
 * <em>amoxicillin (CUI) NARROWER-THAN penicillins (NUI)</em>. The same
 * mechanism works for any reference source whose hierarchy is loaded as
 * {@code NARROWER-THAN} / {@code BROADER-THAN} / {@code SAME-AS} edges
 * (including SNOMED is-a edges, if a deployment prefers to pre-load them
 * rather than call Snowstorm).
 *
 * <h2>Subsumption</h2>
 * {@link #subsumes(String, String)} resolves both codes to reference terms and
 * walks the "broader" closure of the descendant: following {@code NARROWER-THAN}
 * / {@code IS-A} edges upward and {@code BROADER-THAN} edges in reverse, while
 * treating {@code SAME-AS} as a zero-cost equivalence hop. If the ancestor is
 * reached, the answer is {@link SubsumptionOutcome#SUBSUMES}.
 *
 * <h2>Attribute bridging</h2>
 * {@link #getAttributeValues(String, String)} returns empty: the reference-map
 * model has no equivalent of SNOMED's {@code Causative agent} /
 * {@code Has active ingredient} attribute relationships. The matcher's
 * include-self fallback ({@code AllergyMatcherImpl.expandToSubstances}) then
 * compares the drug and allergen codes directly via {@link #subsumes}, which
 * is exactly the CUI→NUI class lookup this backend is built for.
 */
@Component(ConceptReferenceTermMapBackend.BEAN_NAME)
public class ConceptReferenceTermMapBackend implements TerminologyBackend {

    public static final String BEAN_NAME = "cdshooks.referenceMapBackend";

    /** Backend identifier for the {@code cdshooks.terminologyBackend} selector. */
    public static final String BACKEND_NAME = "referenceMap";

    /** Map types whose A→B direction means "A is narrower than (a child of) B". */
    private static final Set<String> NARROWER_TYPES = new HashSet<>(Arrays.asList(
            "NARROWER-THAN", "IS-A"));

    private static final String BROADER_TYPE = "BROADER-THAN";
    private static final String SAME_AS_TYPE = "SAME-AS";

    /** Defensive bound on how many terms a single subsumption walk will visit. */
    private static final int MAX_VISITED = 5000;

    private static final long DEFAULT_TTL_SECONDS = 3600L;

    private static final Logger log = LoggerFactory.getLogger(ConceptReferenceTermMapBackend.class);

    @Autowired
    private ConceptService conceptService;

    /**
     * Caches subsumption outcomes per (ancestor, descendant) code pair. A single
     * uncached call fans out into several {@code concept_reference_term_map}
     * queries as it walks the broader closure, so memoising the result keeps the
     * per-drug-add lookup cheap — mirroring the Snowstorm backend's cache. TTL is
     * pulled from {@code cdshooks.cacheTtlSeconds}; the trade-off is that edits to
     * the reference map take up to one TTL to be reflected. Lazily initialised to
     * avoid reading global properties during bean creation.
     */
    private volatile TtlCache<Map.Entry<String, String>, SubsumptionOutcome> subsumesCache;

    /** Test seam — production wiring is field injection. */
    void setConceptService(ConceptService conceptService) {
        this.conceptService = conceptService;
    }

    @Override
    public String name() {
        return BACKEND_NAME;
    }

    /**
     * Always empty — see the class javadoc. The matcher falls back to direct
     * code-to-code subsumption, which is the intended path for this backend.
     */
    @Override
    public List<CodedConcept> getAttributeValues(String conceptCode, String attributeCode) {
        return Collections.emptyList();
    }

    @Override
    public SubsumptionOutcome subsumes(String ancestorCode, String descendantCode) {
        if (ancestorCode == null || descendantCode == null) {
            return SubsumptionOutcome.UNKNOWN;
        }
        Map.Entry<String, String> key = new AbstractMap.SimpleImmutableEntry<>(ancestorCode, descendantCode);
        return subsumesCacheLazy().get(key, k -> computeSubsumes(k.getKey(), k.getValue()));
    }

    private SubsumptionOutcome computeSubsumes(String ancestorCode, String descendantCode) {
        Set<ConceptReferenceTerm> ancestors = resolve(ancestorCode);
        Set<ConceptReferenceTerm> descendants = resolve(descendantCode);
        if (ancestors.isEmpty() || descendants.isEmpty()) {
            // Code isn't present in concept_reference_term_map at all — we have
            // no opinion. UNKNOWN (not NOT_SUBSUMED) so a router can fall
            // through to another backend rather than treat absence as "safe".
            return SubsumptionOutcome.UNKNOWN;
        }

        // Walk the broader-closure of every descendant term, crossing SAME-AS
        // as equivalence. EQUIVALENT wins over SUBSUMES when the start node
        // itself is (or is same-as) the ancestor.
        Set<ConceptReferenceTerm> visited = new HashSet<>();
        Deque<Node> frontier = new ArrayDeque<>();
        for (ConceptReferenceTerm d : descendants) {
            frontier.add(new Node(d, true));
        }

        while (!frontier.isEmpty() && visited.size() < MAX_VISITED) {
            Node node = frontier.poll();
            ConceptReferenceTerm term = node.term;
            if (term == null || !visited.add(term)) {
                continue;
            }
            if (ancestors.contains(term)) {
                // Reaching the ancestor only by SAME-AS hops from the descendant
                // means the two codes are equivalent, not parent/child.
                return node.atStartViaEquivalenceOnly
                        ? SubsumptionOutcome.EQUIVALENT
                        : SubsumptionOutcome.SUBSUMES;
            }
            for (ConceptReferenceTerm broader : broaderNeighbors(term)) {
                frontier.add(new Node(broader, false));
            }
            for (ConceptReferenceTerm same : sameAsNeighbors(term)) {
                // equivalence preserves the "start via equivalence only" flag
                frontier.add(new Node(same, node.atStartViaEquivalenceOnly));
            }
        }
        if (visited.size() >= MAX_VISITED) {
            log.warn("Subsumption walk for ({} -> {}) hit the {}-term visit cap; "
                    + "treating as not-subsumed", ancestorCode, descendantCode, MAX_VISITED);
        }
        return SubsumptionOutcome.NOT_SUBSUMED;
    }

    /* -------------------- cache -------------------- */

    private TtlCache<Map.Entry<String, String>, SubsumptionOutcome> subsumesCacheLazy() {
        TtlCache<Map.Entry<String, String>, SubsumptionOutcome> local = subsumesCache;
        if (local != null) return local;
        synchronized (this) {
            if (subsumesCache == null) {
                subsumesCache = new TtlCache<>(cacheTtlMillis(), cacheMaxEntries());
            }
            return subsumesCache;
        }
    }

    private long cacheTtlMillis() {
        return TimeUnit.SECONDS.toMillis(parseLong(
                gp(CdsHooksConstants.GP_CACHE_TTL_SECONDS), DEFAULT_TTL_SECONDS));
    }

    private int cacheMaxEntries() {
        long configured = parseLong(gp(CdsHooksConstants.GP_CACHE_MAX_ENTRIES), TtlCache.DEFAULT_MAX_ENTRIES);
        if (configured <= 0 || configured > Integer.MAX_VALUE) {
            return TtlCache.DEFAULT_MAX_ENTRIES;
        }
        return (int) configured;
    }

    /** Best-effort global-property read; never breaks the lookup (see AllergyMatcherImpl). */
    private static String gp(String key) {
        try {
            return Context.getAdministrationService().getGlobalProperty(key);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    /* -------------------- graph helpers -------------------- */

    /** Terms strictly broader than {@code term} by one edge. */
    private Set<ConceptReferenceTerm> broaderNeighbors(ConceptReferenceTerm term) {
        Set<ConceptReferenceTerm> out = new HashSet<>();
        // Outgoing: term is A. NARROWER-THAN / IS-A A→B means B is broader.
        for (ConceptReferenceTermMap map : safeOutgoing(term)) {
            String type = typeName(map.getConceptMapType());
            if (NARROWER_TYPES.contains(type)) {
                addIfLive(out, map.getTermB());
            }
        }
        // Incoming: term is B. BROADER-THAN A→B means A is broader.
        for (ConceptReferenceTermMap map : safeIncoming(term)) {
            String type = typeName(map.getConceptMapType());
            if (BROADER_TYPE.equals(type)) {
                addIfLive(out, map.getTermA());
            }
        }
        return out;
    }

    /** Terms equivalent to {@code term} (SAME-AS, either direction). */
    private Set<ConceptReferenceTerm> sameAsNeighbors(ConceptReferenceTerm term) {
        Set<ConceptReferenceTerm> out = new HashSet<>();
        for (ConceptReferenceTermMap map : safeOutgoing(term)) {
            if (SAME_AS_TYPE.equals(typeName(map.getConceptMapType()))) {
                addIfLive(out, map.getTermB());
            }
        }
        for (ConceptReferenceTermMap map : safeIncoming(term)) {
            if (SAME_AS_TYPE.equals(typeName(map.getConceptMapType()))) {
                addIfLive(out, map.getTermA());
            }
        }
        return out;
    }

    private Set<ConceptReferenceTermMap> safeOutgoing(ConceptReferenceTerm term) {
        Set<ConceptReferenceTermMap> maps = term.getConceptReferenceTermMaps();
        return maps != null ? maps : Collections.emptySet();
    }

    private List<ConceptReferenceTermMap> safeIncoming(ConceptReferenceTerm term) {
        List<ConceptReferenceTermMap> maps = conceptService.getReferenceTermMappingsTo(term);
        return maps != null ? maps : Collections.emptyList();
    }

    private static void addIfLive(Set<ConceptReferenceTerm> out, ConceptReferenceTerm term) {
        if (term != null && !Boolean.TRUE.equals(term.getRetired())) {
            out.add(term);
        }
    }

    private static String typeName(ConceptMapType type) {
        return type == null || type.getName() == null
                ? "" : type.getName().trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Resolve a reference code to the (usually one) reference term(s) carrying
     * it. The source is unknown at this layer, so all sources are searched and
     * results are filtered to exact, case-insensitive code matches.
     */
    private Set<ConceptReferenceTerm> resolve(String code) {
        Set<ConceptReferenceTerm> out = new HashSet<>();
        String needle = code.trim();
        if (needle.isEmpty()) {
            return out;
        }
        List<ConceptReferenceTerm> candidates;
        try {
            candidates = conceptService.getConceptReferenceTerms(needle, null, null, null, false);
        } catch (Exception e) {
            log.debug("Reference-term lookup failed for code {}: {}", code, e.getMessage());
            return out;
        }
        if (candidates == null) {
            return out;
        }
        for (ConceptReferenceTerm term : candidates) {
            if (term != null && term.getCode() != null
                    && needle.equalsIgnoreCase(term.getCode().trim())) {
                addIfLive(out, term);
            }
        }
        return out;
    }

    /** BFS node carrying whether it's still reachable from the start by SAME-AS hops only. */
    private static final class Node {
        final ConceptReferenceTerm term;
        final boolean atStartViaEquivalenceOnly;

        Node(ConceptReferenceTerm term, boolean atStartViaEquivalenceOnly) {
            this.term = term;
            this.atStartViaEquivalenceOnly = atStartViaEquivalenceOnly;
        }
    }
}
