# Synapsys Spring Boot Starter

Register a Spring Boot application as a Synapsys worker, and let Synapsys Core
start and stop its background processes remotely.

Your application declares processes as beans. The starter reports them to Core on a
heartbeat, receives start/stop commands on the response, and runs them on isolated
threads. Workers never accept inbound connections — everything rides one outbound POST.

## Compatibility

| | |
| --- | --- |
| Java | 17 or later |
| Spring Boot | 3.x |
| Runtime dependencies | `spring-boot-autoconfigure`, `spring-context`, `slf4j-api`, `jackson-databind` — all of which a Spring Boot web application already has |
| Coordinates | `org.basamento.synapsys:synapsys-spring-boot-starter` |
| Framework identifier sent to Core | `springboot` |
| Core | any version serving `GET /api/health` and `POST /api/v1/workers/heartbeat` |

You also need a reachable Synapsys Core instance and a worker token.

This starter implements the shared Synapsys client-library specification, which every
official client library follows so that they behave like one product rather than
several interpretations of it:

- `synapsys-docs/CLIENT_LIBRARY_SPEC.md` — the normative client contract
- `synapsys-docs/content/manuals/raw-api/checklist.md` — the wire-protocol checklist
- `openapi.yaml` in the Core repository — the machine-readable contract

## Install

Maven:

```xml
<dependency>
  <groupId>org.basamento.synapsys</groupId>
  <artifactId>synapsys-spring-boot-starter</artifactId>
  <version>0.2.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'org.basamento.synapsys:synapsys-spring-boot-starter:0.2.0'
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
| `synapsys.shutdown-timeout` | `10s` | How long shutdown waits for running processes to become terminal. |
| `synapsys.capture-console` | `true` | Stream process console output to Core. `false` leaves `System.out` and `System.err` untouched while keeping remote process control. |

Unknown `synapsys.*` keys fail startup rather than being ignored, so a typo
surfaces immediately.

## Lifecycle semantics

Core owns the desired state; the starter owns the transition. Every command carries a
token, and the token is acknowledged **only after** the local handler has accepted it,
so anything unacknowledged is redelivered on a later beat.

| Situation | What happens |
| --- | --- |
| `desiredState: idle` | No outstanding command. Never a stop. |
| A start for a process already running | Idempotent no-op, acknowledged. The active `runId` is not reassigned. |
| A stop for a process that is not running | Idempotent no-op, acknowledged. |
| **A start while the previous run is still stopping** | **Deferred.** Not acknowledged, and the old run's `runId` is kept. Core redelivers it; it is applied once the old run is terminal. |
| A command handler that throws | Logged, the process is marked `failed`, the token stays unacknowledged, and the other processes on that beat are unaffected. |
| A duplicate or stale token | Ignored. |

Deferring a start is the difference between a run that happens a few seconds late and
a run that never happens at all: acknowledging it as a no-op would tell Core the
command was carried out when nothing ran.

`running` is set **synchronously**, before the worker thread starts or the task is
submitted, so a second start arriving in the same beat cannot observe `idle` and spawn
a duplicate. Each progressive process gets its own single-thread executor, so one slow
process cannot block another's start command.

## Cancellation

A stop sets the process's stop signal, interrupts the worker thread, and — for endless
processes — runs `onStop()` on a thread of its own, so a slow cleanup hook cannot delay
the commands the same beat carries for the other processes.

Returning from `runForever` after observing the stop signal, or letting
`checkCancelled()` throw inside `run()`, is a **clean stop, not a failure**: the
process returns to `idle` and nothing is logged as an error. A `Throwable` that is not
a cancellation lands the process in `failed`, is captured to Core as process output,
and is never rethrown into the host application.

Nothing is ever killed. Java cannot safely stop a thread it does not own, so a body
that neither polls its stop signal nor honours an interrupt cannot be stopped. It is
reported by name and left running, and no replacement execution can overlap it.

## Shutdown

`DisposableBean.destroy()` runs a bounded cooperative shutdown, in this order:

1. the heartbeat loop stops, and no further beat can start;
2. every running process is asked to stop;
3. the starter waits up to `synapsys.shutdown-timeout` for them to become terminal;
4. **one** best-effort final heartbeat is sent, carrying the final process states and
   whatever log lines are still queued;
5. console capture is uninstalled and the per-process executors are released.

The final heartbeat's response is **ignored**: shutdown never starts new work, even if
Core has a start command outstanding. If it fails, or the budget is already exhausted,
that is logged at `debug` and shutdown continues — a transport problem never fails or
extends the application's shutdown. Shutdown is idempotent and never throws.

## Console capture

Anything a process prints to stdout or stderr while running is streamed to Core and
shown against that run. The application's own console is unaffected: every line still
reaches the real stream, in order — it is a tee, never a redirect. Output is attributed
by thread, so two concurrent processes never have their lines mixed up, and output
from application code that is not running a process is not captured.

Note that **this transmits process output to Core**, including anything the process
prints. Set `synapsys.capture-console: false` to turn it off while keeping remote
process control.

### Limits you should know about

Capture works by replacing `System.out` and `System.err` with tees. That is a
**JVM-wide** change, and it comes with boundaries worth stating plainly:

- **One capture at a time.** The first starter instance to install owns the streams.
- **Only what goes through `System.out`/`System.err` is captured.** A Logback appender
  bound to the original `PrintStream` before the starter initialises, or one writing
  to a file, bypasses the tee.
- **Something installed on top of us is never clobbered.** On shutdown the originals
  are restored only if the current streams are still the tees the starter installed.
- **Attribution is by thread.** A process body that hands work to a thread pool it
  created itself loses the binding, and that pool's output is not captured.
- **Bounded, and drops oldest.** At most 10,000 queued lines, 16,000 characters per
  line, and 100 entries or 96 KB of UTF-8 per heartbeat.
- **Blank lines and lines with no run id are dropped**, because Core cannot store them.
- **Nothing is captured when `synapsys.capture-console` or `synapsys.enabled` is
  `false`**, and nothing after shutdown has restored the streams.

## Failure behaviour

- **Core being unreachable never stops a process.** The worker keeps running and keeps
  retrying, and converges on the next successful beat.
- **Startup does not block on Core.** A worker that starts while Core is down is the
  normal case. Set `synapsys.fail-fast: true` for the opposite.
- **The heartbeat loop cannot die.** Nothing escapes a tick, including `Error`.
- **Heartbeats never overlap and never burst.** The next beat is scheduled after the
  previous one settles, so a slow Core delays the loop rather than queueing a catch-up
  storm against a service that has just recovered.
- **A 2xx response that is not a readable heartbeat response is a failure.** An empty
  or unparseable body means the beat delivered no commands, so the drained log lines
  are requeued at the front of the queue and nothing is acknowledged. Unknown *fields*,
  and desired states this version has never heard of, are still tolerated: Core is
  versioned independently of the starter.
- **The token travels in the `Authorization` header and nowhere else.** Redirects are
  never followed, so it cannot be replayed at another host, and the Core health probe
  is sent unauthenticated because Core serves it unauthenticated.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Copyright 2026 Julian Marzoli (Basamento).
