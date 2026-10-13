# common — Asynchronous Decoupling (MQ)

## Overview

Topic: `package-status-topic`

Package status updates move from latency-driven RPC to bandwidth-driven messaging.

Part of the [LogisticsConnect](../README.md) project. Holds the ActiveMQ broker shared
by the services below — not a service itself, so it has no port of its own.

- Producer: `delay-stage-service` (`../delay-stage-service`)
- Consumer(s): `transit-service` (required, stage 3) and `alertbot` (stretch, stage 4 —
  reacts to stage changes to decide when to raise an alert)

Broker URL and topic name are shared via a common `co.wethinkcode.logisticsconnect.mq.MqConfig` class
(`BROKER_URL`, `TOPIC`). It's identical in every participating service's own source
tree — each service here is an independent Maven project with no shared parent pom,
so the common package is duplicated rather than imported from one place. The message
record `PackageStatusMessage` is duplicated the same way, since producer and consumer
must agree on the payload shape.

`MqConfig.BROKER_FAILOVER_URL` wraps `BROKER_URL` in ActiveMQ's failover transport.
Clients reconnect with backoff after a broker restart and restore their producer or
subscription, so consumers keep receiving without a restart. Note that a send issued
while the transport is disconnected waits on its reconnect lock, so producers bound
their own send with a timeout — see `StagePublisher`.

## Project structure

```
common/
├── docker-compose.yml
└── README.md
```

This folder holds the broker config and notes only — the actual publish/subscribe
code belongs in the producer/consumer services listed above (their poms already
depend on `activemq-client`, and each already has
`src/main/java/co/wethinkcode/logisticsconnect/mq/MqConfig.java`).

## Build

Nothing to build here directly — this folder just brings up the broker used by the
services listed above.

## Run

```
docker compose up -d
```

- Broker URL for clients: `tcp://localhost:61616`
- Web console: http://localhost:8161 (default admin/admin)

Then start the producer/consumer services as usual (`mvn package && java -jar ...`
from their own directories at the project root).

## Test

```
docker compose ps          # confirm the broker container is healthy
```

Once the TODOs below are implemented, verify end-to-end by publishing a message from
`delay-stage-service` and confirming the consumer(s) receive it — e.g. via logs, or by
watching the topic in the web console.

```
# a stage change is broadcast, and both consumers receive it independently
curl -X POST http://localhost:7052/delay-stage/H-500 \
  -H 'Content-Type: application/json' -d '{"stage":3}'
# -> {"hubId":"H-500","stage":3,"previousStage":0,"published":true}

# what transit-service has replicated from the topic
curl http://localhost:7053/delay-stages
# -> {"H-500":3}

# and the ETA it now computes from that stage
curl http://localhost:7053/eta/H-500

# the same message is also what alertbot watches. At stage 3 it is under its
# threshold of 4, so nothing is posted:
curl http://localhost:7054/alerts
# -> []

# push the stage past alertbot's threshold and both consumers react from the
# same broadcast, with neither calling the other
curl -X POST http://localhost:7052/delay-stage/H-500 \
  -H 'Content-Type: application/json' -d '{"stage":5}'
curl http://localhost:7054/alerts
# -> [{"hubId":"H-500","stage":5,"previousStage":3,"reason":"crossed alert threshold 4", ...}]
```

## TODO

- ~~Add `activemq-client` publish logic to `delay-stage-service` on its stage/state-change endpoint.~~ Done — see `StagePublisher`.
- ~~Add `activemq-client` subscriber logic to consumer service(s) above, replacing any
  direct synchronous calls to `delay-stage-service`.~~ Done for `transit-service` — see `DelayStageSubscriber`.
- ~~Add `activemq-client` subscriber logic to consumer service(s) above, replacing any
  direct synchronous calls to `delay-stage-service`.~~ Done for both — see
  `DelayStageSubscriber` in `transit-service` and `AlertSubscriber` in `alertbot`.
