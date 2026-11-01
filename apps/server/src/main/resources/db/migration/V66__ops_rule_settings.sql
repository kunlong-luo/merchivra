-- Flyway V66 / product milestone V69. Reuse V10/V67 operation findings,
-- no second alert engine, outbound notification service or new scheduler.
CREATE TABLE ops_rule_setting (
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 rule_code VARCHAR(40) NOT NULL
   CHECK(rule_code IN ('UNMAPPED_LISTING','INVENTORY_UNKNOWN','LOW_STOCK',
     'REFUND_REVIEW','FBA_INBOUND_REVIEW','ADS_REPORT_REVIEW')),
 enabled BOOLEAN NOT NULL,
 low_stock_threshold INTEGER,
 version BIGINT NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9223372036854775807),
 updated_by UUID NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,seller_marketplace_id,rule_code),
 FOREIGN KEY(tenant_id,seller_marketplace_id) REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY(tenant_id,updated_by) REFERENCES membership(tenant_id,user_id),
 CHECK ((rule_code='LOW_STOCK' AND low_stock_threshold BETWEEN 0 AND 10000)
    OR (rule_code<>'LOW_STOCK' AND low_stock_threshold IS NULL))
);
CREATE INDEX ops_rule_setting_tenant_v66 ON ops_rule_setting(tenant_id,seller_marketplace_id);
CREATE TABLE ops_rule_setting_audit (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 rule_code VARCHAR(40) NOT NULL,
 actor_user_id UUID NOT NULL,
 old_enabled BOOLEAN,
 new_enabled BOOLEAN NOT NULL,
 old_threshold INTEGER,
 new_threshold INTEGER,
 old_version BIGINT,
 new_version BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,seller_marketplace_id,rule_code)
  REFERENCES ops_rule_setting(tenant_id,seller_marketplace_id,rule_code),
 FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id),
 CHECK(new_version > 0),
 CHECK((old_version IS NULL AND old_enabled IS NULL) OR old_version IS NOT NULL)
);
CREATE INDEX ops_rule_setting_audit_recent_v66
 ON ops_rule_setting_audit(tenant_id,seller_marketplace_id,created_at DESC,id DESC);
