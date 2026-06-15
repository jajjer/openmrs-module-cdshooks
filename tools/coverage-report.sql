-- ---------------------------------------------------------------------------
-- cdshooks drug-allergy coverage report
--
-- Reports how much of the dictionary is mappable by the drug-allergy matcher:
-- a drug/allergen can only trigger a check when its concept carries a SNOMED
-- CT or RxNORM reference term (the codes the matcher resolves). Run against an
-- OpenMRS MySQL/MariaDB schema, e.g.:
--
--   docker exec cdshooks-dev-db-1 mysql -uopenmrs -popenmrs openmrs \
--     < tools/coverage-report.sql
--
-- Note: coverage is measured at the *concept* level — many drug products
-- (Amoxicillin 250mg, 500mg, ...) share one concept, so mapping that one
-- concept covers all of them.
-- ---------------------------------------------------------------------------

-- Reusable: concept_ids that have a SNOMED or RxNORM reference term.
-- (Inlined per query since MySQL views aren't assumed.)

SELECT '== DRUG concept coverage (SNOMED or RxNORM) ==' AS report;
SELECT
  COUNT(DISTINCT d.concept_id)                                            AS total_drug_concepts,
  COUNT(DISTINCT mapped.concept_id)                                       AS mapped_concepts,
  ROUND(100 * COUNT(DISTINCT mapped.concept_id)
            / NULLIF(COUNT(DISTINCT d.concept_id), 0), 1)                 AS pct_mapped
FROM drug d
LEFT JOIN (
  SELECT crm.concept_id
  FROM concept_reference_map crm
  JOIN concept_reference_term t  ON t.concept_reference_term_id = crm.concept_reference_term_id
  JOIN concept_reference_source cs ON cs.concept_source_id = t.concept_source_id
  WHERE cs.name LIKE '%SNOMED%' OR cs.name LIKE '%RxNORM%'
) mapped ON mapped.concept_id = d.concept_id
WHERE d.retired = 0;

SELECT '== DRUG coverage by source ==' AS report;
SELECT cs.name AS source, COUNT(DISTINCT d.concept_id) AS drug_concepts_mapped
FROM drug d
JOIN concept_reference_map crm   ON crm.concept_id = d.concept_id
JOIN concept_reference_term t    ON t.concept_reference_term_id = crm.concept_reference_term_id
JOIN concept_reference_source cs ON cs.concept_source_id = t.concept_source_id
WHERE d.retired = 0 AND (cs.name LIKE '%SNOMED%' OR cs.name LIKE '%RxNORM%')
GROUP BY cs.name
ORDER BY drug_concepts_mapped DESC;

SELECT '== TOP UNMAPPED drug concepts (ranked by # of products = priority) ==' AS report;
SELECT
  d.concept_id,
  (SELECT name FROM concept_name WHERE concept_id = d.concept_id AND voided = 0 LIMIT 1) AS drug,
  COUNT(*) AS num_products
FROM drug d
WHERE d.retired = 0
  AND d.concept_id NOT IN (
    SELECT crm.concept_id
    FROM concept_reference_map crm
    JOIN concept_reference_term t    ON t.concept_reference_term_id = crm.concept_reference_term_id
    JOIN concept_reference_source cs ON cs.concept_source_id = t.concept_source_id
    WHERE cs.name LIKE '%SNOMED%' OR cs.name LIKE '%RxNORM%'
  )
GROUP BY d.concept_id
ORDER BY num_products DESC, drug
LIMIT 25;

SELECT '== ALLERGEN coverage (concepts actually recorded as coded allergies) ==' AS report;
SELECT
  COUNT(DISTINCT a.coded_allergen)                                        AS recorded_allergen_concepts,
  COUNT(DISTINCT mapped.concept_id)                                       AS mapped_concepts,
  ROUND(100 * COUNT(DISTINCT mapped.concept_id)
            / NULLIF(COUNT(DISTINCT a.coded_allergen), 0), 1)             AS pct_mapped
FROM allergy a
LEFT JOIN (
  SELECT crm.concept_id
  FROM concept_reference_map crm
  JOIN concept_reference_term t    ON t.concept_reference_term_id = crm.concept_reference_term_id
  JOIN concept_reference_source cs ON cs.concept_source_id = t.concept_source_id
  WHERE cs.name LIKE '%SNOMED%' OR cs.name LIKE '%RxNORM%'
) mapped ON mapped.concept_id = a.coded_allergen
WHERE a.voided = 0 AND a.coded_allergen IS NOT NULL;
