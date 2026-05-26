package org.openmrs.module.cdshooks.model;

import java.util.Objects;

public final class SnomedConcept {

    private final String sctid;
    private final String display;

    public SnomedConcept(String sctid, String display) {
        this.sctid = sctid;
        this.display = display;
    }

    public String getSctid() { return sctid; }
    public String getDisplay() { return display; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SnomedConcept)) return false;
        SnomedConcept that = (SnomedConcept) o;
        return Objects.equals(sctid, that.sctid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sctid);
    }

    @Override
    public String toString() {
        return sctid + " (" + display + ")";
    }
}
