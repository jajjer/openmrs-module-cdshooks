#!/usr/bin/env python3
"""
Allergy / Rx warning — toy matching algorithm.

The entire backend algorithm from the design doc, in ~50 lines:
  for each patient allergy:
    for each SNOMED code mapped to the ordered drug:
      if drug code == allergy code -> INGREDIENT match
      else if allergy code subsumes drug code in SNOMED -> CLASS match

This skips the OpenMRS-to-SNOMED resolution step (concept_reference_term_map).
We just hardcode SNOMED codes for the worked example. The real implementation
would look up reference terms before this loop.

Usage:
  python3 02-match.py
  SNOWSTORM=https://your-snowstorm/fhir python3 02-match.py
"""

import os
import sys
import urllib.parse
import urllib.request
import json
from dataclasses import dataclass

SNOWSTORM = os.environ.get("SNOWSTORM", "https://tx.fhir.org/r4")
SNOMED = "http://snomed.info/sct"


@dataclass
class Allergy:
    display: str
    sctid: str
    severity: str
    reaction: str


@dataclass
class Drug:
    display: str
    sctid: str


# Hardcoded worked example using SNOMED *medicinal product* concepts.
# Substance hierarchy concepts would have different SCTIDs.
PATIENT_ALLERGIES = [
    Allergy(display="Product containing penicillin", sctid="890458001", severity="SEVERE", reaction="Hepatotoxicity"),
]
ORDERED_DRUG = Drug(display="Amoxicillin-containing product", sctid="27658006")


def fhir_get(path: str, params: dict) -> dict:
    url = f"{SNOWSTORM}{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"Accept": "application/fhir+json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())


def subsumes(ancestor_sctid: str, descendant_sctid: str) -> str:
    """Return the FHIR $subsumes outcome: 'equivalent', 'subsumes', 'subsumed-by', or 'not-subsumed'."""
    result = fhir_get(
        "/CodeSystem/$subsumes",
        {"system": SNOMED, "codeA": ancestor_sctid, "codeB": descendant_sctid},
    )
    for p in result.get("parameter", []):
        if p.get("name") == "outcome":
            return p.get("valueCode", "unknown")
    return "unknown"


def match(drug: Drug, allergies: list[Allergy]) -> list[dict]:
    matches = []
    for a in allergies:
        if a.sctid == drug.sctid:
            matches.append({
                "allergen": a.display,
                "matchType": "ingredient",
                "severity": a.severity,
                "reaction": a.reaction,
                "explanation": f"Patient is directly allergic to {a.display}.",
            })
            continue
        outcome = subsumes(ancestor_sctid=a.sctid, descendant_sctid=drug.sctid)
        if outcome in ("subsumes", "equivalent"):
            matches.append({
                "allergen": a.display,
                "matchType": "class",
                "severity": a.severity,
                "reaction": a.reaction,
                "explanation": f"{drug.display} is a {a.display}.",
            })
    return matches


def main() -> int:
    print(f"Snowstorm: {SNOWSTORM}")
    print(f"Ordered drug: {ORDERED_DRUG.display} ({ORDERED_DRUG.sctid})")
    print(f"Patient allergies: {[a.display for a in PATIENT_ALLERGIES]}")
    print()

    matches = match(ORDERED_DRUG, PATIENT_ALLERGIES)
    if not matches:
        print("No allergy/Rx conflicts detected.")
        return 0

    for m in matches:
        icon = "⚠"
        print(f"{icon} [{m['severity']}] {m['matchType'].upper()} MATCH")
        print(f"   Allergen: {m['allergen']}  (reaction: {m['reaction']})")
        print(f"   {m['explanation']}")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
