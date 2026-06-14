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

import java.util.Objects;

/**
 * A terminology-neutral (code, display) pair. The matcher and its backends
 * speak in these rather than any single code system, so the same value object
 * carries a SNOMED SCTID, an RxNORM CUI, or an RxClass NUI interchangeably.
 * Equality is by {@code code} only.
 */
public final class CodedConcept {

    private final String code;
    private final String display;

    public CodedConcept(String code, String display) {
        this.code = code;
        this.display = display;
    }

    public String getCode() { return code; }
    public String getDisplay() { return display; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CodedConcept)) return false;
        CodedConcept that = (CodedConcept) o;
        return Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return code + " (" + display + ")";
    }
}
