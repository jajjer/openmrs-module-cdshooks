package org.openmrs.module.cdshooks.model;

public final class AllergyMatch {

    public enum MatchType { INGREDIENT, CLASS }

    public enum Severity { MILD, MODERATE, SEVERE, UNKNOWN }

    private final String allergenDisplay;
    private final MatchType matchType;
    private final Severity severity;
    private final String reaction;
    private final String explanation;

    public AllergyMatch(String allergenDisplay, MatchType matchType, Severity severity,
                        String reaction, String explanation) {
        this.allergenDisplay = allergenDisplay;
        this.matchType = matchType;
        this.severity = severity;
        this.reaction = reaction;
        this.explanation = explanation;
    }

    public String getAllergenDisplay() { return allergenDisplay; }
    public MatchType getMatchType() { return matchType; }
    public Severity getSeverity() { return severity; }
    public String getReaction() { return reaction; }
    public String getExplanation() { return explanation; }
}
