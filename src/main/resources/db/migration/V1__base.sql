CREATE TABLE reports (
  report_id text PRIMARY KEY, asset_id text NOT NULL, observed_at timestamptz NOT NULL,
  observed_ns smallint NOT NULL, lat double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lon double precision NOT NULL CHECK (lon BETWEEN -180 AND 180), kind text, speed_kn double precision,
  heading_deg double precision, source text
);
CREATE INDEX reports_track ON reports (asset_id, observed_at, observed_ns, report_id);
CREATE TABLE positions (
  asset_id text PRIMARY KEY, report_id text NOT NULL REFERENCES reports,
  lat double precision NOT NULL, lon double precision NOT NULL
);
CREATE INDEX positions_viewport ON positions (lat, lon);
CREATE TABLE rejections (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  request_id text NOT NULL, line integer NOT NULL, code text NOT NULL, raw_line text NOT NULL
);
CREATE TABLE ingest_batches (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  received integer NOT NULL, accepted integer NOT NULL, duplicates integer NOT NULL, rejected integer NOT NULL,
  CHECK (received = accepted + duplicates + rejected)
);
CREATE TABLE alerts (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id text NOT NULL REFERENCES positions, zone_id text NOT NULL,
  trigger_report_id text NOT NULL REFERENCES reports, opened_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  status text NOT NULL DEFAULT 'OPEN', claimed_by text, claimed_at timestamptz,
  resolved_by text, resolved_at timestamptz, resolution_note text,
  CHECK (status IN ('OPEN','CLAIMED','RESOLVED')),
  CHECK ((status = 'OPEN') = (claimed_by IS NULL)),
  CHECK ((status = 'RESOLVED') = (resolved_by IS NOT NULL))
);
CREATE UNIQUE INDEX alerts_one_active ON alerts (asset_id, zone_id) WHERE status IN ('OPEN','CLAIMED');
CREATE INDEX alerts_status_page ON alerts (status, id);
CREATE TABLE audit (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, alert_id bigint NOT NULL REFERENCES alerts,
  at timestamptz NOT NULL DEFAULT clock_timestamp(), actor text NOT NULL, action text NOT NULL,
  from_status text, to_status text NOT NULL
);
CREATE INDEX audit_by_alert ON audit (alert_id, id);
CREATE FUNCTION record_alert_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO audit (alert_id,actor,action,to_status) VALUES (NEW.id,'system','OPENED',NEW.status);
  ELSIF NEW.status <> OLD.status THEN
    INSERT INTO audit (alert_id,actor,action,from_status,to_status)
    VALUES (NEW.id,current_setting('console.actor'),
      CASE NEW.status WHEN 'OPEN' THEN 'RELEASED' WHEN 'CLAIMED' THEN 'CLAIMED' ELSE 'RESOLVED' END,
      OLD.status,NEW.status);
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER audit_alert AFTER INSERT OR UPDATE ON alerts FOR EACH ROW EXECUTE FUNCTION record_alert_change();
CREATE FUNCTION forbid_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Audit entries are append-only';
END;
$$;
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON audit
FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_change();