# Synapsys Spring Boot Starter

Register a Spring Boot application as a Synapsys worker, and let Synapsys Core
start and stop its background processes remotely.

Your application declares processes as beans. The starter reports them to Core on a
heartbeat, receives start/stop commands on the response, and runs them on isolated
threads. Workers never accept inbound connections — everything rides one outbound POST.

## Requirements

- Java 17 or later
- Spring Boot 3.x
- A reachable Synapsys Core instance and a worker token

## Install

Maven:

```xml
<dependency>
  <groupId>org.basamento.synapsys</groupId>
  <artifactId>synapsys-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'org.basamento.synapsys:synapsys-spring-boot-starter:0.1.0'
```

## Configure

```yaml
synapsys:
  core-url: https://core.example.com
  core-token: ${SYNAPSYS_CORE_TOKEN}
```

The token is sent as `Authorization: Bearer <token>`. Keep it in an environment
variable or a secret, never in committed configuration.

## Define a process

Two kinds of process are supported.

**Endless** — runs until stopped:

```java
@SynapsysProcess(name = "invoice-poller")
public class InvoicePoller extends AbstractEndlessProcess {

    @Override
    protected void runForever(StopSignal stopSignal) throws Exception {
        while (!stopSignal.isStopRequested()) {
            System.out.println("polling for new invoices");
            Thread.sleep(5_000);
        }
    }
}
```

**Progressive** — runs once per start command, then returns to idle:

```java
@SynapsysProcess(name = "monthly-report")
public class MonthlyReport extends AbstractProgressiveProcess {

    @Override
    protected void run() throws Exception {
        for (Account account : accounts) {
            checkCancelled();        // honours a stop command
            System.out.println("processing " + account.id());
        }
    }
}
```

`@SynapsysProcess` is a stereotype annotation, so the class is picked up by
component scanning. Omit `name` to use the bean name. Anything the process prints
to stdout or stderr while running is streamed to Core and shown against that run.

Both types accept an optional `onStop()` override for cleanup.

## Properties

| Property | Default | Description |
| --- | --- | --- |
| `synapsys.enabled` | `true` | Set to `false` to keep the starter on the classpath without heartbeats or console capture — useful in tests and local runs. |
| `synapsys.core-url` | — | **Required.** Base URL of Synapsys Core. |
| `synapsys.core-token` | — | Bearer token for this worker. Core rejects heartbeats without it. |
| `synapsys.worker-name` | `spring.application.name` | Name this worker reports. Required if `spring.application.name` is unset. |
| `synapsys.host` | machine hostname | Host reported to Core. Set it when the resolved name is meaningless, as with a container id. |
| `synapsys.heartbeat-interval` | `5s` | Interval between heartbeats. Must be a whole number of seconds. |
| `synapsys.fail-fast` | `false` | Fail application startup when Core is unreachable. |
| `synapsys.connect-timeout` | `2s` | HTTP connect timeout to Core. |
| `synapsys.request-timeout` | `5s` | HTTP request timeout to Core. |

Unknown `synapsys.*` keys fail startup rather than being ignored, so a typo
surfaces immediately.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

