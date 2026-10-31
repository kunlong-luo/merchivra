-- Catalog's first actual owner-operated CRUD/audit flow.
ALTER TABLE product_sku
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE catalog_change_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  resource_type VARCHAR(24) NOT NULL CHECK(resource_type IN ('SKU','LISTING_MAPPING')),
  resource_id UUID NOT NULL,
  event_type VARCHAR(24) NOT NULL
    CHECK(event_type IN ('SKU_CREATED','SKU_RENAMED','SKU_ARCHIVED','SKU_RESTORED',
                        'LISTING_MAPPED','LISTING_UNMAPPED')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX catalog_change_audit_recent_idx
  ON catalog_change_audit(tenant_id,occurred_at DESC,id DESC);
