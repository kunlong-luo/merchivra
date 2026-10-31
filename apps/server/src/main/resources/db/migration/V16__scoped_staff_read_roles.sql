-- V16: one explicitly scoped, read-only staff role. No default grant.
-- Existing V15 invites stay OWNER-only, preserving old invitation semantics.
ALTER TABLE local_owner_invitation
  ADD COLUMN invite_role VARCHAR(32) NOT NULL DEFAULT 'OWNER',
  ADD COLUMN seller_account_id UUID;
ALTER TABLE local_owner_invitation
  ADD CONSTRAINT local_invite_role_scope_ck CHECK (
    (invite_role='OWNER' AND seller_account_id IS NULL)
    OR (invite_role='OPERATOR_READ' AND seller_account_id IS NOT NULL)
  );
ALTER TABLE local_owner_invitation
  ADD CONSTRAINT local_invite_seller_fk
    FOREIGN KEY(tenant_id,seller_account_id) REFERENCES seller_account(tenant_id,id);
-- Role bindings for read staff MUST be seller-scoped. No broad global
-- OPERATOR_READ, no OWNER-only scope and no reviewer grants via invitation.
ALTER TABLE role_binding
  ADD CONSTRAINT role_binding_read_scope_ck CHECK (
    role_code <> 'OPERATOR_READ' OR seller_account_id IS NOT NULL
  );
