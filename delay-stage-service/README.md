# DelayStageServiceApp

## Overview

Tracks the Transit Delay Stage (0-8, e.g. weather shutdowns).

Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service publishes to the ActiveMQ topic `package-status-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside it in this module.

## Project structure

```
delay-stage-service/
├── pom.xml
└── src/main/java/co/wethinkcode/logisticsconnect/
    ├── DelayStageServiceApp.java
    ├── PackageStatusMessage.java
    ├── StagePublisher.java
    └── mq/
        └── MqConfig.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/delay-stage-service.jar
```

Listens on port `7052`.

## Test

No automated tests yet. Manually verify it's up, and that a stage change reaches the topic
(broker first: `cd ../common && docker compose up -d`):

```
curl http://localhost:7052/health   # -> OK

# publish a stage change; "published" is true when the broker took it
curl -X POST http://localhost:7052/delay-stage/H-500 \
  -H 'Content-Type: application/json' -d '{"stage":3}'
# -> {"hubId":"H-500","stage":3,"previousStage":0,"published":true}
```

Re-posting the same stage returns `"published": false` — only real transitions are
broadcast. If the broker is down the stage is still recorded and `published` is `false`
after ~3s, rather than the request hanging.

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/logisticsconnect/`, and run `mvn test`.
