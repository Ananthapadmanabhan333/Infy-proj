# NEXUS 360 deployment guide: Vercel + Render + Neon

## Important architecture note

Vercel is an excellent host for the React/Vite frontend, but it does not run this project's long-lived Java Spring Boot server as a standard Vercel frontend deployment. Deploy the React frontend to **Vercel**, the Spring Boot API to **Render** (or Railway), and PostgreSQL to **Neon** (or another managed PostgreSQL provider). The browser calls the API using `VITE_API_URL`.

This repository currently contains an interview/demo MVP. Its API security configuration permits unauthenticated access, and some screens still use seeded/demo values. Do not expose sensitive or real company data. Before public production use, implement and verify authentication, authorization, tenant isolation, rate limiting, audit controls, secrets management, backups and security tests. A successful hosting deployment is not a production security certification.

## 1. Prepare the repository

1. Extract the project ZIP.
2. Create a GitHub repository.
3. From the extracted `nexus360` directory, run:

```bash
git init
git add .
git commit -m "Initial NEXUS 360 application"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/nexus360.git
git push -u origin main
```

Never commit `.env` files, passwords, API keys or production connection strings.

## 2. Create the PostgreSQL database on Neon

1. Create a project at https://neon.tech/.
2. Create a PostgreSQL database named `nexus360` (or note the database Neon created).
3. Copy the connection details from Neon.
4. Convert/use the JDBC URL format expected by Spring:

```text
jdbc:postgresql://YOUR_NEON_HOST/YOUR_DATABASE?sslmode=require
```

5. Keep the database username and password separately. Do not put credentials in source code.

The backend currently uses Hibernate schema update for this MVP. Before production, replace this with versioned Flyway migrations and test migrations against a clean database.

## 3. Deploy the Spring Boot API to Render

1. Create an account at https://render.com/.
2. Choose **New > Web Service** and connect the GitHub repository.
3. Set the Root Directory to `backend`.
4. Choose **Docker** as the runtime. Render will use `backend/Dockerfile`.
5. Set the health check path to `/actuator/health`.
6. Add environment variables:

| Key | Value |
|---|---|
| `PORT` | `8080` |
| `DATABASE_URL` | Neon JDBC URL beginning `jdbc:postgresql://` |
| `DATABASE_USER` | Neon database username |
| `DATABASE_PASSWORD` | Neon database password |
| `FRONTEND_ORIGIN` | Your Vercel domain, e.g. `https://nexus360.vercel.app` |

7. Deploy the service and wait for the build to finish.
8. Test `https://YOUR-API.onrender.com/api/health` and `https://YOUR-API.onrender.com/actuator/health`.
9. Open `https://YOUR-API.onrender.com/swagger-ui.html` to inspect the API.

If Render reports a database connection error, verify the JDBC URL, SSL requirement, database name, username and password. Do not paste secrets into public logs or chat.

## 4. Deploy the frontend to Vercel

1. Open https://vercel.com/ and import the GitHub repository.
2. Set **Root Directory** to `frontend`.
3. Framework preset: **Vite**.
4. Build command: `npm run build`.
5. Output directory: `dist`.
6. Add this environment variable in Vercel Project Settings > Environment Variables:

```text
VITE_API_URL=https://YOUR-API.onrender.com/api
```

7. Deploy.
8. After deployment, open the Vercel domain and verify the sidebar, dashboard and incident list.

The included `frontend/vercel.json` provides SPA route rewrites so client-side routes resolve to `index.html`.

## 5. CORS and redeployment

Set `FRONTEND_ORIGIN` on the API service to the exact Vercel origin, with no trailing path. If you use a Vercel preview domain, add it deliberately to the backend CORS allow-list or use a stable custom domain. Do not use wildcard CORS for a production application.

After changing Vercel environment variables, redeploy the frontend. After changing Render environment variables, restart/redeploy the API service.

## 6. Post-deployment smoke test

- [ ] Vercel site loads over HTTPS.
- [ ] Browser developer console has no uncaught errors.
- [ ] `/api/health` returns a healthy response.
- [ ] Dashboard loads data from the API where the API supports it.
- [ ] Incident list loads.
- [ ] Create a test incident and confirm it persists after page refresh.
- [ ] Resolve the test incident and confirm the backend status changes.
- [ ] Swagger UI opens.
- [ ] CORS allows the Vercel origin and rejects unapproved origins.
- [ ] No secrets are present in GitHub or frontend bundles.

## 7. What is not yet production complete

This MVP is not a finished production ITSM platform. In particular:

- Authentication/JWT enforcement is not implemented; API routes are currently open for demo use.
- Some dashboard metrics, trend charts, service desk, AI, SLA and workflow screens use demonstration data or UI-only behavior.
- There is no verified durable workflow execution engine or SLA scheduler yet.
- There is no production-grade tenant isolation, security review, backup/restore procedure or observability stack.
- Database schema currently relies on Hibernate `ddl-auto: update`; use Flyway before production.

Do not represent these capabilities as fully implemented until they are backed by tested code and persistent data.

## 8. Useful links

- Vercel Vite deployment: https://vercel.com/docs/frameworks/frontend/vite
- Render Docker deployment: https://render.com/docs/docker
- Neon connection strings: https://neon.tech/docs/connect/connect-from-any-app
- Spring Boot external configuration: https://docs.spring.io/spring-boot/reference/features/external-config.html
