# synapsys-springboot-starter
Spring Boot starter for registering a Synapsys worker and its processes with Synapsys Core.

Configure the Core URL and required worker token through application
properties. The token should be supplied by a secret or environment variable:

```yaml
synapsys:
  core-url: http://synapsys-core:8080
  core-token: ${SYNAPSYS_CORE_TOKEN}
```

The starter sends the token as `Authorization: Bearer <token>` on Core
requests. The Core health endpoint may remain public, but the token is
required for worker heartbeats. If `synapsys.core-token` is missing, the
starter logs a warning at startup and Core rejects the worker heartbeats.
