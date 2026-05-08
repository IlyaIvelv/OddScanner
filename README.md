# OddScanner MVP

## Stack
Java 21, Spring Boot 3.5, jOOQ, PostgreSQL 16, Playwright.

## Setup
1. Run DB: `docker-compose up -d`
2. Install Playwright browsers: 
   `mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install"`
3. Build & Run: `mvn spring-boot:run`

## API
Swagger UI: http://localhost:8080/swagger-ui.html