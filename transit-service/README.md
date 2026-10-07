# TransitServiceApp

## Overview

Calculates estimated arrival windows based on hub and delay stage.

Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service subscribes to the ActiveMQ topic `package-status-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside it in this module.

## Project structure

```
transit-service/
├── pom.xml
└── src/main/java/co/wethinkcode/logisticsconnect/
    ├── TransitServiceApp.java
    ├── DelayStageSubscriber.java
    ├── HubClient.java
    ├── Hub.java
    ├── PackageStatusMessage.java
    └── mq/
        └── MqConfig.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/transit-service.jar
```

Listens on port `7053`.

## Test

No automated tests yet. Manually verify it's up (broker first: `cd ../common && docker
compose up -d`, plus hub-service and delay-stage-service running):

```
curl http://localhost:7053/health   # -> OK

# stages replicated from the topic so far
curl http://localhost:7053/delay-stages
# -> {"H-500":3}

# ETA from hub-service's place data plus the stage above
curl http://localhost:7053/eta/H-500
# -> {"hubId":"H-500",...,"delayStage":3,"delayMinutes":180,"etaMinutes":536,...}
```

Drive it from the producer to see the subscription update:

```
curl -X POST http://localhost:7052/delay-stage/H-500 \
  -H 'Content-Type: application/json' -d '{"stage":4}'
```

A hub with no message yet reads as stage 0, and this service keeps serving ETAs even
while delay-stage-service is down — that is the point of the subscription.

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/logisticsconnect/`, and run `mvn test`.
