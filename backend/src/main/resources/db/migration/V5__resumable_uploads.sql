CREATE TABLE upload_tasks (
    id uuid PRIMARY KEY,
    owner_id uuid REFERENCES app_users(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL,
    original_name varchar(255) NOT NULL,
    content_type varchar(255) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 107374182400),
    permanent boolean NOT NULL,
    chunk_hashes text NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'UPLOADING'
        CHECK (state IN ('UPLOADING','COMPLETED','CANCELLED','EXPIRED')),
    expires_at timestamptz NOT NULL,
    file_snapshot text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (NOT permanent OR owner_id IS NOT NULL),
    CHECK ((state='COMPLETED') = (file_snapshot IS NOT NULL))
);
CREATE INDEX idx_upload_tasks_expiry ON upload_tasks(state, expires_at);
CREATE TABLE upload_chunks (
    task_id uuid NOT NULL REFERENCES upload_tasks(id) ON DELETE CASCADE,
    chunk_index integer NOT NULL CHECK (chunk_index >= 0),
    size_bytes bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 8388608),
    sha256 char(64) NOT NULL,
    PRIMARY KEY (task_id, chunk_index)
);
