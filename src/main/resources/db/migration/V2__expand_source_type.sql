SET LOCAL lock_timeout = '2s';

CREATE FUNCTION classify_source(value text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN CASE left(value, 3)
  WHEN 'SAT' THEN 'SATELLITE'
  WHEN 'GND' THEN 'GROUND'
  WHEN 'MOB' THEN 'MOBILE'
  ELSE 'UNKNOWN'
END;

ALTER TABLE reports ADD COLUMN source_type text;

CREATE FUNCTION set_report_source_type() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.source_type := classify_source(NEW.source);
  RETURN NEW;
END;
$$;
CREATE TRIGGER report_source_type
BEFORE INSERT OR UPDATE OF source ON reports
FOR EACH ROW EXECUTE FUNCTION set_report_source_type();

ALTER TABLE reports ADD CONSTRAINT reports_source_type_valid
  CHECK (
    source_type IS NOT NULL
    AND source_type IN ('SATELLITE', 'GROUND', 'MOBILE', 'UNKNOWN')
  -- New writes are checked immediately, while existing rows are backfilled online.
  ) NOT VALID;

-- The singleton row is a durable, restartable cursor for the external backfill step.
CREATE TABLE source_type_backfill (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  last_report_id text,
  processed bigint NOT NULL DEFAULT 0 CHECK (processed >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO source_type_backfill DEFAULT VALUES;

DO $$
BEGIN
  -- Empty installations can finalize immediately without running the backfill scripts.
  IF NOT EXISTS (SELECT 1 FROM reports LIMIT 1) THEN
    ALTER TABLE reports VALIDATE CONSTRAINT reports_source_type_valid;
    ALTER TABLE reports ALTER COLUMN source_type SET NOT NULL;
  END IF;
END;
$$;
