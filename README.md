# Banks Fraud Detection System

[![CI](https://github.com/hamouditaha/banks/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/hamouditaha/banks/actions/workflows/ci.yml)

Digital banking system with real-time fraud detection, built as Spring Boot microservices
communicating over **Kafka**, backed entirely by **Redis** (system of record, not just a cache),
with money transfers coordinated by an **orchestration-based SAGA**.

## Architecture

```
                        ┌──────────────┐
                        │   frontend   │  :4200 (Angular)
                        └──────┬───────┘
                               │ /api
                        ┌──────▼───────┐
                        │  api-gateway │  :8080
                        └──────┬───────┘
                               │
        ┌──────────────────────┼───────────────────────┐
        │                      │                        │
┌───────▼────────┐   ┌─────────▼──────────┐   ┌─────────▼──────────┐
│ account-service │   │ orchestrator-service│   │ notification-service│
│     :8081       │   │        :8083        │   │        :8084        │
└───────┬─────────┘   └─────────┬──────────┘   └─────────┬───────────┘
        │                       │                         │
        │              ┌────────▼─────────┐               │
        │              │      Kafka       │◄──────────────┘
        │              │  (command/reply  │
        │              │  saga topics +   │
        │              │ outcome events)  │
        │              └────────▲─────────┘
        │                       │
        │            ┌──────────┴───────────┐
        │            │ fraud-detection-service│  :8082
        │            │                        │
        │            └────────────────────────┘
        │
        └──────────────────► Redis (all state) ◄───────────── all services
```

`discovery-server` (Eureka, :8761) is used by every service for registration/lookup and
by the gateway for load-balanced routing.

### Services

| Service | Responsibility |
|---|---|
| `frontend` | Angular 19 back-office UI: accounts, transfers with live saga tracking, fraud dashboard |
| `discovery-server` | Eureka service registry |
| `api-gateway` | Single entry point (CORS-enabled, OAuth2 resource server validating Keycloak JWTs), routes `/api/accounts/**`, `/api/transfers/**`, `/api/notifications/**`, `/api/fraud/**` |
| `account-service` | Owns accounts & balances (Redis), atomic debit/credit, participates in the saga |
| `fraud-detection-service` | Rule-based fraud engine (blacklist, amount thresholds, velocity), participates in the saga |
| `orchestrator-service` | Drives the transfer SAGA end-to-end, owns saga state, issues compensations |
| `notification-service` | Consumes final saga outcomes, records per-account notifications |
| `common-events` | Shared Kafka event/DTO contracts + topic names |
| `integration-tests` | End-to-end saga tests running the real services on Testcontainers Kafka + Redis (test-only module) |

### Why Redis as the system of record (not just a cache)

Every piece of durable state lives in Redis:

- **Accounts & balances** (`account-service`) - metadata as a JSON string, balance as a
  separate integer (minor units/cents) key updated with `INCRBY` (credit) or a Lua script
  that atomically checks-and-decrements (debit), so the balance never needs a read-then-write
  round trip from the app.
- **Idempotency + locking** (`account-service`) - each debit/credit/refund is idempotent on
  `sagaId:step`, guarded by a **Redisson distributed lock** per account so a redelivered Kafka
  message can never double-apply a balance change.
- **Fraud signals** (`fraud-detection-service`) - a blacklist `SET`, and per-account velocity
  counters implemented as a Redis `ZSET` sliding-window log (add now, evict everything older
  than the window, count what's left).
- **Saga state** (`orchestrator-service`) - each transfer's current step, history and outcome,
  keyed by `saga:{sagaId}`.
- **Notifications** (`notification-service`) - a capped Redis `LIST` per account.

Redis is run with `--appendonly yes` (AOF persistence) in `docker-compose.yml` so this data
survives a container restart.

### The SAGA (orchestration-based)

`orchestrator-service` owns the state machine. Each step is a Kafka command/reply pair; Kafka
messages are keyed by `sagaId`, so all messages for one saga land on the same partition and are
processed **in order** by a single consumer thread - no extra locking is needed to serialize a
saga's own steps.

```
POST /api/transfers
        │
        ▼
   DEBIT_PENDING  ──(account-service debits fromAccount)──►  ok? ──► FRAUD_CHECK_PENDING
        │ no                                                            │
        ▼                                                               ▼ approved
     FAILED                                          (fraud-detection-service scores it)
                                                                          │ rejected
                                                                          ▼
                                                                    COMPENSATING
                                                                          │
                                                          CREDIT_PENDING  │  (refund fromAccount)
                                                                │         │
                                                        ok? ────┤         ▼
                                                                │    COMPENSATED
                                                                ▼
                                                            COMPLETED

  A failed credit also triggers COMPENSATING → refund → COMPENSATED, same as a fraud rejection.
```

Terminal states (`COMPLETED`, `FAILED`, `COMPENSATED`) publish a `TransactionOutcomeEvent` that
`notification-service` turns into per-account notifications.

### Fraud rules (v1 - rule-based)

Configured in `fraud-detection-service/src/main/resources/application.yml`:

- **Blacklist** - either party blacklisted → immediate rejection (score 100).
- **Amount thresholds** - `>= 10,000` adds 40, `>= 50,000` adds 70 to the risk score.
- **Velocity** - more than 5 transfers from the same source account in a 60s sliding window adds 50.
- Rejected once the accumulated score reaches 70 (`fraud.rules.reject-score-threshold`).

Manage the blacklist live via `POST/DELETE /api/fraud/blacklist/{accountId}` to demo a rejection + compensation without changing config.
Every decision is logged (capped Redis list `fraud:evaluations`) and exposed with the active rules.

## REST API (through the gateway, :8080)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/accounts` | Open an account `{ownerName, currency, openingBalance}` |
| `GET` | `/api/accounts` | List accounts (newest first) |
| `GET` | `/api/accounts/{id}` | Account with its balance |
| `POST` | `/api/accounts/{id}/deposit` | Deposit `{amount}` (active accounts only) |
| `PATCH` | `/api/accounts/{id}/status` | Block / unblock / close `{status: ACTIVE\|BLOCKED\|CLOSED}` |
| `POST` | `/api/transfers` | Start a transfer saga `{fromAccountId, toAccountId, amount}` |
| `GET` | `/api/transfers?accountId=` | List transfers, optionally for one account |
| `GET` | `/api/transfers/{sagaId}` | Saga status and history |
| `GET` | `/api/notifications/{accountId}` | Notifications of an account |
| `GET` | `/api/fraud/rules` | Active fraud rule configuration |
| `GET` | `/api/fraud/evaluations?limit=` | Recent fraud decisions |
| `GET/POST/DELETE` | `/api/fraud/blacklist[/{accountId}]` | Manage the blacklist |

## Frontend (Angular)

The `frontend/` folder is an Angular 19 app (standalone components, signals, lazy-loaded routes):

- **Tableau de bord** - KPIs (accounts, outstanding balances, success rate, fraud rejections), latest transfers and alerts.
- **Comptes** - open accounts, search, account page with deposit, block/unblock/close, blacklist toggle,
  transfer history and notifications.
- **Virements** - new transfer form, filterable history that refreshes while sagas are in flight,
  and a detail page that follows each saga step live (debit → fraud check → credit / compensation).
- **Anti-fraude** - active rules, blacklist management, recent decisions with risk score and triggered rules.

```bash
cd frontend
npm install
npm start          # http://localhost:4200, /api is proxied to the gateway on :8080 (no login: gateway in "dev" profile)
npm run start:auth # same, with Keycloak login (gateway with authentication on)
npm run build      # production bundle in dist/frontend
```

## Running it

### Option A: Docker Compose (everything, including the services)

```bash
docker compose up --build
```

This builds each service from source (multi-stage Maven build) and starts Redis, Kafka (KRaft,
single broker), Kafka UI (http://localhost:8090), Keycloak (http://localhost:8180), Eureka
(http://localhost:8761), the gateway, all four business services and the Angular frontend served
by nginx on http://localhost:4200. Log in with `admin` / `admin` (ADMIN) or `client` / `client`
(CLIENT) - see [Security](#security).

### Option B: Infra in Docker, services from your IDE/Maven

Quick local demo without authentication: the gateway runs with the `dev` Spring profile and
`npm start` serves the frontend without Keycloak.

```bash
docker compose up redis kafka kafka-ui
mvn clean install
# then run each *Application main class, or:
mvn -pl discovery-server spring-boot:run
mvn -pl api-gateway spring-boot:run -Dspring-boot.run.profiles=dev   # no auth
# (or start Keycloak too - docker compose up keycloak - and drop the profile to keep auth on;
#  then use `npm run start:auth` for the frontend)
mvn -pl account-service spring-boot:run
mvn -pl fraud-detection-service spring-boot:run
mvn -pl orchestrator-service spring-boot:run
mvn -pl notification-service spring-boot:run
```

## Demo walkthrough

All requests below go through the gateway on `:8080`. With authentication on, first get a token
(the `banks-cli` client allows the password grant for this local demo) and send it with every
call - or run the gateway with the `dev` profile and drop the `-H "$AUTH"` lines.

```bash
# 0. Token for the admin user (needed for /api/fraud/**)
TOKEN=$(curl -s -X POST localhost:8180/realms/banks/protocol/openid-connect/token \
  -d grant_type=password -d client_id=banks-cli -d username=admin -d password=admin | jq -r .access_token)
AUTH="Authorization: Bearer $TOKEN"

# 1. Create two accounts
curl -s -X POST localhost:8080/api/accounts -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Alice","currency":"USD","openingBalance":5000}'
# => note the returned "id" as ALICE_ID

curl -s -X POST localhost:8080/api/accounts -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Bob","currency":"USD","openingBalance":0}'
# => note the returned "id" as BOB_ID

# 2. Happy path transfer
curl -s -X POST localhost:8080/api/transfers -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d "{\"fromAccountId\":\"$ALICE_ID\",\"toAccountId\":\"$BOB_ID\",\"amount\":100}"
# => note "sagaId", poll it:
curl -s -H "$AUTH" localhost:8080/api/transfers/$SAGA_ID
# status progresses: DEBIT_PENDING -> FRAUD_CHECK_PENDING -> CREDIT_PENDING -> COMPLETED

# 3. Trigger a fraud rejection + compensation
curl -s -X POST -H "$AUTH" localhost:8080/api/fraud/blacklist/$BOB_ID   # ADMIN only
curl -s -X POST localhost:8080/api/transfers -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d "{\"fromAccountId\":\"$ALICE_ID\",\"toAccountId\":\"$BOB_ID\",\"amount\":50}"
# => status ends at COMPENSATED; Alice's balance is unaffected (debited then refunded)

# 4. Check notifications
curl -s -H "$AUTH" localhost:8080/api/notifications/$ALICE_ID
```

## Tests

```bash
mvn verify                  # unit tests + Testcontainers integration tests (needs Docker running)
mvn test                    # unit tests only, no Docker needed
cd frontend && npm test     # Angular unit tests (Karma); npm run test:ci for headless Chrome
```

| Level | Where | What it proves |
|---|---|---|
| Unit - saga state machine | `orchestrator-service` `SagaOrchestratorTest`, `TransferServiceTest` | Every transition: `DEBIT_PENDING → FRAUD_CHECK_PENDING → CREDIT_PENDING → COMPLETED`; debit failure → `FAILED`; fraud rejection and credit failure → `COMPENSATING` → refund → `COMPENSATED` (or `FAILED` if the refund fails); duplicate/out-of-order replies are ignored in every state and never publish twice |
| Integration - idempotency | `account-service` `AccountCommandIdempotencyIT` (real Redis) | A debit/credit/refund command replayed with the same `sagaId:step` moves the balance once - also with 8 concurrent deliveries; a cached failure is replayed as a failure |
| End-to-end - the saga | `integration-tests` `TransferSagaEndToEndIT` (real Kafka + Redis) | Boots the real account, fraud, orchestrator and notification services, then over REST: two accounts, transfer → `COMPLETED` with correct balances; blacklisted receiver → `COMPENSATED` with the sender's balance restored; insufficient funds → `FAILED`; notifications delivered |
| Security | `api-gateway` `GatewaySecurityTest`, `GatewayDevProfileTest` | 401 without a token, 403 for `CLIENT` on `/api/fraud/**` and account status changes, `ADMIN` let through, CORS preflight, open `dev` profile |
| Frontend | `frontend/src/**/*.spec.ts` | Bearer-token interceptor (refresh, no token leak to other hosts, re-login), roles and admin guard, saga history parsing and stepper, transfer form and CSV export, role-based account actions, dashboard stats and chart |
| Fraud rules | `fraud-detection-service` `FraudRuleEngineTest` | Blacklist, amount thresholds, velocity scoring |

Integration tests are named `*IT` and run by Failsafe in `mvn verify`; JUnit 5, Mockito, AssertJ,
Awaitility and Testcontainers are used throughout. CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml))
runs `mvn -B verify` on JDK 17 and the Angular build + headless tests on every push and pull
request to `main`.

## Security

```
browser ──login (PKCE)──► Keycloak :8180 ──JWT──► browser ──Bearer JWT──► api-gateway :8080 ──► services
                                     ▲                                        │
                                     └──────── signing keys (JWKS) ───────────┘
```

- **Keycloak** (`docker-compose.yml`) imports the `banks` realm from
  [`keycloak/realm-export.json`](keycloak/realm-export.json): realm roles `ADMIN` and `CLIENT`, the
  public client `banks-frontend` (authorization code + PKCE) and `banks-cli` (password grant, for
  the curl demo only). Demo users: `admin` / `admin` (ADMIN + CLIENT) and `client` / `client`
  (CLIENT). Keycloak admin console: http://localhost:8180 (`admin` / `admin`).
- **api-gateway** is an OAuth2 resource server: it checks the JWT signature (JWKS), issuer and
  expiry, maps Keycloak realm roles to Spring authorities, and enforces:

  | Path | Access |
  |---|---|
  | `/api/fraud/**` | `ADMIN` |
  | `PATCH /api/accounts/*/status` (block, unblock, close) | `ADMIN` |
  | `/api/**` (accounts, transfers, notifications) | any authenticated user |
  | `/actuator/health`, CORS preflight | public |

- **Frontend** logs in with `keycloak-angular` before the app starts; an HTTP interceptor refreshes
  the token when needed and adds `Authorization: Bearer …` to calls to `/api` only. The fraud
  console and blacklist actions are hidden from non-admins (the gateway enforces it regardless).
- **`dev` profile**: `-Dspring-boot.run.profiles=dev` (or `GATEWAY_PROFILE=dev docker compose up`)
  turns the gateway into an open proxy for a quick local demo; pair it with `npm start`, which
  serves the frontend without Keycloak.

Configuration: `KEYCLOAK_ISSUER_URI` must match the token `iss` (the URL the browser uses),
while `KEYCLOAK_JWK_SET_URI` is where the gateway fetches keys - inside Docker that is
`http://keycloak:8180/...`. The demo passwords and realm are for local use only.

## Screenshots

Captures live in [`docs/screenshots/`](docs/screenshots/). To add:

| File | Content |
|---|---|
| `docs/screenshots/dashboard.png` | Tableau de bord - KPIs, latest transfers and fraud alerts (logged in as `admin`) |
| `docs/screenshots/saga-detail.png` | Suivi du virement - a saga's live steps, ideally a `COMPENSATED` one (debit → fraud rejected → refund) |
| `docs/screenshots/fraud.png` | Anti-fraude - active rules, blacklist and recent decisions with risk scores |

## Possible extensions

- Add a config-server for centralized configuration.
- Replace the rule-based engine with rules + an ML anomaly-scoring model.
- Propagate the user's token to the services (token relay) and make them resource servers too,
  instead of trusting the internal network.
- Add an outbox pattern in `account-service`/`orchestrator-service` if you need exactly-once
  publication guarantees stronger than the current at-least-once + idempotency-key approach.
