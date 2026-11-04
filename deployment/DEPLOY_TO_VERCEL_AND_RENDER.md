# Deploying to Vercel, Render and Neon

This guide has moved and been rewritten for the completed application:

- **[DEPLOYMENT.md](../DEPLOYMENT.md)** — step-by-step Vercel + Render + Neon deployment, the `/api` proxy,
  CORS, first sign-in, AI provider, the full environment-variable reference, verification and troubleshooting.
- **[docs/NEON.md](../docs/NEON.md)** — creating and operating the Neon database.

Configuration files: [`render.yaml`](../render.yaml), [`frontend/vercel.json`](../frontend/vercel.json),
[`backend/Dockerfile`](../backend/Dockerfile), [`.env.example`](../.env.example),
[`backend/.env.example`](../backend/.env.example), [`frontend/.env.example`](../frontend/.env.example).

The limitations listed in the previous version of this file (open API, demo-only screens, no SLA scheduler or
workflow engine, `ddl-auto: update`) have been resolved; see [PROJECT_STATUS.md](../PROJECT_STATUS.md).
