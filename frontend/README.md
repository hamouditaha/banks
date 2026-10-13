# Banks frontend

Angular 19 back-office for the Banks platform: standalone components, signals, lazy-loaded routes,
no UI library. All calls go to the api-gateway under `/api` (dev proxy or nginx).

## Run

```bash
npm install
npm start            # http://localhost:4200, no login - needs the gateway on the "dev" profile
npm run start:auth   # same, with Keycloak login (http://localhost:8180, users admin/admin and client/client)
npm run build        # production bundle in dist/frontend (Keycloak enabled)
npm test             # Karma, watch mode
npm run test:ci      # single headless run with coverage (set CHROME_BIN if Chrome is not installed)
```

## Screens

| Route | What it does |
|---|---|
| `/dashboard` | KPIs, transfers per day (last 7 days), how transfers ended, latest transfers, fraud alerts (ADMIN) |
| `/accounts` | Open an account, search accounts |
| `/accounts/:id` | Balance, deposit, transfers and notifications of the account; block / close / blacklist (ADMIN) |
| `/transfers` | New transfer, history with status filters, CSV export of the filtered list |
| `/transfers/:id` | Live saga tracking (polls until a terminal status): stepper, journal, fraud decision (ADMIN) |
| `/fraud` | ADMIN only: active rules, blacklist, recent decisions |

Anything else shows a 404 page.

## Roles

The UI reads the Keycloak realm roles from the token (`AuthService`):

- **ADMIN** sees everything.
- **CLIENT** does not get the fraud console, the fraud widgets or the account back-office actions,
  and the UI never calls `/api/fraud/**` for them (the gateway would answer 403).

Hiding a button is not a security control: the gateway enforces the same rules on its own
(`/api/fraud/**` and account status changes are ADMIN-only).
Without Keycloak (`npm start`) the user is treated as an admin so every screen is reachable.

## Layout

```
src/app
  core/       ApiService (typed gateway client), models, auth (service, interceptor, guard),
              toasts, dashboard stats, test fixtures
  shared/     status badge, short-id pipe, bar chart, CSV helpers, toast container
  features/   one folder per screen (dashboard, accounts, transfers, fraud, not-found)
```

Styles live in `src/styles.scss` (CSS variables, light and dark themes).
