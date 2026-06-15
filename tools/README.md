# Coverage tooling

The drug-allergy matcher can only check a med/allergen when its concept carries
a code it can resolve (SNOMED CT or RxNORM) **and** a terminology relationship
links the drug to the allergen's class. These tools attack the data side — the
real path to high coverage (see `../docs/DESIGN.md`, "Phase 2").

Coverage is measured at the **concept** level: many drug products (Amoxicillin
250 mg, 500 mg, …) share one concept, so mapping that one concept covers them all.

## `coverage-report.sql` — where are the gaps?

Reports drug + allergen mapping coverage and ranks the **unmapped, commonly-
ordered** drugs so the data work is targeted.

```bash
docker exec -i cdshooks-dev-db-1 mysql -uopenmrs -popenmrs openmrs -t \
  < tools/coverage-report.sql
```

Example (the bundled demo DB): 21/163 drug concepts mapped (~13%); top gaps
were Furosemide, Diphenhydramine, Ibuprofen, Prednisone, Amoxicillin+clavulanate.

## `rxclass_loader.py` — fill the drug→class relationships

For a set of RxNORM ingredient CUIs, fetches their ATC drug classes from the NLM
RxClass API and emits the `concept_reference_term_map` edges the reference-map
backend consumes — plus the ATC class hierarchy, derived from the code structure
(`J01CA → J01C → J01`), so a broad allergen mapping (e.g. penicillins = `J01C`)
subsumes any drug that rolls up to it. No manual class curation.

```bash
# 1. extract the RxNORM CUIs already on drug concepts
docker exec cdshooks-dev-db-1 mysql -uopenmrs -popenmrs openmrs -N -e "
  SELECT DISTINCT t.code FROM drug d
  JOIN concept_reference_map crm ON crm.concept_id=d.concept_id
  JOIN concept_reference_term t  ON t.concept_reference_term_id=crm.concept_reference_term_id
  JOIN concept_reference_source cs ON cs.concept_source_id=t.concept_source_id
  WHERE d.retired=0 AND cs.name='RxNORM';" > cuis.txt

# 2. generate edges (needs network access to rxnav.nlm.nih.gov)
python3 tools/rxclass_loader.py cuis.txt --outdir out

# 3. review, then load
docker exec -i cdshooks-dev-db-1 mysql -uopenmrs -popenmrs openmrs < out/load-rxclass-edges.sql
```

Outputs (in `--outdir`):
- `rxclass-edges.csv` — portable `child,source,map_type,parent,source` edge list
- `rxclass-reference-terms.csv` — the ATC class reference terms
- `load-rxclass-edges.sql` — idempotent loader (review before applying)

`--relaSource` selects the class system (default `ATC`; RxClass also exposes
MED-RT/EPC, VA, etc.). The drug allergen concepts still need mapping to a class
code in the same system — that's the complementary half of the data work.

### Known limitations / follow-ups
- Names for *derived* parent ATC levels are placeholders (`ATC J01C`); a
  follow-up can fetch real class names so alert text reads in names.
- Production use should ship these as `openmrs-module-initializer` CSVs in the
  distro (the `.csv` outputs are the basis) rather than raw SQL, with a
  documented refresh cadence (ATC/RxClass change over releases).
- The allergen→class mappings and the canonical class system are terminology
  decisions for the CIEL team.
