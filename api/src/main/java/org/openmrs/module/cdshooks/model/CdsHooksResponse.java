package org.openmrs.module.cdshooks.model;

import java.util.ArrayList;
import java.util.List;

/**
 * CDS-Hooks 2.0 response envelope. See
 * https://cds-hooks.hl7.org/2.0/#http-response_1 for the full schema.
 */
public class CdsHooksResponse {

    private List<Card> cards = new ArrayList<>();
    private List<SystemAction> systemActions = new ArrayList<>();

    public List<Card> getCards() { return cards; }
    public void setCards(List<Card> cards) { this.cards = cards; }

    public List<SystemAction> getSystemActions() { return systemActions; }
    public void setSystemActions(List<SystemAction> systemActions) { this.systemActions = systemActions; }

    public static class Card {
        public String uuid;
        public String summary;
        public String detail;
        public String indicator; // info | warning | critical
        public Source source;
        public List<Link> links = new ArrayList<>();

        public static class Source {
            public String label;
            public String url;
            public String icon;
        }

        public static class Link {
            public String label;
            public String url;
            public String type; // absolute | smart
        }
    }

    public static class SystemAction {
        public String type;       // create | update | delete
        public String description;
        public Object resource;
    }
}
