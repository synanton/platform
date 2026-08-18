CREATE TABLE IF NOT EXISTS security.roles (
    role_name    TEXT PRIMARY KEY,
    description  TEXT,
    is_system    BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS security.role_permissions (
    role_name  TEXT NOT NULL REFERENCES security.roles(role_name),
    permission TEXT NOT NULL,
    PRIMARY KEY (role_name, permission)
);

CREATE TABLE IF NOT EXISTS security.role_assignments (
    assignment_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id        TEXT NOT NULL,
    role_name         TEXT NOT NULL REFERENCES security.roles(role_name),
    identity_profile  TEXT NOT NULL,
    source            TEXT NOT NULL,
    ttl_hours         INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO security.roles (role_name, description, is_system) VALUES
  ('support_admin', 'Internal operational tooling role', true)
ON CONFLICT DO NOTHING;

INSERT INTO security.role_permissions (role_name, permission) VALUES
  ('support_admin', 'admin.internal.status'),
  ('support_admin', 'admin.internal.bundle'),
  ('support_admin', 'admin.internal.clean'),
  ('support_admin', 'admin.internal.delete'),
  ('support_admin', 'admin.internal.recrawl'),
  ('support_admin', 'admin.internal.workflow'),
  ('support_admin', 'tenant.metadata.read')
ON CONFLICT DO NOTHING;

ALTER TABLE security.api_keys
    ADD COLUMN IF NOT EXISTS key_class TEXT NOT NULL DEFAULT 'standard',
    ADD COLUMN IF NOT EXISTS ip_allowlist TEXT[],
    ADD COLUMN IF NOT EXISTS grace_until TIMESTAMPTZ;
