CREATE TABLE assets (
  asset_id text PRIMARY KEY,
  current_report_id text,
  current_observed_at timestamptz,
  current_observed_ns smallint,
  lat double precision,
  lon double precision,
  CHECK (current_observed_ns IS NULL OR current_observed_ns BETWEEN 0 AND 999),
  CHECK (
    (current_report_id IS NULL AND current_observed_at IS NULL AND current_observed_ns IS NULL
      AND lat IS NULL AND lon IS NULL)
    OR
    (current_report_id IS NOT NULL AND current_observed_at IS NOT NULL AND current_observed_ns IS NOT NULL
      AND lat BETWEEN -90 AND 90 AND lon BETWEEN -180 AND 180)
  )
);

CREATE TABLE reports (
  report_id text PRIMARY KEY,
  asset_id text NOT NULL REFERENCES assets DEFERRABLE INITIALLY DEFERRED,
  observed_at timestamptz NOT NULL,
  observed_ns smallint NOT NULL CHECK (observed_ns BETWEEN 0 AND 999),
  lat double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lon double precision NOT NULL CHECK (lon BETWEEN -180 AND 180),
  kind text,
  speed_kn double precision,
  heading_deg double precision,
  source text
);

ALTER TABLE assets ADD CONSTRAINT assets_current_report
  FOREIGN KEY (current_report_id)
  REFERENCES reports (report_id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION verify_asset_current_report() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.current_report_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM reports
    WHERE report_id = NEW.current_report_id AND asset_id = NEW.asset_id
  ) THEN
    RAISE EXCEPTION 'Current report does not belong to asset';
  END IF;
  RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER asset_current_report_matches
AFTER INSERT OR UPDATE OF current_report_id ON assets
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION verify_asset_current_report();

CREATE INDEX reports_track
  ON reports (asset_id, observed_at, observed_ns, report_id);
CREATE INDEX assets_viewport
  ON assets (lat, lon, asset_id)
  WHERE current_report_id IS NOT NULL;

CREATE TABLE ingest_rejections (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  request_id text NOT NULL,
  line integer NOT NULL CHECK (line > 0),
  code text NOT NULL CHECK (code IN (
    'MALFORMED_JSON', 'MISSING_FIELD', 'INVALID_COORDINATE', 'INVALID_TIMESTAMP', 'INVALID_FIELD'
  )),
  raw_line text NOT NULL
);
CREATE INDEX ingest_rejections_by_request ON ingest_rejections (request_id, line);
CREATE INDEX ingest_rejections_by_time ON ingest_rejections (received_at);

CREATE TABLE ingest_batches (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  received integer NOT NULL CHECK (received >= 0),
  accepted integer NOT NULL CHECK (accepted >= 0),
  duplicates integer NOT NULL CHECK (duplicates >= 0),
  rejected integer NOT NULL CHECK (rejected >= 0),
  CHECK (received = accepted + duplicates + rejected)
);

CREATE TABLE alerts (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id text NOT NULL REFERENCES assets,
  zone_id text NOT NULL,
  trigger_report_id text NOT NULL REFERENCES reports,
  opened_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  status text NOT NULL DEFAULT 'OPEN',
  claimed_by text,
  claimed_at timestamptz,
  resolved_by text,
  resolved_at timestamptz,
  resolution_note text,
  CHECK (status IN ('OPEN', 'CLAIMED', 'RESOLVED')),
  CHECK (
    (status = 'OPEN'
      AND claimed_by IS NULL AND claimed_at IS NULL
      AND resolved_by IS NULL AND resolved_at IS NULL AND resolution_note IS NULL)
    OR
    (status = 'CLAIMED'
      AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL
      AND resolved_by IS NULL AND resolved_at IS NULL AND resolution_note IS NULL)
    OR
    (status = 'RESOLVED'
      AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL
      AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL
      AND char_length(btrim(resolution_note)) BETWEEN 1 AND 500)
  )
);
CREATE UNIQUE INDEX alerts_one_active
  ON alerts (asset_id, zone_id)
  WHERE status IN ('OPEN', 'CLAIMED');
CREATE INDEX alerts_status_page ON alerts (status, id);
CREATE INDEX alerts_asset_page ON alerts (asset_id, id);
CREATE INDEX alerts_zone_page ON alerts (zone_id, id);

CREATE TABLE alert_audit (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  alert_id bigint NOT NULL REFERENCES alerts,
  at timestamptz NOT NULL DEFAULT clock_timestamp(),
  actor text NOT NULL,
  action text NOT NULL CHECK (action IN ('OPENED', 'CLAIMED', 'RELEASED', 'RESOLVED')),
  from_status text,
  to_status text NOT NULL CHECK (to_status IN ('OPEN', 'CLAIMED', 'RESOLVED'))
);
CREATE INDEX alert_audit_by_alert ON alert_audit (alert_id, id);

CREATE FUNCTION record_alert_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  change_actor text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO alert_audit (alert_id, actor, action, to_status)
    VALUES (NEW.id, 'system', 'OPENED', NEW.status);
  ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
      (OLD.status = 'OPEN' AND NEW.status = 'CLAIMED')
      OR (OLD.status = 'CLAIMED' AND NEW.status IN ('OPEN', 'RESOLVED'))
    ) THEN
      RAISE EXCEPTION 'Invalid alert transition';
    END IF;
    change_actor := current_setting('console.actor', true);
    IF change_actor IS NULL OR change_actor = '' THEN
      RAISE EXCEPTION 'Missing alert actor';
    END IF;
    INSERT INTO alert_audit (alert_id, actor, action, from_status, to_status)
    VALUES (
      NEW.id,
      change_actor,
      CASE NEW.status WHEN 'OPEN' THEN 'RELEASED' WHEN 'CLAIMED' THEN 'CLAIMED' ELSE 'RESOLVED' END,
      OLD.status,
      NEW.status
    );
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER audit_alert
AFTER INSERT OR UPDATE ON alerts
FOR EACH ROW EXECUTE FUNCTION record_alert_change();

CREATE FUNCTION forbid_alert_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Alert audit entries are append-only';
END;
$$;
CREATE TRIGGER alert_audit_no_row_change
BEFORE UPDATE OR DELETE ON alert_audit
FOR EACH ROW EXECUTE FUNCTION forbid_alert_audit_change();
CREATE TRIGGER alert_audit_no_truncate
BEFORE TRUNCATE ON alert_audit
FOR EACH STATEMENT EXECUTE FUNCTION forbid_alert_audit_change();
