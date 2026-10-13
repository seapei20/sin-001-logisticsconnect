# Dev Log

Running record of what was built, when, and why. Written as we go — this is the
source material for the final debrief/PR description.

## Sep 1 — Stage 1 kickoff

### Implemented `Hub.java` Domain Model
- Standardized the core `Hub` model (`hubId`, `province`, `sortingCenter`, `active`).
- Overrode `equals()` and `hashCode()` to support value-based equality checking and hash-based collection operations (`Map`/`Set`) necessary for deduplication.
- Implemented `toString()` to facilitate clear logging and debugging across services.

### Created `CsvCleaner.java` Pipeline
- **Parsing:** Integrated OpenCSV (`CSVReaderBuilder`) to safely read and process `hubs-global.csv`.
- **Sanitization & Padding:** Built `collapseSpaces()` to trim whitespace padding and compress internal consecutive spaces into a single space.
- **Casing & Normalization:**
  - Upper-cased primary keys (`hubId`).
  - Standardized facility names using word title-casing (`capitalizeWords()`).
  - Normalized boolean flags (`Y`, `YES`, `1`, `true` $\rightarrow$ `true`; `N`, `NO`, `0`, `false` $\rightarrow$ `false`).
- **Province Aliasing:** Built a lookup map (`PROVINCE_ALIASES`) to consolidate regional variations (e.g., `kwa-zulu natal` and `kwazulu natal` $\rightarrow$ `KwaZulu-Natal`). Cleaned missing or placeholder provinces to default to `"Unknown"`.
- **Validation & Filtering:** Ignored blank lines or placeholder entries (`N/A`, `null`, `unknown`).

### Deduplication Strategy
- Used a `LinkedHashMap` keyed by normalized `hubId` to retain insertion order.
- Utilized `Map.merge()` with a custom `mergeHubs()` resolver:
  1. Overwrites `"Unknown"` provinces if duplicate records provide valid location data.
  2. Evaluates `active` flags using logical OR (`incoming.isActive() || existing.isActive()`) so a facility remains marked active if any duplicate record reports it as operational.

### REST Exposure (`IngestionServiceApp.java`)
- Extended `IngestionServiceApp` via Javalin to serve cleaned records:
  - `GET /hubs`: Returns the full cleaned dataset as JSON.
  - `GET /hubs/{hubId}`: Case-insensitive lookup returning standard `200 OK` JSON or `404 Not Found`.

## Sep 29 — Stage 2: wiring the REST services

Replaced the scaffold TODOs with real domain endpoints in all three services, plus the
synchronous calls between them described in the root README's integration contracts.

### `hub-service` — place names from ingestion output
- Added `IngestionClient` to pull `GET :7050/hubs` over `java.net.http.HttpClient`, with
  a 3s connect / 5s request timeout and a non-200 check that raises `IOException`.
- `HubServiceApp` now caches the cleaned hub list in a `volatile List<Hub>` rather than
  re-parsing the CSV, so hub-service is a reader of ingestion output instead of a second
  parser of the same file.
- Startup is decoupled from ingestion being up: a daemon thread retries the fetch every 3s
  until the first load succeeds, so service start order no longer matters.
- Endpoints: `GET /hubs`, `GET /hubs/{hubId}` (upper-cased path param, `404` on miss), and
  `POST /refresh` to force a reload. Both read paths lazily fetch if the cache is still
  empty and return `503` if ingestion is unreachable, instead of serving an empty list.

### `delay-stage-service` — stage state and validation
- Backed stages with a `ConcurrentHashMap<String, Integer>`, since Javalin serves requests
  off multiple threads and stage writes are not single-threaded.
- Endpoints: `GET /delay-stage` (all), `GET /delay-stage/{hubId}` (defaults to stage 0 for
  an unseen hub, so transit-service can always resolve a stage), and `POST /delay-stage/{hubId}`.
- Validates the request body via a `SetStageRequest` record and rejects anything outside 0-8
  with a `400`, distinguishing a bad body from a bad value.

### `transit-service` — ETA from hub data plus delay stage
- `HubClient` and `DelayStageClient` mirror `IngestionClient`: same JDK HTTP client, same
  timeout posture, same non-200 → `IOException` convention. `HubClient` maps `404` to
  `Optional.empty()` so an unknown hub is a normal outcome rather than an error.
- `GET /eta/{hubId}` calls hub-service for location data and delay-stage-service for the
  current stage, then returns a window built from a fixed `DELAY_MINUTES_BY_STAGE` table
  (stage 0 = no delay through stage 8 = 24h) plus a deterministic per-hub base transit time
  derived from the hubId hash, which keeps the response stable across repeat calls.
- Distinct status codes per failure mode: `404` unknown hub, `502` when either upstream is
  unreachable, so a caller can tell "no such hub" from "cannot reach hub-service".

### Verified manually
Built all four modules and ran the stack end to end: all `/health` endpoints OK, 18 hubs
flowing ingestion → hub-service, `GET :7053/eta/H-500` returning a stage-3 ETA of 536 minutes,
`404` on an unknown hub, `400` on `{"stage":99}`, and case-insensitive hub lookups resolving.

### Stage 3 seams left in place
Both MQ integration points were marked with an `MQ TODO` comment at the exact line where the
behaviour changes: `DelayStageServiceApp.setStage` (publish `{hubId, stage, timestamp}` on
change) and `TransitServiceApp.etaForHub` (read the stage from the subscription instead of the
`DelayStageClient` call). Stage 3 was scoped to replace one call and add one publish, with
no reshaping of the payloads — which is what happened, next section.

## Sep 29 — Stage 3: decoupling with `package-status-topic`

Filled in both `MQ TODO` seams. `delay-stage-service` now broadcasts stage changes, and
`transit-service` reads stages from a subscription rather than a REST call.

### Producer — `delay-stage-service`
- Added `PackageStatusMessage` (`hubId`, `stage`, `timestamp`) and `StagePublisher`.
- The publisher connects lazily and holds one `Session`/`MessageProducer` on a single
  daemon thread, so JMS objects are only touched by that one thread.
- Publishes only on a real transition. Re-posting the stage a hub is already at returns
  `published: false` — it would tell consumers nothing and re-stamp the timestamp.
- The in-memory stage map stays the source of truth, so a broker outage never loses a
  stage change. The response reports `published` so the caller can see the degradation:
  `{"hubId":"H-500","stage":3,"previousStage":0,"published":true}`.
- Also moved the body parse into its own `try` so a publish failure can't be misreported
  as a `400` by the surrounding catch-all.

### Consumer — `transit-service`
- Added `DelayStageSubscriber`: subscribes to the topic and keeps each hub's last known
  stage in a `ConcurrentHashMap`. `GET /eta/{hubId}` reads that map.
- Deleted `DelayStageClient` and `DelayStage`. Leaving them would have kept a second,
  unused path from transit-service to delay-stage-service and muddied the point of the
  stage.
- Added `GET /delay-stages` to expose the replicated cache — makes the subscription
  observable without reading logs or the web console.
- The `502` for an unreachable delay-stage-service is gone by design: the stage is now
  local, so that failure mode no longer exists for ETA requests.

### Reconnection: the failover transport
`MqConfig` gained `BROKER_FAILOVER_URL`, the shared `BROKER_URL` wrapped in ActiveMQ's
failover transport, so a client that loses the connection reconnects with backoff and
restores its producer or subscription. Derived from `BROKER_URL` rather than replacing
it, leaving the documented broker URL as the one source of truth.

Two things this got wrong on the way, both found by testing rather than reading:

1. **The subscriber never came back.** The first cut logged the connection error and
   relied on the startup retry loop, which had already returned. Killing and restarting
   the broker left transit-service subscribed to nothing while `delay-stage-service`
   happily republished into the void — the cache silently froze. The failover transport
   fixes this properly (`Successfully reconnected` → `Stage update H-500: 4 -> 7` with
   no restart).
2. **The failover transport made publishes hang.** `createConnection` is non-blocking,
   but a `send` issued while the transport is disconnected waits on its reconnect lock
   for the length of the outage — a stage-change POST blocked for the full 30s of the
   test instead of failing. `setSendTimeout` does *not* cover that wait; the bound has
   to be applied around the send. `StagePublisher` now runs each publish on its single
   thread and bounds it with a 3s `Future.get`, returning `published: false` on timeout.
   A timed-out message may still land once the broker returns, which is fine for a
   latest-value update on a topic and is noted in the code.

Kept the initial-connect retry loop in `DelayStageSubscriber`, since a cold connect still
throws and the failover transport only takes over once a connection has been established.

### Verified manually
Built all modules and ran the full stack against the broker from `common/docker-compose.yml`:
all `/health` endpoints OK, 18 hubs flowing ingestion → hub-service, one subscriber
receiving a fan-out of three hubs (`H-500`→2, `H-501`→5, `H-502`→8) and computing each
ETA from its own stage (90/480/1440 delay minutes). Failure paths checked rather than
assumed:

| Scenario | Result |
|---|---|
| `POST {"stage":3}` with broker up | `published: true`, transit logs `none -> 3`, ETA 536 min |
| Re-post same stage | `published: false`, no message sent |
| `POST {"stage":99}` / malformed body | `400`, unchanged from stage 2 |
| Unknown hub ETA | `404` |
| **delay-stage-service killed** | ETAs still served from the cache, incl. the stage-3 hub — the decoupling win |
| **Broker stopped, then stage change** | `200` in ~3s with `published: false`; stage still recorded locally |
| **Broker restarted, no service restart** | producer and subscriber reconnect automatically; next stage change reaches transit |

Also confirmed the two known limits: a hub with no message yet reads as stage 0 (a plain
topic does not replay), and a change published during an outage is not recovered. Both
are documented on the contract rather than hidden.

## Sep 29 — Stage 4: alertbot as a second consumer

`alertbot` is the stretch goal from the root README: a second subscriber on
`package-status-topic` that raises a public notice when a hub's delay stage crosses a
threshold. It is the point of the stage 3 topic having more than one consumer —
`transit-service` turns a stage into an ETA, alertbot turns the same stage into a post,
and neither knows the other exists.

### `AlertSubscriber`
- Copied the `DelayStageSubscriber` pattern: `MqConfig.BROKER_FAILOVER_URL`,
  `setWatchTopicAdvisories(false)`, and a daemon startup retry loop for the cold connect.
  The failover transport then covers a mid-run broker restart, as it does for transit.
- Watches the stage in each message and posts when it crosses `ALERT_THRESHOLD_STAGE = 4`.
  Stage 4 was chosen as a judgement call, not derived from anything: far enough up the
  0-8 scale that ordinary jitter does not trip it, but not so far that a real disruption
  goes unannounced.

### Alerts fire on crossings, not on level
The first cut posted whenever `stage >= 4`, which meant a hub climbing 4 → 5 → 6 produced
three identical notices for one disruption. `alerting` now tracks which hubs are currently
over the line, so a post happens on the upward crossing only, and a second one when the hub
recovery below. A hub has to drop back under and cross again before it can post twice.

The up/down decision happens inside a `ConcurrentHashMap.compute` on the stage map, rather
than as a read followed by a write. That is the one place the concurrency actually matters:
two messages for the same hub in flight at once could otherwise both read the same
"previous" stage and both conclude they were the one that crossed, and the crossing would be
missed or duplicated. `compute` runs its function under the bin lock for the key, so the
side effect is serialized per hub. The previous stage is carried out through a one-element
`int[]` because the lambda's return value is the *new* stage, not the old one.

### `SocialPoster` — the simulated webhook
- No network call. The value of this stage is deciding when to post, not integrating a
  real social API, so the post is rendered as text and recorded.
- Keeps a bounded history (last 100). A long-running instance sees a message per hub
  transition indefinitely, and an unbounded list would be a slow leak in a service whose
  whole job is to run forever.
- Posts are exposed over `GET /alerts` so a verification run can be read back as JSON
  instead of scraped out of a log. `GET /watched` mirrors transit-service's
  `/delay-stages`, and `GET /threshold` reports the threshold and the post count.

### Verified manually
Built and ran the full five-service stack against the broker. Both consumers receive the
same broadcast and react independently:

| Scenario | Result |
|---|---|
| stage 0 → 2 → 3 (under threshold) | transit updates, `/alerts` empty |
| 3 → 5 (crosses 4) | one post, `crossed alert threshold 4` |
| 5 → 6 (stays over) | no second post — the reason for tracking crossings |
| 6 → 1 (recovers) | second post, `recovered below alert threshold 4` |
| Three hubs pushed to 6 | three posts; transit's `/delay-stages` and alertbot's `/watched` agree |
| `POST {"stage":99}` / malformed body | `400`, no post — validation unchanged from stage 2 |
| **delay-stage-service killed** | ETAs still served, alertbot still subscribed — neither consumer needs the producer up |
| **Broker stopped, then stage change** | `200` in 3.1s with `published: false`; stage recorded locally, alertbot correctly did *not* post |
| **Broker restarted, no restart** | both consumers reconnected on the failover transport; the next stage change reached transit and posted an alert |

The broker-outage row is the one worth reading twice: alertbot not posting is correct, not
a bug. The change never reached the topic, and a plain topic does not replay — the same
limit documented in stage 3, now visible from the consumer that depends on it. A durable
topic with persistent subscribers is what would close it, and both consumers would need it.

### Not done
- No automated tests, consistent with the rest of the repo. `AlertSubscriber` and
  `SocialPoster` are both constructible without a broker, so the crossing logic and the
  bounded history are testable directly by feeding messages in; that is the obvious next
  step if this is picked up again.
- `SocialPoster` does no retry or backoff. A real webhook would need both, plus a dead
  letter path for posts that keep failing.
