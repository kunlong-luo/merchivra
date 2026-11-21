-- V175-B: one-time, time-bounded key-possession challenges for tenant-enrolled WMS keys.
-- Only proves control of a private key matching a tenant-registered public key.
-- Does NOT prove independent WMS identity, receipt, inventory, or historical COGS.
CREATE TABLE finance_wms_key_challenge_v101 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 source_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 nonce_hex CHAR(64) NOT NULL CHECK(nonce_hex ~ '^[0-9a-f]{64}$'),
 canonical_sha256 CHAR(64) NOT NULL CHECK(canonical_sha256 ~ '^[0-9a-f]{64}$'),
 issued_by UUID NOT NULL,
 issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 expires_at TIMESTAMPTZ NOT NULL,
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
  CHECK(operator_identity_verified=FALSE),
 physical_receipt_verified BOOLEAN NOT NULL DEFAULT FALSE
  CHECK(physical_receipt_verified=FALSE),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,id,source_id,warehouse_id),
 UNIQUE(tenant_id,nonce_hex),
 FOREIGN KEY(tenant_id,source_id,warehouse_id)
  REFERENCES finance_wms_signing_source_v98(tenant_id,id,warehouse_id),
 FOREIGN KEY(tenant_id,issued_by) REFERENCES membership(tenant_id,user_id),
 CHECK(expires_at>issued_at AND expires_at<=issued_at+INTERVAL '15 minutes')
);
CREATE INDEX finance_wms_key_challenge_recent_v101
 ON finance_wms_key_challenge_v101(tenant_id,source_id,issued_at DESC,id DESC);

CREATE TABLE finance_wms_key_proof_v101 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 challenge_id UUID NOT NULL,
 source_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 signature_base64 VARCHAR(120) NOT NULL
  CHECK(signature_base64 ~ '^[A-Za-z0-9+/]{84,88}={0,2}$'),
 canonical_sha256 CHAR(64) NOT NULL CHECK(canonical_sha256 ~ '^[0-9a-f]{64}$'),
 result VARCHAR(32) NOT NULL DEFAULT 'KEY_POSSESSION_ONLY'
  CHECK(result='KEY_POSSESSION_ONLY'),
 verified_by UUID NOT NULL,
 verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
  CHECK(operator_identity_verified=FALSE),
 physical_receipt_verified BOOLEAN NOT NULL DEFAULT FALSE
  CHECK(physical_receipt_verified=FALSE),
 inventory_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(inventory_posted=FALSE),
 cogs_reversed BOOLEAN NOT NULL DEFAULT FALSE CHECK(cogs_reversed=FALSE),
 UNIQUE(tenant_id,challenge_id),
 FOREIGN KEY(tenant_id,challenge_id,source_id,warehouse_id)
  REFERENCES finance_wms_key_challenge_v101(tenant_id,id,source_id,warehouse_id),
 FOREIGN KEY(tenant_id,verified_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_wms_key_proof_source_v101
 ON finance_wms_key_proof_v101(tenant_id,source_id,verified_at DESC,id DESC);

-- Database defense in depth: signature verification is done in Java; PG18
-- independently refuses self-confirmation and proof of a revoked source.
CREATE FUNCTION finance_wms_key_proof_actor_v101() RETURNS trigger AS $$
DECLARE challenge_issuer UUID; source_registrar UUID; source_state VARCHAR(16);
DECLARE challenge_expiry TIMESTAMPTZ;
BEGIN
 SELECT c.issued_by,s.requested_by,s.state,c.expires_at
 INTO challenge_issuer,source_registrar,source_state,challenge_expiry
 FROM finance_wms_key_challenge_v101 c
 JOIN finance_wms_signing_source_v98 s
   ON s.tenant_id=c.tenant_id AND s.id=c.source_id
   AND s.warehouse_id=c.warehouse_id
 WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.challenge_id
   AND c.source_id=NEW.source_id AND c.warehouse_id=NEW.warehouse_id;
 IF challenge_issuer IS NULL OR source_state<>'APPROVED'
    OR now()>=challenge_expiry OR NEW.verified_by IN (challenge_issuer,source_registrar) THEN
   RAISE EXCEPTION 'Independent, unexpired approved source key proof required'
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_wms_key_proof_actor_v101
 BEFORE INSERT ON finance_wms_key_proof_v101
 FOR EACH ROW EXECUTE FUNCTION finance_wms_key_proof_actor_v101();
CREATE TRIGGER finance_wms_key_challenge_immutable_v101
 BEFORE UPDATE OR DELETE ON finance_wms_key_challenge_v101
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();
CREATE TRIGGER finance_wms_key_proof_immutable_v101
 BEFORE UPDATE OR DELETE ON finance_wms_key_proof_v101
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();
