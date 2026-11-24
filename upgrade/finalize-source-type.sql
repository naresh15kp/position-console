\set ON_ERROR_STOP on

SET lock_timeout = '2s';
ALTER TABLE reports VALIDATE CONSTRAINT reports_source_type_valid;
ALTER TABLE reports ALTER COLUMN source_type SET NOT NULL;
