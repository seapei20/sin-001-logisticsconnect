# How this project is built and tested

The working rule: **no commit goes out until `mvn test` passes for the module it touches.**

This is a habit, not a one-off. Each stage below follows the same loop, so the history shows
the work being built up and every commit is independently verifiable.

## The loop

```
1. write or change code in one module
2. cd <module> && mvn test          # must be green
3. git add <module> && git commit
4. git push                         # CI re-runs the same tests
```

If a test fails at step 2, fix the code or the test deliberately — never delete the test to
get green.

## Running the tests

One module:

```
cd ingestion-service && mvn test
```

All five (they are independent Maven projects with no parent pom, so they are built one at a
time):

```
find . -name pom.xml -execdir mvn -q test \;
```

Expected output when healthy:

```
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## What is covered

| Module | Tests | What they pin down |
|---|---|---|
| `ingestion-service` | 23 | CSV cleaning rules + the REST contract over a real socket |
| `hub-service` | 0 | Stage 2 |
| `delay-stage-service` | 0 | Stage 2, plus stage 3 publish |
| `transit-service` | 0 | Stage 2, plus stage 3 subscription |
| `alertbot` | 0 | Stage 4 |

Each module that has tests uses JUnit 5 (`junit-jupiter` 5.10.2) and Surefire 3.2.5, both
declared in that module's own `pom.xml`.

### ingestion-service

`CsvCleanerTest` covers each cleaning rule against the real `hubs-global.csv` and against
inline fixtures for cases the shipped file does not contain: casing, padding and internal
whitespace, the accepted boolean spellings, placeholders, and the two dedup rules.

`IngestionServiceAppTest` starts the service on port 0 (the OS picks a free port, so it never
collides with a running instance) and exercises `/health`, `/hubs`, and `/hubs/{id}` over
HTTP. A unit test on `CsvCleaner` would still pass if the route were never registered, so the
contract the other services depend on is asserted over the wire.

## Continuous integration

`.github/workflows/build.yml` runs `mvn test` for all five modules on every push to `main` and
every pull request, in a matrix so a failure in one module is visible on its own. The GitHub
check has to pass before a push is mergeable, which means the tests are enforced rather than
merely present.

## A note on what "tested" means here

A passing suite is evidence only if the tests can fail. Both suites were checked by breaking
the production code deliberately and confirming the right tests went red:

- flipping the dedup rule from `existing || incoming` to `existing` failed
  `activeWinsOverInactive`
- dropping the internal-whitespace collapse failed 3 tests, including the end-to-end
  assertion on the real CSV
- changing the unknown-hub route from 404 to 200 failed `unknownHubIs404`

A suite that never went red was not proving anything.
