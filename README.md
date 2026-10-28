# LogisticsConnect

## Overview

Supply chain parcel delivery hub and transit delay tracking.

Domain entities: hubs, sorting centers, regional districts.

Every class in this repo lives in a single flat package, `co.wethinkcode.logisticsconnect`. LogisticsConnect is built
as a small set of independent services, following a growth path from simple data
cleanup through synchronous REST calls to asynchronous MQ decoupling and alerting:

1. clean a messy legacy CSV export (`hubs-global.csv`) — handled by **IngestionServiceApp**
2. serve it up and act on it, via three REST services calling each other directly
   over HTTP
3. decouple the relevant services with an ActiveMQ topic (`package-status-topic`) instead of
   direct calls — shared broker setup lives in [`common/`](common)
4. raise the alarm on failure — handled by **AlertBotApp**

| Service | Folder                                        | Port | Role |
|---|-----------------------------------------------|---|---|
| IngestionServiceApp | [`ingestion-service/`](ingestion-service)     | 7050 | Parses and cleans `hubs-global.csv` |
| HubServiceApp | [`hub-service/`](hub-service)                 | 7051 | Serves provinces and sorting centers (place-name source of truth). |
| DelayStageServiceApp | [`delay-stage-service/`](delay-stage-service) | 7052 | Tracks the Transit Delay Stage (0-8, e.g. weather shutdowns). |
| TransitServiceApp | [`transit-service/`](transit-service)         | 7053 | Calculates estimated arrival windows based on hub and delay stage. |
| AlertBotApp | [`alertbot/`](alertbot)                       | 7054 | posts proactive delay notifications to public transit social media pages (simulated). |

Plus [`common/`](common) (no port) — the shared ActiveMQ broker and MQ config notes
for `package-status-topic`: Package status updates move from latency-driven RPC to bandwidth-driven messaging.

**Status:** all four stages are implemented. Stage 1 cleans and serves the CSV over REST;
stage 2 wires the three domain services together over synchronous HTTP; stage 3 replaces
transit-service's call to delay-stage-service with an ActiveMQ subscription; stage 4 adds
alertbot as a second subscriber on that same topic, raising a public notice when a hub's
delay stage crosses a threshold. See [DEVLOG.md](DEVLOG.md) for what was built and why.

## Implementation Summary

LogisticsConnect is implemented in four stages:

| Stage | Implementation | Purpose |
|---|---|---|
| 1. Data ingestion | `ingestion-service` | Cleans hub data from the CSV file and exposes it through REST endpoints. |
| 2. REST integration | `hub-service`, `delay-stage-service`, `transit-service` | Connects services through HTTP requests and calculates estimated arrival times. |
| 3. Message-based updates | `delay-stage-service`, `transit-service` | Publishes delay-stage changes to the ActiveMQ `package-status-topic`; transit subscribes to updates rather than making a direct delay-stage request. |
| 4. Alert notifications | `alertbot` | Subscribes to the same topic and records simulated public alerts when delay stages meet its configured threshold. |

The services run independently on their own ports. ActiveMQ provides the messaging connection between the publisher and subscribers.

See [DEVLOG.md](DEVLOG.md) for development notes and design decisions.

## Integration contracts

Two kinds of integration point exist in this repo: synchronous REST calls (stage 2)
and the asynchronous MQ topic (stage 3). Field/endpoint names below are illustrative
— reasonable variations are fine as long as the shape (who calls whom, with what
kind of payload) is preserved.

### REST (stage 2)

| Caller | Callee | Example | Purpose |
|---|---|---|---|
| hub-service | ingestion-service | `GET :7050/hubs` → JSON array of cleaned hub records | hub-service loads its place-name data from the cleaned CSV output instead of re-parsing it itself |
| transit-service | hub-service | `GET :7051/hubs/{hubId}` → hub/sorting-center details | transit-service needs hub location data to calculate an ETA |
| transit-service | delay-stage-service | *removed in stage 3* — replaced by the `package-status-topic` subscription below | was the synchronous stage lookup behind `GET :7053/eta/{hubId}` |
| (client) | delay-stage-service | `POST :7052/delay-stage/{hubId}` with a body like `{ "stage": 3 }` | the stage/state-change endpoint referenced in [common/README.md](common/README.md) — this is also where the stage-3 MQ publish happens |

### MQ (stage 3) — topic `package-status-topic`

Already documented in detail in [common/README.md](common/README.md): broker URL and
topic name come from the shared `co.wethinkcode.logisticsconnect.mq.MqConfig` class,
duplicated into each participating service.

- **Producer:** `delay-stage-service`, on its stage/state-change endpoint above. It
  publishes only on a real transition, and the response reports `published` so a
  broker outage is visible to the caller rather than silent.
- **Consumers:** `transit-service` (replacing its direct REST call to
  delay-stage-service) and `alertbot`. Both receive the same broadcast without knowing
  about each other — transit turns a stage into an ETA, alertbot turns it into a public
  post when it crosses its threshold.
- **Example message shape:** `{ "hubId": "H-501", "stage": 5, "timestamp": "2026-07-18T10:15:00Z" }`

Because a plain topic does not replay, transit-service holds each hub's last known
stage in memory and reads it from there. A hub with no message yet reads as stage 0, and
a change published while transit-service is disconnected is not recovered — a durable
topic with a persistent subscriber would close that gap. The upside is that an ETA now
succeeds even when delay-stage-service is down, which is the decoupling stage 3 is for.

## Project structure

```
logisticsconnect/
├── README.md
├── .gitignore
├── ingestion-service/          (port 7050)
│   ├── pom.xml
│   ├── README.md
│   └── src/main/
│       ├── java/co/wethinkcode/logisticsconnect/IngestionServiceApp.java
│       └── resources/hubs-global.csv
├── hub-service/          (port 7051)
├── delay-stage-service/          (port 7052)
├── transit-service/          (port 7053)
├── common/
│   ├── docker-compose.yml
│   └── README.md
└── alertbot/          (port 7054)
```

## Build

Requirements: Java 17+, Maven 3.8+, Docker (for the broker in `common/`).

Every folder here (`ingestion-service/`, each domain service, and `alertbot/`) is
an **independent** Maven project — there is no parent/aggregator pom. Build one at a
time, e.g.:

```
cd hub-service
mvn package
```

...or build every module in the repo in one pass from the project root:

```
find . -name pom.xml -execdir mvn -q package \;
```

## Run

```
# ingestion
cd ingestion-service && mvn package && java -jar target/ingestion-service.jar

# domain services, each in its own terminal
# terminal 1
cd hub-service && mvn package && java -jar target/hub-service.jar
# terminal 2
cd delay-stage-service && mvn package && java -jar target/delay-stage-service.jar
# terminal 3
cd transit-service && mvn package && java -jar target/transit-service.jar

# MQ broker (needed once the MQ-aware services above are wired up)
cd common && docker compose up -d

# alerting
cd alertbot && mvn package && java -jar target/alertbot.jar
```

| Service | Port |
|---|---|
| IngestionServiceApp (`ingestion-service`) | 7050 |
| HubServiceApp (`hub-service`) | 7051 |
| DelayStageServiceApp (`delay-stage-service`) | 7052 |
| TransitServiceApp (`transit-service`) | 7053 |
| AlertBotApp (`alertbot`) | 7054 |

## Testing

Run each command from the project root to test an individual service.

### Ingestion service

```bash
cd ingestion-service && mvn test
```

The ingestion service previously passed 23 tests with no failures or errors.

### Other services

Run each command separately, returning to the project root between commands:

```bash
cd hub-service && mvn test
```

```bash
cd delay-stage-service && mvn test
```

```bash
cd transit-service && mvn test
```

```bash
cd alertbot && mvn test
```

Check the Maven summary for the actual result of each module.

## API Demo

Start ActiveMQ before starting the services:

```bash
docker compose -f common/docker-compose.yml up -d
docker ps
```

Start each service in its own terminal. Run these commands from the project root, one service per terminal:

```bash
cd ingestion-service && mvn package && java -jar target/ingestion-service.jar
```

```bash
cd hub-service && mvn package && java -jar target/hub-service.jar
```

```bash
cd delay-stage-service && mvn package && java -jar target/delay-stage-service.jar
```

```bash
cd transit-service && mvn package && java -jar target/transit-service.jar
```

```bash
cd alertbot && mvn package && java -jar target/alertbot.jar
```

Keep each terminal open while its service runs. If a port is already in use, check whether that service is already running before starting another instance.

### Check the endpoints

Run these requests from a separate terminal:

**Ingestion service — port 7050**

```bash
curl http://localhost:7050/health
curl http://localhost:7050/hubs
curl http://localhost:7050/hubs/H-500
```

**Hub service — port 7051**

```bash
curl http://localhost:7051/health
curl http://localhost:7051/hubs
curl http://localhost:7051/hubs/H-500
```

**Delay-stage service — port 7052**

```bash
curl http://localhost:7052/health
curl http://localhost:7052/delay-stage
curl http://localhost:7052/delay-stage/H-500
```

Send a stage update to test the publisher/subscriber flow:

```bash
curl -X POST http://localhost:7052/delay-stage/H-500 \
  -H 'Content-Type: application/json' \
  -d '{"stage":3}'
```

**Transit service — port 7053**

```bash
curl http://localhost:7053/health
curl http://localhost:7053/eta/H-500
curl http://localhost:7053/delay-stages
```

**AlertBot — port 7054**

```bash
curl http://localhost:7054/health
curl http://localhost:7054/alerts
curl http://localhost:7054/watched
curl http://localhost:7054/threshold
```

The exact responses depend on the running application and its data. Use them to verify the actual behaviour during your demo.

### ActiveMQ

The ActiveMQ web console is available at:

http://localhost:8161

The OpenWire messaging port is `61616`.

## Stage 3 and Stage 4 Notes

- The delay-stage service publishes stage updates to the `package-status-topic` topic.
- The transit service and AlertBot subscribe to that topic.
- Transit maintains an in-memory cache of received stages for ETA calculations.
- AlertBot checks received updates against its configured threshold and records alerts.
- AlertBot simulates public notifications; this does not mean that real public messaging services are contacted.
- Because the stage cache is in memory, updates missed while a subscriber is disconnected may not be recovered automatically.

Verification_code
WTC-JQWR56CF

