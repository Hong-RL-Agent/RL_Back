# RL Back

Spring Boot backend prototype for the Red Flag project.

This repository contains Java backend code for authenticated test sessions, admin APIs, report entities, system health data, AI analysis logs, and Playwright-driven exploration support.

## Features

- Spring Boot REST API
- JWT-based security
- User authentication and admin workflows
- Test session entities and repositories
- Detected bug, internal error, action log, and AI analysis log persistence
- OpenAPI/Swagger resource file
- Playwright dependency for browser exploration workflows

## Tech Stack

- Java 17
- Spring Boot 3.2
- Spring Security
- Spring Data JPA
- PostgreSQL
- JWT
- Playwright for Java
- Gradle

## Getting Started

```bash
.\gradlew.bat compileJava
.\gradlew.bat bootRun
```

If using Docker Compose:

```bash
docker compose up -d
```

## Project Structure

```text
src/main/java/com/jaws/jawsback/
  config/
  controller/
  dto/
  entity/
  exception/
  repository/
  security/
  service/
src/main/resources/
  application.properties
  static/swagger.yml
```

## Related Repositories

- `Jwas_front`: React web console
- `fds-backend-node`: Node.js fraud detection API
- `Jwas_Playwright`: browser automation support
- `Jwas_AI`: AI policy server
