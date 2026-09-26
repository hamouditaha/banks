# Banks Fraud Detection System

Digital banking system with real-time fraud detection, built as Spring Boot microservices
communicating over **Kafka**, backed entirely by **Redis** (system of record, not just a cache),
with money transfers coordinated by an **orchestration-based SAGA**.

## Architecture

```
                        ┌──────────────┐
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
| `discovery-server` | Eureka service registry |
| `api-gateway` | Single entry point, routes `/api/accounts/**`, `/api/transfers/**`, `/api/notifications/**` |
| `account-service` | Owns accounts & balances (Redis), atomic debit/credit, participates in the saga |
| `fraud-detection-service` | Rule-based fraud engine (blacklist, amount thresholds, velocity), participates in the saga |
| `orchestrator-service` | Drives the transfer SAGA end-to-end, owns saga state, issues compensations |
| `notification-service` | Consumes final saga outcomes, records per-account notifications |
| `common-events` | Shared Kafka event/DTO contracts + topic names |

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

Manage the blacklist live via `POST/DELETE /api/fraud/blacklist/{accountId}` (fraud-detection-service, :8082) to demo a rejection + compensation without changing config.

## Running it

### Option A: Docker Compose (everything, including the services)

```bash
docker compose up --build
```

This builds each service from source (multi-stage Maven build) and starts Redis, Kafka (KRaft,
single broker), Kafka UI (http://localhost:8090), Eureka (http://localhost:8761), the gateway
and all four business services.

### Option B: Infra in Docker, services from your IDE/Maven

```bash
docker compose up redis kafka kafka-ui
mvn clean install
# then run each *Application main class, or:
mvn -pl discovery-server spring-boot:run
mvn -pl api-gateway spring-boot:run
mvn -pl account-service spring-boot:run
mvn -pl fraud-detection-service spring-boot:run
mvn -pl orchestrator-service spring-boot:run
mvn -pl notification-service spring-boot:run
```

## Demo walkthrough

All requests below go through the gateway on `:8080`.

```bash
# 1. Create two accounts
curl -s -X POST localhost:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Alice","currency":"USD","openingBalance":5000}'
# => note the returned "id" as ALICE_ID

curl -s -X POST localhost:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Bob","currency":"USD","openingBalance":0}'
# => note the returned "id" as BOB_ID

# 2. Happy path transfer
curl -s -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d "{\"fromAccountId\":\"$ALICE_ID\",\"toAccountId\":\"$BOB_ID\",\"amount\":100}"
# => note "sagaId", poll it:
curl -s localhost:8080/api/transfers/$SAGA_ID
# status progresses: DEBIT_PENDING -> FRAUD_CHECK_PENDING -> CREDIT_PENDING -> COMPLETED

# 3. Trigger a fraud rejection + compensation
curl -s -X POST localhost:8082/api/fraud/blacklist/$BOB_ID
curl -s -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d "{\"fromAccountId\":\"$ALICE_ID\",\"toAccountId\":\"$BOB_ID\",\"amount\":50}"
# => status ends at COMPENSATED; Alice's balance is unaffected (debited then refunded)

# 4. Check notifications
curl -s localhost:8080/api/notifications/$ALICE_ID
```

## Possible extensions

- Add a config-server for centralized configuration.
- Replace the rule-based engine with rules + an ML anomaly-scoring model.
- Add Testcontainers-based integration tests (spin up real Redis + Kafka per test run).
- Add an outbox pattern in `account-service`/`orchestrator-service` if you need exactly-once
  publication guarantees stronger than the current at-least-once + idempotency-key approach.
