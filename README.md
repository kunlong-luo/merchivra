# Merchivra ERP

**Open-source, self-hosted Amazon Seller ERP** — a Java 25 / Spring Boot 4.1 backend, React 19 / TypeScript frontend, and PostgreSQL 18 database.

[简体中文](README.zh-CN.md) · [Detailed installation, deployment & removal guide (中文)](docs/deployment/local-spark-db.md) · [Architecture](docs/architecture/overview.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md)

> **Pre-alpha — localhost/testing only, not production-ready.** The actual React frontend, authenticated local business workflows, and isolated PostgreSQL 18 migrations are implemented and tested. Live Amazon Seller OAuth/SP-API is **disabled by default and not yet validated with real seller credentials**. Mock data is never presented as live Amazon data.

## Services and architecture

The **default deployment uses exactly three services**:

| Service | Runtime | Purpose | Local endpoint |
| --- | --- | --- | --- |
| `web` | React 19, Vite-built SPA served by Nginx | ERP UI and same-origin `/api` reverse proxy | http://127.0.0.1:3000 |
| `server` | Java 25, Spring Boot 4.1.2 | Session/CSRF, ERP APIs, Flyway migrations | http://127.0.0.1:8080 |
| `postgres` **or existing `spark-postgres`** | PostgreSQL **18** | All business data, audit trails, uploaded evidence | Internal DB or existing PostgreSQL host |

**Redis is optional and not used by the current application runtime.** The bundled Redis container is behind the `optional-cache` Compose profile. An existing Redis, InfluxDB, or PostGIS stack does not need to be modified. Frontend is **React-only (no Vue)**; the backend is a modular monolith. More information: [dependency decisions](docs/architecture/dependency-adoption.md).

## Option A — quick start, bundled PostgreSQL 18 (three containers)

Requires Docker Engine and Docker Compose v2. From a terminal:

```bash
git clone https://github.com/kunlong-luo/merchivra.git
cd merchivra
cp .env.example .env
# Edit .env:
#   MERCHIVRA_DB_PASSWORD=<strong random password>
#   MERCHIVRA_BOOTSTRAP_USERNAME=<first admin username>
#   MERCHIVRA_BOOTSTRAP_PASSWORD=<unique password of at least 16 characters>

docker compose --env-file .env -f deploy/compose.yaml config -q
docker compose --env-file .env -f deploy/compose.yaml up -d --build
docker compose --env-file .env -f deploy/compose.yaml ps
```

Visit **http://127.0.0.1:3000** and sign in with your initial admin credentials. The app starts local **fictional Demo** mode with the bundled Compose only; do not use real Amazon credentials here. The PostgreSQL 18 named Docker volume persists across container restarts. The API ping is at http://127.0.0.1:8080/api/v1/ping; health at `/actuator/health`.

Optional unused Redis container: `docker compose --env-file .env -f deploy/compose.yaml --profile optional-cache up -d`.

## Option B — reuse an existing spark-db PostgreSQL 18 (recommended if available)

You can reuse the PostgreSQL **18/PostGIS 3.6** instance in your existing `spark-db` Compose stack, whose PostgreSQL container is `spark-postgres`, and whose shared external network is **`spark-iot-net`**.

**Never point Merchivra at the existing `iot_farm` database.** Create a dedicated `merchivra` database with an independent non-superuser `merchivra` login, leaving all existing spark-db databases, extensions, volumes, Redis, and InfluxDB untouched.

1. Start your original spark-db `postgres` service from its own Compose directory, then verify its network: `docker network inspect spark-iot-net`. The container must be attached to that network.
2. Create the new database and role via an interactive PostgreSQL session:

   ```bash
   docker exec -it spark-postgres psql -U root -d postgres
   ```

   Inside `psql` run the following **once** (choose a strong, unique password interactively):

   ```sql
   CREATE ROLE merchivra LOGIN;
   \password merchivra
   CREATE DATABASE merchivra OWNER merchivra;
   \q
   ```

   Do not recreate these objects if they already exist.

3. In the Merchivra project directory, copy `.env.example` to `.env`. Set `MERCHIVRA_DB_NAME=merchivra`, `MERCHIVRA_DB_USER=merchivra`, `MERCHIVRA_DB_PASSWORD` to the new role's password, `MERCHIVRA_BOOTSTRAP_USERNAME`, and `MERCHIVRA_BOOTSTRAP_PASSWORD` (at least 16 characters). Keep `MERCHIVRA_DEMO_ENABLED=false` unless using *fictional* local data.
4. Start **only two new containers** (web and server), which share the pre-existing PostgreSQL service as their third logical tier:

   ```bash
   docker compose --env-file .env -f deploy/compose.spark-db.yaml config -q
   docker compose --env-file .env -f deploy/compose.spark-db.yaml up -d --build
   docker compose --env-file .env -f deploy/compose.spark-db.yaml ps
   ```

The backend container connects to **`spark-postgres:5432` over `spark-iot-net`**, not to `127.0.0.1:5432` (which would refer to the backend container itself). Nothing in this deployment creates or deletes another PostgreSQL/Redis/InfluxDB container. Flyway operates **only within the separate `merchivra` database**.

Detailed instructions including database checks, permissions, networking, backup, restoration, troubleshooting and uninstall: [spark-db deployment guide](docs/deployment/local-spark-db.md).

## Local development — hot reload

Requires **Java 25, Gradle 9.2.1（项目内 Gradle Wrapper）, Node.js 24.11+, npm, PostgreSQL 18**. With your existing spark-db database mapped to the **host's** `127.0.0.1:5432`, run:

Backend terminal:

```bash
export DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=merchivra DB_USER=merchivra
export DB_PASSWORD='your-dedicated-merchivra-password'
export MERCHIVRA_BOOTSTRAP_USERNAME=your_admin
export MERCHIVRA_BOOTSTRAP_PASSWORD='your-own-16-plus-character-password'
(cd apps/server && ./gradlew bootRun)
```

Frontend terminal:

```bash
cd apps/web
npm ci
npm run dev
```

Vite usually serves http://127.0.0.1:5173 and proxies `/api` to the backend at `127.0.0.1:8080`. For a locally executed backend the DB host is **127.0.0.1**; for a containerized backend sharing the Docker network it is **spark-postgres**.

## Deploy, upgrade, stop, uninstall

Choose **exactly one** base Compose file; do not stack `compose.yaml` and `compose.spark-db.yaml` in the same invocation. `deploy/compose.production.yaml` is an optional, **not production-certified**, HTTPS/environment overlay and does *not* supply TLS termination, secret management or backup automation.

### A. Bundled DB

```bash
# Stop without deleting data
docker compose --env-file .env -f deploy/compose.yaml stop
# Rebuild and restart while keeping persistent database volume
docker compose --env-file .env -f deploy/compose.yaml up -d --build
# Remove app containers and network, KEEP database volume
docker compose --env-file .env -f deploy/compose.yaml down --remove-orphans
```

**Danger:** `docker compose --env-file .env -f deploy/compose.yaml down -v` also destroys the *bundled* PostgreSQL data volume. Only use this after intentionally choosing irreversible data deletion and verifying backups.

### B. Existing spark-db

```bash
# Stop only Merchivra, leave spark-db and its databases untouched
docker compose --env-file .env -f deploy/compose.spark-db.yaml stop
# Remove only Merchivra web/server containers and private network
docker compose --env-file .env -f deploy/compose.spark-db.yaml down --remove-orphans
```

**Never run `down -v` in your shared spark-db Compose project to uninstall Merchivra** — that could delete unrelated `iot_farm`, InfluxDB, Redis and other data. Even after removing Merchivra containers, the dedicated `merchivra` database remains in `spark-postgres`. If you explicitly wish to permanently drop that database, back it up first and then **manually** run `DROP DATABASE merchivra WITH (FORCE)` against PostgreSQL's `postgres` database. Only drop the dedicated role if it is not used elsewhere. Refer to the detailed [removal and backup procedures](docs/deployment/local-spark-db.md).

## Is the frontend done?

**The React frontend is implemented and buildable — it is not an empty scaffold.** It includes login and local tenant UI, Seller setup/connection screens, core Listings/Orders/FBA views, operations automation, team/read roles, purchasing/suppliers and warehouse lots, count and inter-warehouse discrepancy review, proof PDF/PNG/JPEG upload with server-side SHA-256, original supplier return reports, finance evidence and reconciliation views, plus a fictional Mock demo.

**However, the full ERP is not production complete**: live Seller authorization, real Amazon Seller data, third-party logistics/supplier confirmation, production file security and full cross-tenant/business E2E require additional validation. Code and API screens are not evidence of a completed real-seller integration.

Recent **Java 25 tests, React/Vitest/typecheck/build, real PostgreSQL 18 Flyway migrations and Spring Boot startup** all succeeded in [GitHub Actions run 41270021997](https://github.com/kunlong-luo/merchivra/actions/runs/41270021997). This does not imply the user's particular spark-db stack was accessed or changed.

## Tests and troubleshooting

```bash
curl -f http://127.0.0.1:8080/api/v1/ping
curl -f http://127.0.0.1:8080/actuator/health

(cd apps/server && ./gradlew test)
cd apps/web && npm ci && npm run typecheck && npm run test && npm run build
```

For errors, inspect the appropriate Compose `server` and `web` logs and verify database version, credentials, and network membership. **Do not delete database volumes to fix a connection error.**

[Deployment manual](docs/deployment/local-spark-db.md) · [Development guide](docs/development.md) · [API docs](docs/api/README.md) · [V52 private evidence limits](docs/purchasing/lot-proof-files-supplier-trace-v52.md)

## License

[Apache-2.0](LICENSE). Do not commit real Seller credentials, `.env` files, buyer information or uploaded private proof documents.
