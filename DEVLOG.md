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

## Sep 1 — Stage 1 kickoff

(fill in below as we build Hub.java / CsvCleaner.java today)

