package org.openmrs.module.cdshooks.api.impl;

import org.openmrs.Concept;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracts SNOMED CT SCTIDs from an OpenMRS {@link Concept} by walking its
 * concept mappings and matching against the SNOMED reference source.
 *
 * <p>The SNOMED source can be identified by source name (typically
 * "SNOMED CT") or by HL7 code "SCT". We match either, case-insensitively.
 */
@Component
public class SnomedMappingExtractor {

    public List<String> extract(Concept concept) {
        List<String> sctids = new ArrayList<>();
        if (concept == null) return sctids;
        for (ConceptMap mapping : concept.getConceptMappings()) {
            ConceptReferenceTerm term = mapping.getConceptReferenceTerm();
            if (term == null) continue;
            if (isSnomedSource(term.getConceptSource()) && term.getCode() != null) {
                sctids.add(term.getCode().trim());
            }
        }
        return sctids;
    }

    private static boolean isSnomedSource(ConceptSource source) {
        if (source == null) return false;
        String name = source.getName();
        String hl7 = source.getHl7Code();
        return ("SNOMED CT".equalsIgnoreCase(name))
                || ("SNOMED".equalsIgnoreCase(name))
                || (name != null && name.toUpperCase(Locale.ROOT).contains("SNOMED"))
                || ("SCT".equalsIgnoreCase(hl7));
    }
}
