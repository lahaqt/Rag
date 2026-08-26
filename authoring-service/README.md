# authoring-service

Spring Boot service for the Engineering Authoring Coach control plane and student API. It requires PostgreSQL, an OIDC issuer/JWK endpoint, Content client credentials, and `AUTHORING_CONFIG_ENCRYPTION_KEY` when encrypted model or MCP secrets are configured.

Run with `mvn spring-boot:run`; Flyway applies `db/migration` before the application accepts requests. See the root README and `docs/contracts/authoring-public-v1.openapi.yaml`.
