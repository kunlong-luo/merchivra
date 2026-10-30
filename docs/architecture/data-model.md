# Data model proposal (not implemented)

## Proposed aggregates
| Aggregate | Planned entities |
| --- | --- |
| Identity | tenant, user, role, user_role, audit_log |
| Accounts | seller_account, seller_marketplace, authorization_metadata |
| Catalog | product, product_sku, marketplace_listing, sku_mapping |
| Orders | sales_order, sales_order_item, order_status_history, return_refund |
| Inventory | warehouse, inventory_balance, inventory_ledger, fba_inventory_snapshot |
| Finance | product_cost_history, finance_transaction, fx_rate, margin_projection |
| Sync | sync_job, sync_checkpoint, sync_attempt, dead_letter |

## Key relationships
- tenant 1:N seller_account; seller_account 1:N seller_marketplace.
- product 1:N product_sku; product_sku 1:N marketplace_listing.
- marketplace_listing has marketplace-specific seller SKU and ASIN; no ASIN-only primary key.
- sales_order belongs to tenant + seller account + marketplace + source order ID.
- fba_inventory_snapshot records observation time and status buckets; not a warehouse movement.
- finance_transaction stores source transaction ID, type, decimal amount, currency and posting time.

## Constraints
Use UUID or generated opaque IDs internally. Every external-ID uniqueness constraint must include tenant and source scope. Use `NUMERIC(20,6)` for money/amount calculations with explicit currency and rounding rules. Consider immutable ledger rows and cost effective-date windows. Migration files must be immutable after release.

**No production schema has been approved yet.** First schema PR must include an ERD, Flyway migrations and integration tests.
