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

### Stage 3 seams left in place
Both MQ integration points are marked with a `MQ TODO` comment at the exact line where the
behaviour changes: `DelayStageServiceApp.setStage` (publish `{hubId, stage, timestamp}` on
change) and `TransitServiceApp.etaForHub` (read the stage from the subscription instead of the
`DelayStageClient` call). Stage 3 replaces one call and adds one publish, with no reshaping
of the payloads.

### Verified manually
Built all four modules and ran the stack end to end: all `/health` endpoints OK, 18 hubs
flowing ingestion → hub-service, `GET :7053/eta/H-500` returning a stage-3 ETA of 536 minutes,
`404` on an unknown hub, `400` on `{"stage":99}`, and case-insensitive hub lookups resolving.