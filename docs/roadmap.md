# Roadmap

## M0 — foundation
- [x] Repository baseline and open-source documentation (in foundation PR)
- [x] API/UI scaffold and local Docker Compose (in foundation PR)
- [x] CI workflow definition (in foundation PR)
- [ ] Verify GitHub Actions passing on the PR
- [ ] Formalize core ERD and auth/tenancy design

## M1 — connections and reliable synchronization
- [ ] Amazon SP-API permission and authorization discovery
- [ ] Private app connection flow and encrypted credentials
- [ ] Mock marketplace adapter
- [ ] Persistent sync jobs, rate limit, pagination, checkpoints and replay

## M2 — operational data
- [ ] Listing and internal SKU mapping
- [ ] Orders and order-item imports
- [ ] FBA inventory snapshot and alerts

## M3 — finance and dashboard
- [ ] Effective-dated product costs
- [ ] Financial-event ingestion and reconciliation
- [ ] SKU/store estimated margin with transparent assumptions

## M4 — production readiness
- [ ] RBAC/tenant isolation and security testing
- [ ] Audit logs, observability, deployment/backup procedures
- [ ] Beta usage validation and v0.1.0 release

Dates depend on developer capacity and Amazon approvals; the above is a sequence, not a guarantee.
