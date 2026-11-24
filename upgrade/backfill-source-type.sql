\set ON_ERROR_STOP on

CREATE OR REPLACE PROCEDURE backfill_source_types(
  page_size integer,
  pause_seconds double precision
)
LANGUAGE plpgsql AS $$
DECLARE
  last_id text;
  page_end text;
  changed bigint;
BEGIN
  IF page_size < 1 OR pause_seconds < 0 THEN
    RAISE EXCEPTION 'Invalid backfill settings';
  END IF;

  LOOP
    SELECT last_report_id INTO last_id
    FROM source_type_backfill
    WHERE singleton
    FOR UPDATE;

    SELECT max(report_id) INTO page_end
    FROM (
      SELECT report_id
      FROM reports
      WHERE last_id IS NULL OR report_id > last_id
      ORDER BY report_id
      LIMIT page_size
    ) page;

    EXIT WHEN page_end IS NULL;

    UPDATE reports
    SET source_type = classify_source(source)
    WHERE (last_id IS NULL OR report_id > last_id)
      AND report_id <= page_end
      AND source_type IS NULL;
    GET DIAGNOSTICS changed = ROW_COUNT;

    UPDATE source_type_backfill
    SET last_report_id = page_end,
        processed = processed + changed,
        updated_at = clock_timestamp()
    WHERE singleton;

    COMMIT;
    PERFORM pg_sleep(pause_seconds);
  END LOOP;
END;
$$;

SET lock_timeout = '2s';
CALL backfill_source_types(5000, 0.05);
