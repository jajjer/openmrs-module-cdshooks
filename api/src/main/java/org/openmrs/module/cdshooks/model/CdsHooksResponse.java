/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

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
