# SA — Public landing page and account access (Release 2.1)

Source of truth for the pre-login experience. Builds on Release 2 (docs/RELEASE2_SPEC.md); nothing in the
authenticated application changes except where stated.

Reference image (landing page): `C:\Users\Ananthapadmanabhan\.claude\uploads\19c94e11-ffbc-4b07-bf2c-0ca13ff71869\832091d7-image.png`

## 1. Routes

Public (no session needed): `/` (landing), `/login`, `/register`, `/forgot-password`, `/reset-password`, `/privacy`,
`/terms`, `/docs`, `/support`. Every other route stays protected exactly as in Release 2.

- `/` shows the landing page to everyone (it no longer redirects to `/dashboard`). Signed-in visitors see an
  "Open Workspace" button (→ `/dashboard`) and their avatar instead of Login / Access Workspace.
- Login, Enter Workspace and Access Workspace → `/login` (Access Workspace / Enter Workspace go straight to
  `/dashboard` when already signed in).
- Successful login → the safe `next` parameter (same-app relative path) or `/dashboard`.
- Session expiry (refresh fails) → `/login?next=<path>&reason=expired` with the notice "Your session expired. Please
  sign in again." Logout → `/login?reason=signed-out` with "You have signed out."
- Signed-in users opening `/login`, `/register`, `/forgot-password` or `/reset-password` are redirected to `/dashboard`.

## 2. Account policy (unchanged principle: no open self-registration)

Accounts remain administrator-controlled. Release 2.1 adds:

1. **Access request** — anyone can ask for access at `/register`; User administrators (SYSTEM_ADMIN, IT_ADMIN)
   review it under **Users → Access requests**.
2. **Invitation** — approving a request (or inviting someone directly) creates a single-use invitation link. The
   invitee opens `/register?invite=<token>`, sets their name and password, and can then sign in.
3. **Password reset** — `/forgot-password` records a reset request for administrators; an administrator generates a
   single-use reset link; the user sets a new password at `/reset-password?token=<token>`.

There is no e-mail delivery in this release: links are shown **once** to the administrator, who hands them over through
a trusted channel. Public responses are deliberately generic so they never reveal whether an e-mail address has an
account. All of this is stated plainly in the UI.

## 3. Data (Flyway `V4__account_access.sql`)

- `account_requests`: `id`, `type` (`ACCESS` | `PASSWORD_RESET`), `email`, `full_name`, `department_text`,
  `job_title`, `reason`, `status` (`PENDING` | `APPROVED` | `REJECTED` | `COMPLETED`), `user_id` (FK, nullable),
  `decided_by_id`, `decided_at`, `decision_note`, `request_ip`, `created_at`. Partial unique index: one PENDING request
  per (lower(email), type).
- `account_tokens`: `id`, `purpose` (`INVITE` | `PASSWORD_RESET`), `token_hash` (SHA-256, unique), `email`, `full_name`,
  `role`, `department_id`, `job_title`, `user_id`, `account_request_id`, `expires_at`, `used_at`, `revoked_at`,
  `created_by_id`, `created_at`. Raw tokens are never stored.

## 4. Public endpoints (permitAll; the login rate-limit bucket applies to every POST below)

| Method | Path | Body → Response |
|---|---|---|
| POST | `/api/auth/access-requests` | `{ fullName (2–120), email, department? (≤120), jobTitle? (≤120), reason (10–1000) }` → `202 { message }`. No new row when a PENDING request for the e-mail exists or the e-mail already has an account (same 202). Notifies USER_ADMINS (`ACCESS_REQUEST`). |
| POST | `/api/auth/forgot-password` | `{ email }` → `202 { message }`. Active account → PASSWORD_RESET request (deduplicated while pending), notifies USER_ADMINS (`PASSWORD_RESET_REQUEST`). Unknown/inactive → nothing (same 202). |
| GET | `/api/auth/invitations/{token}` | → `200 { email, fullName, role, departmentName, organisationName, expiresAt }`; invalid, used, revoked or expired → `404` with one generic message. |
| POST | `/api/auth/register` | `{ token, fullName (2–120), password }` → `201 { email }`. Password policy as change-password. Creates an active user with the invited role/department (mustChangePassword = false), marks the token used, sets the access request COMPLETED. Invalid token → 404; policy → 400 with `fieldErrors.password`; e-mail taken meanwhile → 409. |
| GET | `/api/auth/reset-password/{token}` | → `200 { email, expiresAt }` or generic `404`. |
| POST | `/api/auth/reset-password` | `{ token, newPassword }` → `204`. Sets the password, clears lockout and failed attempts, mustChangePassword = false, revokes all refresh tokens, marks the token used and the request COMPLETED. |
| GET | `/api/public/info` | → `{ organisationName, supportEmail \| null, version }` (no auth; used by the landing/support pages). |

Existing public endpoints stay: `/api/health` (status pill on the landing page), `/api/auth/login`, `/refresh`,
`/logout`, `/demo-accounts`.

## 5. Administration endpoints (USER_ADMINS = SYSTEM_ADMIN, IT_ADMIN)

| Method | Path | Body → Response |
|---|---|---|
| GET | `/api/admin/account-requests?type&status&page&size` | → `PageResponse<AccountRequestDto>`: `{ id, type, fullName, email, department, jobTitle, reason, status, createdAt, decidedByName, decidedAt, decisionNote, userId }` (newest first) |
| GET | `/api/admin/account-requests/counts` | → `{ pendingAccess, pendingReset }` |
| POST | `/api/admin/account-requests/{id}/approve` | ACCESS: `{ role, departmentId, jobTitle? }` → `{ link: "/register?invite=…", expiresAt }`. PASSWORD_RESET: `{}` → `{ link: "/reset-password?token=…", expiresAt }`. Request becomes APPROVED. 409 if not PENDING or (ACCESS) the e-mail now has an account. IT_ADMIN cannot approve with role SYSTEM_ADMIN (403). |
| POST | `/api/admin/account-requests/{id}/reject` | `{ note (required, ≤500) }` → `AccountRequestDto` (REJECTED) |
| POST | `/api/admin/invitations` | `{ email, fullName?, role, departmentId, jobTitle? }` → `{ link, expiresAt }`; 409 if the e-mail has an account; same SYSTEM_ADMIN rule |

Tokens: 32 random bytes, URL-safe base64; only the SHA-256 hash is stored; invitation valid 7 days, reset link valid
2 hours; single use; issuing a new link for the same e-mail and purpose revokes older unused ones. The raw token
appears in exactly one API response. The returned `link` is a path; the frontend prefixes `window.location.origin`.

Audit: `ACCESS_REQUESTED`, `ACCESS_REQUEST_APPROVED`, `ACCESS_REQUEST_REJECTED`, `INVITATION_CREATED`,
`USER_REGISTERED`, `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_LINK_CREATED`, `PASSWORD_RESET_COMPLETED` (details never
contain tokens or passwords). Notification types `ACCESS_REQUEST` and `PASSWORD_RESET_REQUEST` join the preference list.

## 6. Landing page content rules

- Recreate the reference layout: top navigation (logo, Platform / Solutions / Resources / Company dropdowns, system
  status pill, Login, Access Workspace), hero (eyebrow, two-line headline with gold second line, description, two CTAs,
  fact row), angled dashboard preview with a CSS globe/ambient glow behind it, seven capability cards, How It Works
  (four connected steps), Built for Enterprise Reliability strip, footer.
- **Truthfulness:** no customer counts, uptime percentages, resolution times or certifications (SOC 2, ISO 27001…)
  — none are verified. The fact row and reliability strip state implemented capabilities only (e.g. "7 roles —
  server-enforced access", "24×7 SLA engine", "Append-only audit trail", "Docker · Render · Vercel · Neon").
- The dashboard preview is built from React components with static sample data and is labelled
  "Demo preview · illustrative data". It never calls protected APIs.
- The status pill reflects `/api/health` (Operational / Degraded / Unavailable / Checking).
- Capability cards scroll to public explainer sections on the landing page (no protected data).
- Footer links go to real pages: `/docs` (product documentation overview), `/support`, `/privacy` (what the platform
  stores: accounts, operational records, audit log, one HttpOnly session cookie, no tracking cookies), `/terms`
  (clearly marked template for the operating organisation to adapt).
- Performance: the landing route must not load Recharts or the authenticated app's chunks (use SVG/CSS for the
  preview charts); below-the-fold sections may be lazy; `prefers-reduced-motion` disables animations.
- SEO: per-route `<title>` and meta description, Open Graph tags in `index.html`.
