# AlertBotApp

## Overview

Posts proactive delay notifications to public transit social media pages (simulated).

Part of the [LogisticsConnect](../README.md) project — its alerting service.
Independent Maven module, no parent pom.

Mechanism: Outbound webhook, simulated social post

MQ: this service subscribes to the ActiveMQ topic `package-status-topic` — see
[`../common/`](../common). Broker URL and topic name come from the common
`co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside it in this module, and
the `PackageStatusMessage` record is duplicated from the producer so both agree on the
payload.

This is the second consumer on the topic from stage 3. `transit-service` turns a stage
into an ETA; alertbot turns the same stage into a public post. Neither calls
`delay-stage-service`, so one stage change reaches both without either knowing about
the other.

## Behaviour

Posts a notice when a hub's delay stage **crosses stage 4** (`AlertSubscriber.ALERT_THRESHOLD_STAGE`),
and a second one when it drops back below. Stage 4 is the chosen threshold: far enough up
the 0-8 scale that ordinary jitter does not trip it.

Alerts fire on crossings, not on level. A hub sitting at stage 6 has already been
announced, so `5 -> 6` posts nothing — otherwise a long disruption would produce a
stream of duplicate notices. `alerting` tracks which hubs are currently over the line, so
a hub has to recover and cross again before it can post a second time.

`SocialPoster` is the simulated webhook: no network call is made, since the point of
this stage is deciding *when* to post rather than integrating a real social API. Each
post is rendered as the text that would have been published and kept in a bounded history
(100 posts) so a run can be inspected over HTTP instead of scrolled for in a log.

Inherits the two limits of a plain topic, both documented on the contract rather than
hidden: a hub already above the threshold when this service starts stays silent until its
next change, and a change published while alertbot is disconnected is not replayed.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | Liveness, OK if the service is up — independent of broker state |
| `GET` | `/alerts` | Every post recorded, oldest first |
| `GET` | `/watched` | Last stage seen per hub, as replicated from the topic |
| `GET` | `/threshold` | The threshold in force and how many posts have gone out |

## Project structure

```
alertbot/
├── pom.xml
└── src/main/java/co/wethinkcode/logisticsconnect/
    ├── AlertBotApp.java
    ├── AlertSubscriber.java
    ├── SocialPoster.java
    ├── PostedAlert.java
    ├── PackageStatusMessage.java
    └── mq/
        └── MqConfig.java
```

## Build

```
mvn package
```

## Run

Needs the broker up first, since every message it acts on arrives from the topic:

```
cd ../common && docker compose up -d
cd ../alertbot && mvn package && java -jar target/alertbot.jar
```

Listens on port `7054`. Subscribing runs on a daemon thread, so the service answers
`/health` even when the broker is down — it just posts nothing until a subscription
lands.

## Test

No automated tests yet. Manually verify it's up:

```
curl http://localhost:7054/health   # -> OK
curl http://localhost:7054/threshold   # -> {"alertAtOrAbove":4,"posted":0}
```

Then drive it from the stage endpoint, with the broker running:

```
# under the threshold: transit updates, nothing is posted
curl -X POST http://localhost:7052/delay-stage/H-500 \
  -H 'Content-Type: application/json' -d '{"stage":3}'
curl http://localhost:7054/alerts   # -> []

# cross it: one post
curl -X POST http://localhost:7052/delay-stage/H-500 \
  -H 'Content-Type: application/json' -d '{"stage":5}'
curl http://localhost:7054/alerts   # -> one alert, "crossed alert threshold 4"

# stay above it: still one post, no duplicate
curl -X POST http://localhost:7052/delay-stage/H-500 \
  -H 'Content-Type: application/json' -d '{"stage":6}'
curl http://localhost:7054/alerts   # -> still one alert

# recover: a second post saying so
curl -X POST http://localhost:7052/delay-stage/H-500 \
  -H 'Content-Type: application/json' -d '{"stage":1}'
curl http://localhost:7054/alerts   # -> two alerts, the second "recovered"
```

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/logisticsconnect/`, and run `mvn test`. `AlertSubscriber`
and `SocialPoster` are both constructible without a broker, so the crossing logic and
the bounded history can be tested directly by feeding messages in.
