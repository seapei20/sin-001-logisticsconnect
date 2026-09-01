# RUBRIC — for reviewers only, do not share with candidates

Scores the four stages from the root [README.md](README.md#your-task) "Your task"
table, plus cross-cutting concerns. Stages 1-3 are required; stage 4 is a stretch
goal — weight it as a bonus, not a requirement for a passing score.

## Stage 1 — Clean `hubs-global.csv`

- [ ] Handles casing inconsistencies (IDs, province names, status/category values)
- [ ] Trims padding / collapses internal double spaces
- [ ] Normalizes date formats (if candidate touches date-like fields beyond this CSV's columns)
- [ ] Normalizes boolean/flag representations (`Y`/`yes`/`1`/`true`/`YES` → one form)
- [ ] Handles missing/placeholder values (`N/A`, `unknown`, blank, etc.) sensibly — not just left as-is
- [ ] Some form of duplicate detection/resolution attempted, with reasoning the
      candidate can articulate (exact strategy is open — judge the reasoning, not
      a specific answer)
- [ ] Cleaned records are exposed via REST for other services to consume (not just
      cleaned and logged/discarded)

## Stage 2 — REST services

- [ ] hub-service, delay-stage-service, and transit-service each expose real
      domain endpoints beyond `/health`
- [ ] hub-service serves place-name data sourced from ingestion-service's cleaned
      output (directly or cached)
- [ ] transit-service calls hub-service and delay-stage-service and successfully
      returns an ETA-shaped response for at least one hub
- [ ] Reasonable HTTP semantics (status codes, JSON bodies) — doesn't need to be
      polished, just functional and legible

## Stage 3 — MQ decoupling

- [ ] delay-stage-service publishes to `package-status-topic` on a stage change
- [ ] transit-service subscribes to the topic instead of calling
      delay-stage-service synchronously
- [ ] Broker comes up via `common/docker-compose.yml` and a message can be
      observed flowing end-to-end (logs or the ActiveMQ web console are fine
      evidence)

## Stage 4 — AlertBot (stretch)

- [ ] alertbot subscribes to `package-status-topic`
- [ ] Some threshold/condition triggers a simulated alert (webhook call, log
      line, etc. — "simulated" is explicit in the brief, no real social API needed)

## Cross-cutting

- [ ] **Readability** — code a teammate could pick up without a walkthrough
- [ ] **Incremental commits** — history shows the stages being built up, not one
      giant commit
- [ ] **Tests** — any automated tests beyond the provided `/health` smoke checks
      (not required per the scaffold, but a strong positive signal)
- [ ] **Articulating tradeoffs** — in a debrief/PR description, can the candidate
      explain the dedup strategy they picked, why REST vs. MQ makes sense at each
      stage, and what they'd do differently with more time
