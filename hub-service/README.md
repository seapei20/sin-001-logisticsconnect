# HubServiceApp

## Overview

Serves provinces and sorting centers (place-name source of truth).

Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no
parent pom.

## Project structure

```
hub-service/
├── pom.xml
└── src/main/java/co/wethinkcode/logisticsconnect/HubServiceApp.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/hub-service.jar
```

Listens on port `7051`.

## Test

No automated tests yet. Manually verify it's up:

```
curl http://localhost:7051/health   # -> OK
```

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/logisticsconnect/`, and run `mvn test`.
