ALTER TABLE storage_providers
    ADD COLUMN type varchar(16) NOT NULL DEFAULT 'S3',
    ADD COLUMN mount_path text,
    ADD COLUMN mount_id varchar(128),
    ALTER COLUMN endpoint DROP NOT NULL,
    ALTER COLUMN region DROP NOT NULL,
    ALTER COLUMN bucket DROP NOT NULL,
    ALTER COLUMN access_key_encrypted DROP NOT NULL,
    ALTER COLUMN secret_key_encrypted DROP NOT NULL;

ALTER TABLE storage_providers ADD CONSTRAINT ck_storage_provider_type CHECK (type IN ('S3','NFS','SMB'));
ALTER TABLE storage_providers ADD CONSTRAINT ck_storage_provider_configuration CHECK (
    (type = 'S3' AND endpoint IS NOT NULL AND region IS NOT NULL AND bucket IS NOT NULL
        AND access_key_encrypted IS NOT NULL AND secret_key_encrypted IS NOT NULL AND mount_path IS NULL AND mount_id IS NULL)
    OR (type IN ('NFS','SMB') AND mount_path IS NOT NULL AND mount_id IS NOT NULL
        AND endpoint IS NULL AND region IS NULL AND bucket IS NULL AND access_key_encrypted IS NULL AND secret_key_encrypted IS NULL)
);

-- V1 used automatically generated names: identify only checks that reference location.
DO $$
DECLARE existing_check record;
BEGIN
    FOR existing_check IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'stored_files'::regclass AND contype = 'c'
            AND pg_get_constraintdef(oid) LIKE '%location%'
    LOOP
        EXECUTE format('ALTER TABLE stored_files DROP CONSTRAINT %I', existing_check.conname);
    END LOOP;
END $$;

ALTER TABLE stored_files ADD CONSTRAINT ck_stored_files_location CHECK (location IN ('LOCAL','S3','NFS','SMB'));
ALTER TABLE stored_files ADD CONSTRAINT ck_stored_files_location_provider CHECK (
    (location = 'LOCAL' AND provider_id IS NULL)
    OR (location IN ('S3','NFS','SMB') AND provider_id IS NOT NULL)
);
