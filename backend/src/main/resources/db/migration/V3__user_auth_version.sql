-- Incremented when credentials or access change so existing JWTs cannot regain access.
ALTER TABLE app_users ADD COLUMN auth_version bigint NOT NULL DEFAULT 0;
