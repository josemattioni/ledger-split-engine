CREATE TABLE accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE
        CHECK (code ~ '^[a-z][a-z0-9_]*(:[a-z0-9_-]+)?$'),
    owner_ref VARCHAR(100) NULL
        CHECK (owner_ref IS NULL OR (owner_ref = trim(owner_ref) AND length(owner_ref) > 0)),
    type VARCHAR(20) NOT NULL
        CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    currency VARCHAR(3) NOT NULL DEFAULT 'BRL'
        CHECK (currency IN ('BRL')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
