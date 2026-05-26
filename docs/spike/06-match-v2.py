#!/usr/bin/env python3
"""
Allergy / Rx warning — corrected matching algorithm (v2).

Reflects the cross-hierarchy traversal that the v1 spike missed:

  Allergen finding  --Causative agent-->  Substance(s)
                                              ^
                                              | $subsumes
                                              |
  Drug product  --Has active ingredient-->  Substance(s)

For each (causative_agent, active_ingredient) pair, ask Snowstorm whether the
causative agent subsumes the active ingredient. If yes anywhere, it's a class
match. If the causative agent equals an active ingredient, it's an ingredient
match.

SNOMED attribute SCTIDs used:
  246075003 = Causative agent
  127489000 = Has active ingredient

Usage:
  python3 06-match-v2.py
"""

import os
import sys
import urllib.parse
import urllib.request
import json
from dataclasses import dataclass

SNOWSTORM = os.environ.get("SNOWSTORM", "https://tx.fhir.org/r4")
SNOMED = "http://snomed.info/sct"
CAUSATIVE_AGENT = "246075003"
HAS_ACTIVE_INGREDIENT = "127489000"


@dataclass
class Allergy:
    display: str
    finding_sctid: str
    severity: str
    reaction: str


@dataclass
class Drug:
    display: str
    product_sctid: str


PATIENT_ALLERGIES = [
    Allergy(
        display="Allergy to penicillin",
        finding_sctid="91936005",
        severity="SEVERE",
        reaction="Hepatotoxicity",
    ),
]
ORDERED_DRUG = Drug(display="Amoxicillin-containing product", product_sctid="27658006")


def fhir_get(path: str, params: dict) -> dict:
    url = f"{SNOWSTORM}{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"Accept": "application/fhir+json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())


def get_attribute_values(sctid: str, attr_sctid: str) -> list[tuple[str, str]]:
    """Return [(code, display), ...] for all values of a given SNOMED attribute on a concept."""
    result = fhir_get(
        "/CodeSystem/$lookup",
        {"system": SNOMED, "code": sctid},
    )
    values = []
    for p in result.get("parameter", []):
        if p.get("name") != "property":
            continue
        parts = {pp["name"]: pp for pp in p.get("part", [])}
        if parts.get("code", {}).get("valueCode") == attr_sctid:
            code = parts.get("value", {}).get("valueCode")
            display = parts.get("description", {}).get("valueString", "")
            if code:
                values.append((code, display))
    return values


def subsumes(ancestor_sctid: str, descendant_sctid: str) -> str:
    result = fhir_get(
        "/CodeSystem/$subsumes",
        {"system": SNOMED, "codeA": ancestor_sctid, "codeB": descendant_sctid},
    )
    for p in result.get("parameter", []):
        if p.get("name") == "outcome":
            return p.get("valueCode", "unknown")
    return "unknown"


def match(drug: Drug, allergies: list[Allergy]) -> list[dict]:
    drug_ingredients = get_attribute_values(drug.product_sctid, HAS_ACTIVE_INGREDIENT)
    if not drug_ingredients:
        print(f"  (no active ingredients found for {drug.display}; cannot match)")
        return []

    matches = []
    for allergy in allergies:
        causative_agents = get_attribute_values(allergy.finding_sctid, CAUSATIVE_AGENT)
        if not causative_agents:
            continue

        for agent_code, agent_display in causative_agents:
            for ing_code, ing_display in drug_ingredients:
                # Ingredient match: drug active ingredient equals the causative agent
                if agent_code == ing_code:
                    matches.append({
                        "allergen": allergy.display,
                        "matchType": "ingredient",
                        "severity": allergy.severity,
                        "reaction": allergy.reaction,
                        "explanation": (
                            f"{drug.display} contains {ing_display}, which is the "
                            f"causative agent of {allergy.display}."
                        ),
                    })
                    continue
                # Class match: causative agent subsumes the drug active ingredient
                outcome = subsumes(ancestor_sctid=agent_code, descendant_sctid=ing_code)
                if outcome in ("subsumes", "equivalent"):
                    matches.append({
                        "allergen": allergy.display,
                        "matchType": "class",
                        "severity": allergy.severity,
                        "reaction": allergy.reaction,
                        "explanation": (
                            f"{drug.display} contains {ing_display}, which is a "
                            f"{agent_display} — the causative-agent class for {allergy.display}."
                        ),
                    })

    # Deduplicate by (allergen, matchType, explanation)
    seen = set()
    deduped = []
    for m in matches:
        key = (m["allergen"], m["matchType"], m["explanation"])
        if key not in seen:
            seen.add(key)
            deduped.append(m)
    return deduped


def main() -> int:
    print(f"Snowstorm: {SNOWSTORM}")
    print(f"Ordered drug: {ORDERED_DRUG.display} ({ORDERED_DRUG.product_sctid})")
    print(f"Patient allergies: {[a.display for a in PATIENT_ALLERGIES]}")
    print()

    matches = match(ORDERED_DRUG, PATIENT_ALLERGIES)
    if not matches:
        print("No allergy/Rx conflicts detected.")
        return 0

    for m in matches:
        print(f"⚠ [{m['severity']}] {m['matchType'].upper()} MATCH")
        print(f"   Allergen: {m['allergen']}  (reaction: {m['reaction']})")
        print(f"   {m['explanation']}")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
