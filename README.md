# NEXUS 360 — Enterprise Operations Platform

NEXUS 360 is a full-stack MVP foundation for an enterprise IT operations and incident-management platform, using an Obsidian Black + Champagne Gold design system.

## Stack

- Frontend: React 18, TypeScript, Vite, Recharts, Lucide
- Backend: Java 21, Spring Boot 3.4, Spring Data JPA, Spring Security
- Database: PostgreSQL
- Local infrastructure: Docker Compose, PostgreSQL, Redis
- Frontend hosting: Vercel
- API hosting: Render Docker Web Service (or Railway)
- Managed database: Neon PostgreSQL (or another PostgreSQL provider)

## Run locally

Requirements: Node.js 20+, Java 21, Maven 3.9+, Docker.

```bash
# Terminal 1: start PostgreSQL and Redis
cd nexus360
docker compose up -d postgres redis

# Terminal 2: start API
cd backend
mvn spring-boot:run

# Terminal 3: start frontend
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. API: http://localhost:8080/api. Swagger: http://localhost:8080/swagger-ui.html.

Alternatively, run the Docker stack from the repository root:

```bash
docker compose up --build
```

Open http://localhost:3000.

## Deploy

See [`deployment/DEPLOY_TO_VERCEL_AND_RENDER.md`](deployment/DEPLOY_TO_VERCEL_AND_RENDER.md) for step-by-step deployment. The React frontend deploys to Vercel; the Java Spring Boot API must run on a backend host such as Render; PostgreSQL can run on Neon.

## Current scope and limitations

This is an interview/demo MVP foundation. It includes a polished multi-section frontend, incident CRUD API, dashboard endpoint, PostgreSQL persistence, demo seed data and Docker setup. Some screens use demo data or UI-only interactions. Authentication enforcement, durable SLA scheduling, workflow execution, production AI integration, tenant isolation and production security hardening are not complete. The current API security configuration permits unauthenticated demo access. Do not use real company data or present the app as production-secure until these gaps are implemented and tested.
