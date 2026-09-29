# Dev Log

Running record of what was built, when, and why. Written as we go — this is the
source material for the final debrief/PR description.

## Sep 1 — Setup

- Confirmed environment: Java/Maven/Docker all working locally.
- Reviewed the four stages and the integration contracts in the root README.
- Reviewed `ingestion-service/README.md` known data issues against the actual
  `hubs-global.csv` (18 rows) — casing, padding, boolean variants, missing
  province, and a dedup case: H-500/H-504/H-510/H-515 all describe
  "Johannesburg Central" / "Gauteng" with different active flags.

## Sep 29 — Stage 1: cleaning and serving the CSV

Picked the scaffold back up. The plan for this stage was tests-first: write the cleaning
rules as assertions, implement to them, then wire the REST contract and assert that over a
real socket too.

### `CsvCleaner`
- Parses with opencsv positionally rather than by header name, so a reordered or renamed
  export header cannot silently break the pipeline — the usual way a legacy ingest dies.
- Normalizes four things: hub ids upper-cased, provinces resolved through an alias map
  (`Kwa-Zulu Natal` / `KwaZulu Natal` / `kwa-zulu natal` all become `KwaZulu-Natal`),
  sorting centers title-cased with internal whitespace collapsed, and booleans folded from
  `Y`/`yes`/`1`/`true` and `N`/`no`/`0`/`false`.
- Placeholders (`N/A`, `TBD`, `-`, blank, `unknown`) become `Unknown` for province, and a row
  with no usable hub id is skipped rather than served as a hub nobody can address.

### Dedup strategy
Keyed on the normalized id in a `LinkedHashMap`, so `H-500` and `h-500` collapse and output
order stays stable. Two merge rules, both chosen so a hub is never made to look *worse* than
its data supports:
- an `Unknown` province is overwritten when a duplicate carries a real one;
- `active` is combined with logical OR.

The OR rule is the debatable one and worth stating plainly: the shipped file has H-500/Y,
H-504/true, H-510/FALSE and H-515/YES all describing Johannesburg Central. Taking "any says
active" means a hub is never deactivated by a single conflicting record, which is the
conservative direction — wrongly deactivating a working hub stops parcels moving, while
wrongly keeping one active only delays noticing a real closure. A stricter reading would treat
inactive as authoritative; I picked OR and recorded why.

### REST surface
`GET /hubs` (all), `GET /hubs/{hubId}` (case-insensitive, `404` on miss), `GET /health`.
Cleaned once at startup and cached, since the CSV cannot change while the service runs. A
failed read logs to stderr and leaves the service up, so the failure shows up as missing data
rather than a dead port.

### Tests — 23, and verified they can fail
`CsvCleanerTest` covers each rule against the real CSV plus inline fixtures for cases the
shipped file lacks. `IngestionServiceAppTest` starts the service on port 0 and exercises the
routes over HTTP, because a unit test on the cleaner would pass even if a route were never
registered.

The suite was checked by breaking the production code on purpose and confirming the right
tests went red, since a suite that has never failed proves nothing:

| Deliberate break | Result |
|---|---|
| dedup `existing \|\| incoming` → `existing` | `activeWinsOverInactive` failed |
| dropped internal-whitespace collapse | 3 failed, incl. the end-to-end real-CSV assertion |
| unknown-hub route 404 → 200 | `unknownHubIs404` failed |

`ingestion-service` is also the one module whose tests found a real bug in my own test: a case
-insensitive lookup assertion had the expected/actual arguments swapped.

### CI
`.github/workflows/build.yml` runs `mvn test` across all five modules on every push and pull
request, as a matrix so a failure is attributable to one module. The routine is written up in
`CONTRIBUTING.md`: nothing is committed until the module's tests pass.

