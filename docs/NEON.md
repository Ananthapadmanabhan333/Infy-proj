# Neon PostgreSQL for SA

SA (Enterprise Operations Platform) needs one PostgreSQL 15+ database (tested on 17). Deployment overview:
[DEPLOYMENT.md](../DEPLOYMENT.md); schema reference: [DATABASE_SCHEMA.md](../DATABASE_SCHEMA.md). Flyway owns the schema: migrations in
`backend/src/main/resources/db/migration` run automatically when the API starts, and Hibernate only validates.

## 1. Create the project

1. Sign in at <https://console.neon.tech> → **New project**.
2. Name `nexus360`, PostgreSQL **17**, region next to the API (Render Singapore → *AWS Asia Pacific (Singapore)*;
   Render Oregon → *AWS US West (Oregon)*). Latency between API and database dominates request time.
3. Keep the default database `neondb` and role (owner) — or create a dedicated role under **Roles** and give it
   ownership of the database. The role needs to create tables, indexes, sequences, functions and triggers
   (the audit-log append-only trigger), so a database owner is simplest.

## 2. Get the connection string

**Dashboard → Connect**:

- Branch `main`, database `neondb`, role as above.
- **Connection pooling: off.** Use the direct endpoint (`ep-xxxx.<region>.aws.neon.tech`, without `-pooler`).
  The API keeps its own small pool (HikariCP, `DB_POOL_MAX=8`) and Flyway takes session-level advisory locks during
  migrations, which PgBouncer's transaction mode does not support.
- Copy the URL:

```text
postgresql://<role>:<password>@ep-xxxx.<region>.aws.neon.tech/neondb?sslmode=require&channel_binding=require
```

Paste it as `DATABASE_URL` in Render's environment settings — nowhere else. The API turns it into
`jdbc:postgresql://ep-xxxx.../neondb?sslmode=require` plus username and password at startup
(`DatabaseUrlEnvironmentPostProcessor`). TLS stays mandatory (`sslmode=require`).

## 3. Connection budget

Each API instance opens at most `DB_POOL_MAX` connections (default 8, minimum idle 1). Neon's direct-connection
limit depends on the compute size (well above 8 even on the smallest); keep `DB_POOL_MAX × instances` comfortably
below it. If you scale out to many instances, switch `DATABASE_URL` to the pooled endpoint **and** run migrations
separately with the direct endpoint (e.g. `SPRING_FLYWAY_URL=jdbc:postgresql://<direct-host>/neondb?sslmode=require`
plus `SPRING_FLYWAY_USER`/`SPRING_FLYWAY_PASSWORD`).

## 4. Scale-to-zero

Neon suspends idle compute (5 minutes on the free plan). The first query afterwards waits a moment while compute
starts; the pool's 20-second connection timeout covers this. Background work — the SLA engine evaluates every minute —
keeps compute awake while the API runs. To remove wake-up latency entirely, disable scale-to-zero on a paid plan.

## 5. First start

On the first deploy the API logs:

```
Migrating schema "public" to version "1 - core schema"
Migrating schema "public" to version "2 - reference data"
…
Migrating schema "public" to version "9 - oncall major incidents pir"
Successfully applied 9 migrations
```

(Version descriptions follow the file names `V1__core_schema.sql` … `V9__oncall_major_incidents_pir.sql`; the exact log
wording comes from Flyway and was not captured from a Neon deployment.)

Check the tables in **Neon → Tables**: `incidents`, `incident_slas`, `audit_logs`, `workflows`, … and
`flyway_schema_history`. Reference data (default business-hours calendar, P1–P4 SLA policies, system settings) comes
from V2. Demo data is only added when `APP_DEMO_SEED=true`.

## 6. Operations

- **Backups / point-in-time restore**: Neon keeps history for the plan's restore window. Restore by creating a
  branch at a timestamp (**Branches → Create branch → Past point in time**) and pointing `DATABASE_URL` at it, or by
  restoring the `main` branch in place.
- **Staging**: create a branch of `main` (copy-on-write, instant) and give a staging Render service its connection
  string. Migrations run there first.
- **Schema changes**: add a new migration with the next free number (currently `V10__description.sql`); never edit an applied one (Flyway validates checksums
  and the API refuses to start on a mismatch).
- **Audit trail**: `audit_logs` is append-only, enforced by a database trigger — ordinary `UPDATE` and `DELETE`
  statements fail even for the table owner (the only permitted update clears `actor_id` when a user row is deleted). An
  owner could still disable the trigger, so keep the owner credentials out of day-to-day use where possible.
- **Rotating the password**: Neon → Roles → Reset password → update `DATABASE_URL` in Render → redeploy.

Never commit the connection string, paste it into issues or chat, or print it in logs.
