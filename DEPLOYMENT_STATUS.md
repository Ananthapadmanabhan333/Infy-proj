# Deployment status

_Last updated: 5 November 2026_

| Target | Status |
|---|---|
| GitHub repository / CI | **Deployed.** Repository pushed to [`https://github.com/Ananthapadmanabhan333/Infy-proj`](https://github.com/Ananthapadmanabhan333/Infy-proj) (`main` branch). Workflow: [`.github/workflows/ci.yml`](.github/workflows/ci.yml). |
| Vercel (frontend) | **Deployed (`READY`).** Production URL: [`https://nexus360-three.vercel.app`](https://nexus360-three.vercel.app) (Project: `ananthapadmanabhan333s-projects/nexus360`, config: [`frontend/vercel.json`](frontend/vercel.json)). |
| Neon (PostgreSQL) | **Awaiting database URL.** Create a Neon PostgreSQL project and copy the direct connection string — follow [docs/NEON.md](docs/NEON.md). |
| Render (API) | **Blueprint ready (`render.yaml`).** Pre-configured with `FRONTEND_ORIGIN=https://nexus360-three.vercel.app`. Apply at `https://dashboard.render.com/blueprints` connected to `Ananthapadmanabhan333/Infy-proj`. |

## Verified locally (3 Nov 2026)

- Production jar (`mvn package`, Java 21) started **without** the local profile from a Neon-style
  `postgresql://…?sslmode=…&channel_binding=…` URL: readiness `UP` in ~9 s, Flyway validation passed, anonymous API
  call → `401`, disallowed `Origin` → `403`, security headers present.
- The same jar refuses to start without `JWT_SECRET` ("JWT_SECRET must be at least 32 characters").
- Release 2.1 (final code, 4 Nov 2026): backend 152/152, Vitest 410/410 and Playwright 21/21 (production build served by
  `vite preview`, fresh demo database) — see [PROJECT_STATUS.md §R2.1](PROJECT_STATUS.md#r21-public-landing-page-and-account-access-4-nov-2026).
- Release 2 production jar re-checked the same way: version 2.0.0, readiness `UP` in ~10 s, anonymous API and SSE
  stream → `401`, disallowed `Origin` → `403`, demo accounts disabled with no password exposed under production
  defaults. Migration V3 applied to Release 1 data is covered by `Release2MigrationTest`.

## Not verified

- Docker images (`backend/Dockerfile`, `frontend/Dockerfile`) and `docker compose up` were not built here: Docker
  Desktop was stopped at the owner's request. CI does not build images either; Render builds the backend image on
  first deploy.
- Vercel's `/api` rewrite to Render and the hosted cookie flow can only be checked on the live deployment — use the
  checklist in [DEPLOYMENT.md §10](DEPLOYMENT.md#10-post-deployment-verification).

## Remaining manual actions (account owner)

1. Create the GitHub repository and push (DEPLOYMENT.md §1).
2. Create the Neon project and copy the direct connection string (§2).
3. Apply the Render Blueprint and enter the prompted secrets (§3).
4. If the Render hostname differs from `nexus360-api.onrender.com`, update the rewrite in `frontend/vercel.json` (§4).
5. Import the repository into Vercel with root directory `frontend` (§4).
6. Set `FRONTEND_ORIGIN` on Render to the Vercel domain (§5).
7. Sign in with the bootstrap administrator, change the password, and run the verification checklist (§6, §10).
