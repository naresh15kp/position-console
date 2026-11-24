\set ON_ERROR_STOP on

SELECT count(*) AS null_source_types
FROM reports
WHERE source_type IS NULL;

SELECT count(*) AS misclassified_source_types
FROM reports
WHERE source_type IS DISTINCT FROM classify_source(source);

SELECT last_report_id, processed, updated_at
FROM source_type_backfill
WHERE singleton;
