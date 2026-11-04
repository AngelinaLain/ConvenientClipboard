-- NULL preserves the deployment's existing APP_MAX_UPLOAD_BYTES until settings are first saved.
ALTER TABLE app_settings ADD COLUMN max_upload_bytes bigint;
ALTER TABLE app_settings ADD CONSTRAINT ck_app_settings_max_upload_bytes
    CHECK (max_upload_bytes IS NULL OR max_upload_bytes BETWEEN 1048576 AND 107374182400);
