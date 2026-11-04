# Deployment status

_Last updated: 5 November 2026_

| Target | Status |
|---|---|
| **GitHub Repository / CI** | **Deployed (`main`).** [`https://github.com/Ananthapadmanabhan333/Infy-proj`](https://github.com/Ananthapadmanabhan333/Infy-proj) · Workflow: [`.github/workflows/ci.yml`](.github/workflows/ci.yml). |
| **Neon (PostgreSQL 17)** | **Deployed & Migrated (`V1`–`V13`).** Dedicated `nexus360` database created on `ep-patient-cherry-awi8okum.c-12.us-east-1.aws.neon.tech` (`us-east-1`), leaving existing databases (`neondb`) untouched. Demo dataset (28 users across 11 roles, 9 departments, 10 services, 139 incidents, CMDB, alerts, observability, digital twin, predictive models, AI agents, remediation playbooks, SecOps, FinOps, DEX) seeded. |
| **Backend API (`nexus360-api`)** | **Deployed (`READY`).** Production URL: [`https://nexus360-api.vercel.app`](https://nexus360-api.vercel.app) (Vercel Container Function via [`backend/Dockerfile.vercel`](backend/Dockerfile.vercel), pinned to `iad1` / `us-east-1` alongside Neon; Render blueprint also ready in [`render.yaml`](render.yaml)). |
| **Frontend Web App (`nexus360`)** | **Deployed (`READY`).** Production URL: [`https://nexus360-three.vercel.app`](https://nexus360-three.vercel.app) (config: [`frontend/vercel.json`](frontend/vercel.json), proxying same-origin `/api/*` to `https://nexus360-api.vercel.app/api/*`). |

## Verified on Live Production Deployment (5 Nov 2026)

- `GET https://nexus360-three.vercel.app/api/health` → `{"service":"SA API","version":"2.0.0","status":"UP","database":"UP"}`
- `GET https://nexus360-three.vercel.app/api/auth/demo-accounts` → `enabled: true`, 28 demo accounts across all 11 roles (`SYSTEM_ADMIN`, `IT_ADMIN`, `IT_MANAGER`, `INCIDENT_MANAGER`, `CHANGE_MANAGER`, `ASSET_MANAGER`, `SECURITY_ANALYST`, `FINOPS_ANALYST`, `SUPPORT_AGENT`, `EMPLOYEE`, `AUDITOR`).
- Flyway schema version `13` (`V1__core_schema.sql` through `V13__release4_finops_dex.sql`) applied and validated on Neon PostgreSQL 17.11.
