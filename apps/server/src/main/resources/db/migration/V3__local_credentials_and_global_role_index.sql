-- Local, single-origin authentication foundation. No default users/passwords.
-- This does not approve an external identity provider or real Amazon credentials.
CREATE TABLE local_credential (
  user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  login_name VARCHAR(64) NOT NULL UNIQUE
    CHECK (login_name ~ '^[A-Za-z0-9._-]{3,64}$'),
  password_hash VARCHAR(255) NOT NULL CHECK (length(password_hash) >= 50),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Prevent duplicate tenant-wide bindings when seller_account_id IS NULL.
CREATE UNIQUE INDEX role_binding_global_unique
  ON role_binding(tenant_id, user_id, role_code)
  WHERE seller_account_id IS NULL;
