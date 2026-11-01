-- V59: enforce observation and import batch share one tenant+Marketplace
-- in the database, not just current Java caller SQL.
ALTER TABLE bank_payout_import_batch
  ADD CONSTRAINT bank_payout_import_tenant_market_id_v59
  UNIQUE(tenant_id,seller_marketplace_id,id);
ALTER TABLE bank_payout_observation
  ADD CONSTRAINT bank_payout_observation_batch_market_fk_v59
  FOREIGN KEY(tenant_id,seller_marketplace_id,batch_id)
  REFERENCES bank_payout_import_batch(tenant_id,seller_marketplace_id,id);
