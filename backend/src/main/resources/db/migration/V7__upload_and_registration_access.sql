-- Keep existing deployments open until an administrator changes these switches.
ALTER TABLE app_settings ADD COLUMN allow_anonymous_uploads boolean NOT NULL DEFAULT true;
ALTER TABLE app_settings ADD COLUMN registration_enabled boolean NOT NULL DEFAULT true;
