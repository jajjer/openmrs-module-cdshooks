#!/usr/bin/env bash
# Raw FHIR calls against SNOMED International's public Snowstorm.
# Verify the endpoint URL at https://www.snomed.org/ before running — it has changed before.
#
# Usage:
#   chmod +x 01-curl-tests.sh
#   ./01-curl-tests.sh

set -euo pipefail

SNOWSTORM="${SNOWSTORM:-https://tx.fhir.org/r4}"
SNOMED_SYSTEM="http://snomed.info/sct"

# Important spike finding: SNOMED has parallel drug hierarchies (substance vs.
# medicinal product). The class-match ancestor depends on which hierarchy the
# OpenMRS concept is mapped to. These SCTIDs are from the *product* hierarchy.
AMOXICILLIN_SCTID="${AMOXICILLIN_SCTID:-27658006}"       # Amoxicillin-containing product
PENICILLIN_CLASS_SCTID="${PENICILLIN_CLASS_SCTID:-890458001}"  # Product containing penicillin

echo "=== 1. CapabilityStatement (sanity check the endpoint is alive) ==="
curl -sS "$SNOWSTORM/metadata" \
  -H 'Accept: application/fhir+json' \
  | head -c 400
echo
echo

echo "=== 2. Look up Amoxicillin ($AMOXICILLIN_SCTID) ==="
curl -sS "$SNOWSTORM/CodeSystem/\$lookup?system=$SNOMED_SYSTEM&code=$AMOXICILLIN_SCTID" \
  -H 'Accept: application/fhir+json'
echo
echo

echo "=== 3. Look up Penicillin class ($PENICILLIN_CLASS_SCTID) ==="
curl -sS "$SNOWSTORM/CodeSystem/\$lookup?system=$SNOMED_SYSTEM&code=$PENICILLIN_CLASS_SCTID" \
  -H 'Accept: application/fhir+json'
echo
echo

echo "=== 4. THE LOAD-BEARING CALL: does Penicillin subsume Amoxicillin? ==="
echo "Expect: parameter 'outcome' = 'subsumes' (Amoxicillin is-a Penicillin)"
curl -sS "$SNOWSTORM/CodeSystem/\$subsumes?system=$SNOMED_SYSTEM&codeA=$PENICILLIN_CLASS_SCTID&codeB=$AMOXICILLIN_SCTID" \
  -H 'Accept: application/fhir+json'
echo
echo

echo "=== 5. Reverse: does Amoxicillin subsume Penicillin? (should be 'not-subsumed') ==="
curl -sS "$SNOWSTORM/CodeSystem/\$subsumes?system=$SNOMED_SYSTEM&codeA=$AMOXICILLIN_SCTID&codeB=$PENICILLIN_CLASS_SCTID" \
  -H 'Accept: application/fhir+json'
echo
