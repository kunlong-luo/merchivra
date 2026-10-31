-- V15: owner-only one-time local-account invitations and auditable reviewer grants.
-- Do not weaken current OWNER-only API policy by introducing general STAFF access.
CREATE TABLE local_owner_invitation (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  invited_by UUID NOT NULL,
  login_name VARCHAR(64) NOT NULL
    CHECK(login_name ~ '^[A-Za-z0-9._-]{3,64}$'),
  token_sha256 CHAR(64) NOT NULL UNIQUE CHECK(token_sha256 ~ '^[0-9a-f]{64}$'),
  status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','ACCEPTED','REVOKED')),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  consumed_at TIMESTAMPTZ,
  accepted_user_id UUID REFERENCES app_user(id),
  FOREIGN KEY(tenant_id,invited_by) REFERENCES membership(tenant_id,user_id),
  UNIQUE(tenant_id,id),
  CHECK ((status='ACCEPTED') = (accepted_user_id IS NOT NULL)),
  CHECK ((status='ACCEPTED') = (consumed_at IS NOT NULL))
);
CREATE UNIQUE INDEX local_owner_one_pending_login ON local_owner_invitation(login_name)
  WHERE status='PENDING';
CREATE INDEX local_owner_invitation_tenant_recent ON local_owner_invitation(tenant_id,created_at DESC);

CREATE TABLE local_team_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  actor_user_id UUID NOT NULL,
  subject_user_id UUID,
  invitation_id UUID,
  event_code VARCHAR(28) NOT NULL
    CHECK(event_code IN ('INVITED','INVITE_REVOKED','INVITE_ACCEPTED',
                         'REVIEWER_GRANTED','REVIEWER_REVOKED','OWNER_REVOKED')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,subject_user_id) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,invitation_id) REFERENCES local_owner_invitation(tenant_id,id)
);
CREATE INDEX local_team_audit_tenant_recent ON local_team_audit(tenant_id,occurred_at DESC);
