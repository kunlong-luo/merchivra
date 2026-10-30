# Architecture — modular monolith first

## Initial technology
- Java 21, Spring Boot 3.5, Maven; Vue 3, TypeScript, Vite.
- PostgreSQL as system of record, Flyway for migrations.
- Redis reserved for cache/rate-limit primitives; queue complexity deferred.
- Docker Compose for development; GitHub Actions for CI.

## Logical modules
1. **identity:** users, roles, tenants, authorization, audit
2. **marketplace:** seller accounts, marketplaces, authorization and adapters
3. **catalog:** internal SKU, listing, ASIN/Seller SKU relations
4. **orders:** external order facts, order items and order status history
5. **inventory:** stock snapshots, warehouses and ledger
6. **finance:** financial events, costs, currency, estimated margins
7. **sync:** job orchestration, API quotas, checkpoint, retry and replay

M0 contains a runnable server/web scaffold only; these domain modules are **planned**, not implemented.

## Integration contract
Domain services depend on ports such as `MarketplaceOrderGateway`, `MarketplaceCatalogGateway`, `MarketplaceInventoryGateway`. Amazon SP-API adapters implement the ports. Keep Amazon HTTP DTOs out of domain entities.

## Sync pipeline
Schedule/notification → durable job → quota limiter → adapter → raw metadata/audit → validate/normalize → idempotent upsert → projections/reconciliation. Store per-account and per-marketplace checkpoints with overlap windows, retries and dead-letter visibility.

## Multi-tenancy
Tenant ID is mandatory on every mutable business entity. Enforce tenant context centrally and use tenant-aware unique constraints. Never trust client-supplied tenant IDs. Test negative access scenarios before production use.

## Security boundaries
Credentials need encrypted storage, server-side access controls, no logs containing secrets or PII. Amazon roles and data-use policies govern what may be collected. The M0 ping endpoint is not an authorization example.

## Reliability
- Idempotent ingestion keyed by source and tenant.
- Keep raw source event identifiers and source update timestamps.
- Money uses decimal with original currency.
- Outbox/inbox patterns can be introduced when transactional events are needed.
- Instrument API rate limit, task duration, failure count and sync lag.

See [data model](data-model.md) and [MVP PRD](../requirements/mvp.md).
