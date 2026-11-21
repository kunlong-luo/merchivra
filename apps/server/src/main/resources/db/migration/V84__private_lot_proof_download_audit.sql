-- V129: an authenticated Owner's original-file download is a tenant-bound
-- access event. This is an internal access log, not external provenance or
-- an authenticity certificate. The BYTEA payload never enters this table.
-- Existing V52 originals are retained without modification.
-- Bind the audit's proof and file together at the database level as well
-- as in the service, so an unrelated same-tenant attachment cannot be logged.
ALTER TABLE erp_local_lot_disposition_proof_file
  ADD CONSTRAINT erp_lot_proof_file_tenant_proof_id_unique
  UNIQUE(tenant_id,proof_id,id);

CREATE TABLE erp_local_lot_proof_file_download_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  proof_id UUID NOT NULL,
  file_id UUID NOT NULL,
  downloaded_by UUID NOT NULL,
  sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  downloaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id,proof_id)
    REFERENCES erp_local_lot_disposition_proof(tenant_id,id),
  FOREIGN KEY (tenant_id,proof_id,file_id)
    REFERENCES erp_local_lot_disposition_proof_file(tenant_id,proof_id,id),
  FOREIGN KEY (tenant_id,downloaded_by)
    REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX erp_lot_proof_download_audit_history_idx
  ON erp_local_lot_proof_file_download_audit
    (tenant_id,proof_id,downloaded_at DESC,id DESC);

-- The application only inserts access events; no UPDATE/DELETE endpoint exists.
-- Operations must apply their reviewed retention, database permissions and
-- backup policies separately; database ownership is not an immutability proof.
