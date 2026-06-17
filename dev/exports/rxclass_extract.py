#!/usr/bin/env python3
"""
Generate a starter set of RxClass drug-class edges for loading into the OpenMRS
`concept_reference_term_map` table, in the same CSV shape as the table export.

Each output row is:  RxNORM,<ingredient CUI>,<name>,NARROWER-THAN,RxClass,<class NUI>,<class name>

Source: NLM RxClass / RxNav public API (https://rxnav.nlm.nih.gov/RxClassAPI.html).
Scope (starter): Established Pharmacologic Classes (EPC) whose names match the
common drug-allergy families below. EPC is the clinically meaningful
"what kind of drug is this" axis; RxClass also exposes ATC, mechanism-of-action,
chemical-structure, and physiologic-effect axes that could be added later.

Modeling choices baked in here (flagged for terminology review):
  * relaSource = DAILYMED for class membership.
  * Members arrive as IN (ingredient) or PIN (precise ingredient). PINs are
    normalized down to their base IN so the edge lands on the ingredient CUI
    CIEL typically maps drugs to (e.g. amoxicillin anhydrous PIN 1297882 -> IN 723).
"""
import json
import sys
import time
import urllib.request
import urllib.parse

BASE = "https://rxnav.nlm.nih.gov/REST"

# Common drug-allergy families. Matched (case-insensitive substring) against the
# names of all EPC classes, so we never hard-code a NUI that might be wrong.
KEYWORDS = [
    "penicillin", "cephalosporin", "carbapenem", "monobactam",
    "sulfonamide", "macrolide", "tetracycline", "quinolone",
    "aminoglycoside", "glycopeptide", "lincosamide",
    "nonsteroidal anti-inflammatory", "opioid",
    "angiotensin-converting enzyme",
]


def get(path, **params):
    url = f"{BASE}/{path}.json"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            if attempt == 3:
                raise
            time.sleep(1.5 * (attempt + 1))
    return {}


def base_ingredient(rxcui):
    """Resolve a PIN to its base IN; return list of (cui, name)."""
    d = get(f"rxcui/{rxcui}/related", tty="IN")
    out = []
    for grp in d.get("relatedGroup", {}).get("conceptGroup", []):
        for c in grp.get("conceptProperties", []):
            out.append((c["rxcui"], c["name"]))
    return out


def main():
    epc = get("rxclass/allClasses", classTypes="EPC")
    classes = epc["rxclassMinConceptList"]["rxclassMinConcept"]
    matched = [c for c in classes
               if any(k in c["className"].lower() for k in KEYWORDS)]
    sys.stderr.write(f"Matched {len(matched)} EPC classes\n")

    rows = set()  # (cui, name, nui, classname)
    for c in matched:
        nui, cname = c["classId"], c["className"]
        d = get("rxclass/classMembers", classId=nui, relaSource="DAILYMED")
        members = d.get("drugMemberGroup", {}).get("drugMember", [])
        for m in members:
            mc = m["minConcept"]
            cui, name, tty = mc["rxcui"], mc["name"], mc["tty"]
            if tty == "IN":
                rows.add((cui, name, nui, cname))
            elif tty == "PIN":
                for bcui, bname in base_ingredient(cui):
                    rows.add((bcui, bname, nui, cname))
        sys.stderr.write(f"  {nui}  {cname}: {len(members)} members\n")
        time.sleep(0.1)

    rows = sorted(rows, key=lambda r: (r[3], r[1]))
    w = sys.stdout
    w.write("source_a,code_a,name_a,map_type,source_b,code_b,name_b\n")
    for cui, name, nui, cname in rows:
        def q(s):
            return '"' + str(s).replace('"', '""') + '"'
        w.write(",".join([q("RxNORM"), q(cui), q(name), q("NARROWER-THAN"),
                          q("RxClass"), q(nui), q(cname)]) + "\n")
    sys.stderr.write(f"\nWrote {len(rows)} edges across {len(matched)} classes\n")


if __name__ == "__main__":
    main()
