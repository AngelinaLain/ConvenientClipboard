CREATE TABLE app_users (
    id uuid PRIMARY KEY,
    username varchar(64) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('USER','ADMIN')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE storage_providers (
    id uuid PRIMARY KEY,
    name varchar(100) NOT NULL,
    endpoint text NOT NULL,
    region varchar(80) NOT NULL,
    bucket varchar(255) NOT NULL,
    access_key_encrypted text NOT NULL,
    secret_key_encrypted text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    priority integer NOT NULL DEFAULT 100,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE stored_files (
    id uuid PRIMARY KEY,
    owner_id uuid REFERENCES app_users(id),
    original_name varchar(255) NOT NULL,
    content_type varchar(255) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    object_key varchar(255) NOT NULL UNIQUE,
    share_token varchar(64) NOT NULL UNIQUE,
    permanent boolean NOT NULL DEFAULT false,
    expires_at timestamptz,
    location varchar(16) NOT NULL DEFAULT 'LOCAL' CHECK (location IN ('LOCAL','S3')),
    provider_id uuid REFERENCES storage_providers(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((permanent AND owner_id IS NOT NULL AND expires_at IS NULL) OR (NOT permanent AND expires_at IS NOT NULL)),
    CHECK ((location = 'LOCAL' AND provider_id IS NULL) OR (location = 'S3' AND provider_id IS NOT NULL))
);

CREATE INDEX idx_stored_files_owner ON stored_files(owner_id, created_at DESC);
CREATE INDEX idx_stored_files_expiry ON stored_files(expires_at) WHERE NOT permanent;
CREATE INDEX idx_stored_files_location ON stored_files(location, created_at);

CREATE TABLE app_settings (
    id integer PRIMARY KEY CHECK (id = 1),
    local_capacity_bytes bigint NOT NULL DEFAULT 10737418240 CHECK (local_capacity_bytes > 0),
    offload_threshold integer NOT NULL DEFAULT 50 CHECK (offload_threshold BETWEEN 1 AND 95)
);
INSERT INTO app_settings(id) VALUES (1);
