# Merchivra ERP — MVP PRD (v0.1.0)

**Status:** Proposed; scope baseline for M0–M4. **Primary user:** self-hosting Amazon Seller operator with one or more accounts/marketplaces.

## Problem
Seller product/listing, order, FBA inventory and settlement data lives in separate tools. Operators need a transparent, auditable, low-cost workflow without surrendering all business data to a hosted ERP.

## MVP in scope (priority P0)
| Module | Capability | Acceptance criteria |
| --- | --- | --- |
| System | login, tenant, user and role management | deny cross-tenant access; audit changes |
| Seller account | private-app first; account/marketplace configuration | verify permissions; report authorization/health status |
| Catalog | internal SKU and marketplace listing mapping | one internal SKU can map to many seller SKUs/marketplaces |
| Orders | fetch incremental orders and order items; state changes and refunds when accessible | idempotent import, timestamps and source preserved |
| Inventory | FBA quantities with status and source timestamp | snapshot/reconcile without overwriting internal warehouse ledger |
| Costs | currency-aware manual SKU costs with effective dates | historical cost retained |
| Finance | fees and financial transactions, estimated margin | money as decimal with currency; distinguish estimate/posted/settled |
| Sync | scheduled tasks, checkpoints, retry and monitoring | backoff and per-account rate limits; failures visible |
| Insights | orders, revenue and estimated margin by store/marketplace/SKU | figures traceable to source and calculation version |
| Deployment | Docker Compose, migrations, demo/mock provider | no Amazon account required to run demo |

## Explicitly out of MVP
- Vendor Central; multi-channel integrations; shipping label purchasing.
- Automated repricing, Amazon Ads bid optimization, purchase planning, general ledger.
- SaaS billing and publicly available OAuth application approval workflows.

## Key business rules
1. Internal product SKU, seller SKU, ASIN and FNSKU are distinct identifiers; never assume 1:1 mappings.
2. Order status and financial settlement are different timelines. Order totals do not equal payouts.
3. FBA inventory API snapshots are external observations, not internal warehouse stock ledger events.
4. Persist decimal monetary values and ISO currency codes; FX conversion uses an explicit rate source/date.
5. Jobs must be idempotent, resumable, observable and safe under repeated notifications.
6. Every business record belongs to a tenant; unique constraints include tenant and source identifiers.
7. PII and restricted data are stored only if essential and permitted; minimize scope and retention.

## P0 acceptance / release gate
- Clean checkout builds; docker compose starts API, UI, database and Redis.
- Demo/mock path usable without Amazon credentials.
- Authorized self-hosted seller can sync sample or approved live products, orders and inventory.
- Replaying same sync does not create duplicate records; failed pages can resume.
- Tenant A cannot access Tenant B data through any endpoint or background job.
- Basic estimated margin can be reconciled to source events; limitations shown explicitly.
- Security checks and deployment warnings documented.

## External dependencies
Amazon SP-API registration and role approval; latest endpoint versions and usage limits must be checked at integration time. Amazon Ads API requires separate access.

## Deferred decisions
Public-app OAuth launch, tenant billing, cost allocation rules, storage of restricted PII and supported marketplaces beyond Amazon.
