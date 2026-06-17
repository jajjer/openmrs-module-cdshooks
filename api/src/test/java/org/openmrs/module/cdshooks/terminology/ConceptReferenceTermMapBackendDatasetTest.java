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

import org.junit.BeforeClass;
import org.junit.Test;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptReferenceTermMap;
import org.openmrs.ConceptSource;
import org.openmrs.api.ConceptService;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Data-driven coverage for {@link ConceptReferenceTermMapBackend}: instead of
 * the single hand-built amoxicillin&rarr;penicillins fixture, this test loads
 * <em>every</em> reference-term edge from the curated datasets in
 * {@code dev/exports/} and runs the real subsumption walk over all of them.
 *
 * <ul>
 *   <li>{@code rxclass_drug_class_edges.csv} — the generated RxNORM ingredient
 *       &rarr; RxClass class edges (penicillins, cephalosporins, opioids, …).</li>
 *   <li>{@code concept_reference_term_map_refapp3.csv} — the RefApp 3 baseline:
 *       the ATC class tree plus RxNORM ingredient &rarr; ATC class edges.</li>
 * </ul>
 *
 * <p>The whole graph is built once as in-memory {@link ConceptReferenceTerm}
 * POJOs behind a stubbed {@link ConceptService} (no database), so this runs in
 * the plain {@code mvn test} unit harness — unlike the context-sensitive
 * {@code *IntegrationTest} classes, which are excluded from the default build.
 *
 * <p>What it proves across the full dataset:
 * <ol>
 *   <li>For every edge, the broader term subsumes the narrower one
 *       ({@link SubsumptionOutcome#SUBSUMES}).</li>
 *   <li>The reverse direction never reports {@code SUBSUMES} (the drug does not
 *       subsume its own class).</li>
 *   <li>Multi-hop transitivity: every leaf reaches the root of its class tree
 *       (e.g. amoxicillin &rarr; J01CA &rarr; J01C &rarr; … &rarr; J).</li>
 *   <li>A code mapped to itself is {@link SubsumptionOutcome#EQUIVALENT}; a code
 *       absent from the table is {@link SubsumptionOutcome#UNKNOWN}.</li>
 * </ol>
 */
public class ConceptReferenceTermMapBackendDatasetTest {

    private static final String[] DATASETS = {
            "rxclass_drug_class_edges.csv",
            "concept_reference_term_map_refapp3.csv",
    };

    /** One descendant--type-->broader edge as loaded from a CSV row. */
    private static final class Edge {
        final String sourceA, codeA, type, sourceB, codeB;
        Edge(String sourceA, String codeA, String type, String sourceB, String codeB) {
            this.sourceA = sourceA;
            this.codeA = codeA;
            this.type = type;
            this.sourceB = sourceB;
            this.codeB = codeB;
        }
    }

    private static List<Edge> edges;
    private static ConceptReferenceTermMapBackend backend;

    /** Adjacency by code: code -> set of broader codes (one hop up). */
    private static Map<String, Set<String>> parentsOf;

    @BeforeClass
    public static void loadDatasetGraph() {
        edges = new ArrayList<>();
        for (String dataset : DATASETS) {
            edges.addAll(readEdges(locateDataset(dataset)));
        }
        assertTrue("expected reference-map edges to be loaded from dev/exports", edges.size() > 100);

        // Build the in-memory term graph the backend will walk.
        Map<String, ConceptSource> sources = new HashMap<>();
        Map<String, ConceptMapType> mapTypes = new HashMap<>();
        Map<String, ConceptReferenceTerm> termsByKey = new LinkedHashMap<>();
        Map<String, List<ConceptReferenceTerm>> termsByCode = new HashMap<>();
        Map<ConceptReferenceTerm, List<ConceptReferenceTermMap>> incoming = new HashMap<>();
        parentsOf = new HashMap<>();

        for (Edge e : edges) {
            ConceptReferenceTerm a = term(e.sourceA, e.codeA, sources, termsByKey, termsByCode);
            ConceptReferenceTerm b = term(e.sourceB, e.codeB, sources, termsByKey, termsByCode);
            ConceptMapType type = mapTypes.computeIfAbsent(e.type, ConceptReferenceTermMapBackendDatasetTest::mapType);

            // Outgoing edge a --type--> b (addConceptReferenceTermMap sets termA = a).
            ConceptReferenceTermMap map = new ConceptReferenceTermMap();
            map.setTermB(b);
            map.setConceptMapType(type);
            a.addConceptReferenceTermMap(map);
            incoming.computeIfAbsent(b, k -> new ArrayList<>()).add(map);

            parentsOf.computeIfAbsent(e.codeA, k -> new HashSet<>()).add(e.codeB);
        }

        ConceptService cs = mock(ConceptService.class);
        when(cs.getConceptReferenceTerms(anyString(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> termsByCode.getOrDefault(((String) inv.getArgument(0)).trim(),
                        Collections.emptyList()));
        when(cs.getReferenceTermMappingsTo(any()))
                .thenAnswer(inv -> incoming.getOrDefault(inv.getArgument(0), Collections.emptyList()));

        backend = new ConceptReferenceTermMapBackend();
        backend.setConceptService(cs);
    }

    /** Every loaded edge: the broader code subsumes the narrower code. */
    @Test
    public void everyEdge_broaderSubsumesNarrower() {
        int checked = 0;
        for (Edge e : edges) {
            SubsumptionOutcome outcome = backend.subsumes(e.codeB, e.codeA);
            assertThat(describe(e, "broader should subsume narrower"),
                    outcome, is(SubsumptionOutcome.SUBSUMES));
            checked++;
        }
        assertTrue(checked > 100);
    }

    /** The narrower code never subsumes its own broader class (no spurious matches). */
    @Test
    public void everyEdge_narrowerDoesNotSubsumeBroader() {
        for (Edge e : edges) {
            // Skip self-referential codes (a code that is both broader and
            // narrower elsewhere) — direction is only meaningful per distinct pair.
            if (e.codeA.equals(e.codeB)) {
                continue;
            }
            SubsumptionOutcome outcome = backend.subsumes(e.codeA, e.codeB);
            assertThat(describe(e, "narrower must not subsume broader"),
                    outcome, is(not(SubsumptionOutcome.SUBSUMES)));
        }
    }

    /** Multi-hop transitivity: every leaf reaches the root(s) of its class tree. */
    @Test
    public void transitiveClimb_leafReachesRoot() {
        int rootsChecked = 0;
        for (String leaf : leafCodes()) {
            for (String root : rootsAbove(leaf)) {
                if (root.equals(leaf)) {
                    continue;
                }
                assertThat("leaf " + leaf + " should subsume up to root " + root,
                        backend.subsumes(root, leaf), is(SubsumptionOutcome.SUBSUMES));
                rootsChecked++;
            }
        }
        assertTrue("expected some multi-hop class trees to climb", rootsChecked > 0);
    }

    @Test
    public void sameCode_isEquivalent() {
        String anyCode = edges.get(0).codeA;
        assertThat(backend.subsumes(anyCode, anyCode), is(SubsumptionOutcome.EQUIVALENT));
    }

    @Test
    public void absentCode_isUnknown() {
        assertThat(backend.subsumes(edges.get(0).codeB, "no-such-code-zzz-999999"),
                is(SubsumptionOutcome.UNKNOWN));
    }

    /* -------------------- graph navigation (for transitivity) -------------------- */

    /** Codes that are a child somewhere but never themselves a parent — the leaves. */
    private static Set<String> leafCodes() {
        Set<String> allParents = new HashSet<>();
        for (Set<String> ps : parentsOf.values()) {
            allParents.addAll(ps);
        }
        Set<String> leaves = new HashSet<>(parentsOf.keySet());
        leaves.removeAll(allParents);
        return leaves;
    }

    /** All ancestor codes reachable from {@code start} by climbing parent edges. */
    private static Set<String> rootsAbove(String start) {
        Set<String> roots = new HashSet<>();
        Set<String> seen = new HashSet<>();
        climb(start, seen, roots);
        return roots;
    }

    /** Iterative DFS up the parent edges; collects codes that have no parent. */
    private static void climb(String start, Set<String> seen, Set<String> roots) {
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String code = stack.pop();
            if (!seen.add(code)) {
                continue;
            }
            Set<String> parents = parentsOf.get(code);
            if (parents == null || parents.isEmpty()) {
                if (!code.equals(start)) {
                    roots.add(code);
                }
                continue;
            }
            for (String p : parents) {
                stack.push(p);
            }
        }
    }

    /* -------------------- fixture construction -------------------- */

    private static ConceptReferenceTerm term(String sourceName, String code,
                                             Map<String, ConceptSource> sources,
                                             Map<String, ConceptReferenceTerm> termsByKey,
                                             Map<String, List<ConceptReferenceTerm>> termsByCode) {
        String key = sourceName + "|" + code;
        ConceptReferenceTerm existing = termsByKey.get(key);
        if (existing != null) {
            return existing;
        }
        ConceptSource source = sources.computeIfAbsent(sourceName,
                ConceptReferenceTermMapBackendDatasetTest::source);
        ConceptReferenceTerm t = new ConceptReferenceTerm();
        t.setCode(code);
        t.setConceptSource(source);
        t.setRetired(false);
        termsByKey.put(key, t);
        termsByCode.computeIfAbsent(code, k -> new ArrayList<>()).add(t);
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

    private static String describe(Edge e, String what) {
        return what + ": " + e.sourceA + ":" + e.codeA + " --" + e.type + "--> "
                + e.sourceB + ":" + e.codeB;
    }

    /* -------------------- CSV loading -------------------- */

    /**
     * Locate {@code dev/exports/<name>} by walking up from the working directory
     * (the {@code api} module dir under surefire), so the test reads the same
     * curated source-of-truth files the team maintains rather than a copy.
     */
    private static Path locateDataset(String name) {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve("dev").resolve("exports").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate dev/exports/" + name
                + " upward from " + dir);
    }

    private static List<Edge> readEdges(Path csv) {
        List<Edge> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = r.readLine(); // skip header
            if (header == null) {
                return out;
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> f = parseCsvLine(line);
                // columns: source_a,code_a,name_a,map_type,source_b,code_b,name_b
                if (f.size() < 6) {
                    continue;
                }
                out.add(new Edge(f.get(0), f.get(1), f.get(3), f.get(4), f.get(5)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + csv, e);
        }
        return out;
    }

    /** Minimal RFC-4180-ish parser: handles quoted fields and embedded commas. */
    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }
}
