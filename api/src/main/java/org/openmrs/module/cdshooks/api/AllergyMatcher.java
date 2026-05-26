package org.openmrs.module.cdshooks.api;

import org.openmrs.module.cdshooks.model.AllergyMatch;

import java.util.List;

public interface AllergyMatcher {

    /**
     * Find ingredient and class matches between the patient's allergies and an
     * ordered drug. Both sides are described by their SNOMED CT codings; the
     * matcher bridges from finding → substance (Causative agent) and product →
     * substance (Has active ingredient) via the configured Snowstorm instance,
     * then runs substance × substance subsumption.
     *
     * @param drug      the drug being prescribed
     * @param allergies the patient's recorded allergies
     * @return zero or more matches; empty if no conflict
     */
    List<AllergyMatch> match(DrugInput drug, List<AllergyInput> allergies);

    final class DrugInput {
        public final String display;
        public final List<String> snomedSctids;

        public DrugInput(String display, List<String> snomedSctids) {
            this.display = display;
            this.snomedSctids = List.copyOf(snomedSctids);
        }
    }

    final class AllergyInput {
        public final String display;
        public final List<String> snomedSctids;
        public final AllergyMatch.Severity severity;
        public final String reactionDisplay;

        public AllergyInput(String display, List<String> snomedSctids,
                            AllergyMatch.Severity severity, String reactionDisplay) {
            this.display = display;
            this.snomedSctids = List.copyOf(snomedSctids);
            this.severity = severity;
            this.reactionDisplay = reactionDisplay;
        }
    }
}
