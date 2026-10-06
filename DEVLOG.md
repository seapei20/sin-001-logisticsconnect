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