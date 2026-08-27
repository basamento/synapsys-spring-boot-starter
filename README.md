# Synapsys Spring Boot Starter

Register a Spring Boot application as a [Synapsys](https://docs.synapsys.jmzworks.it)
worker, and let Synapsys Core start and stop its background processes remotely.

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

## Documentation

Full documentation, including the heartbeat protocol and the process state machine,
is at [docs.synapsys.jmzworks.it](https://docs.synapsys.jmzworks.it).

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

# Releasing (maintainers)

How to publish a version to Maven Central, including first-time setup on a new
machine.

## Coordinates

| | |
| --- | --- |
| Namespace | `org.basamento` (verified on Central via DNS TXT on `basamento.org`) |
| groupId | `org.basamento.synapsys` |
| artifactId | `synapsys-spring-boot-starter` |
| Signing key | `830D96AA49CBCA70` |
| Fingerprint | `5EB374E850516689CAD23998830D96AA49CBCA70` |

## One-time setup on a new machine

### 1. JDK

Install a JDK 17 or later (21 LTS is what releases are built with) and set
`JAVA_HOME` **persistently**:

```powershell
[Environment]::SetEnvironmentVariable('JAVA_HOME','C:\path\to\jdk-21','User')
```

```bash
export JAVA_HOME=/path/to/jdk-21   # add to ~/.bashrc or ~/.zshrc
```

Open a **new** terminal afterwards, then confirm:

```
java -version
```

### 2. GnuPG

Install GnuPG and make sure `gpg` is on the **system** PATH — Maven invokes it
by name, so a copy that only exists inside Git Bash is not enough.

```powershell
winget install --id GnuPG.Gpg4win -e
```

```bash
brew install gnupg          # macOS
sudo apt install gnupg      # Debian/Ubuntu
```

Open a new terminal, then confirm:

```
gpg --version
```

### 3. Import the signing key

The private key is in the password manager as `synapsys-signing-key.asc`.
Restore it to a temporary file and import:

```
gpg --import synapsys-signing-key.asc
```

Confirm it is present and trusted, then **delete the temporary file**:

```
gpg --list-secret-keys --keyid-format=long
```

You should see `rsa4096/830D96AA49CBCA70` with uid
`Julian Marzoli <admin@basamento.org>`.

The public half is already published at `keyserver.ubuntu.com`; it does not
need re-uploading. If a future key replaces this one, publish it with:

```
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <NEW_KEY_ID>
```

### 4. Central credentials

Generate a user token at `central.sonatype.com` → your username →
**View Account** → **Generate User Token**. The password is shown only once.

Create `~/.m2/settings.xml` (`C:\Users\<you>\.m2\settings.xml` on Windows):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

The id must be `central` — it matches `<publishingServerId>` in the pom. This
file lives outside the repository; never copy it into the project.

## Releasing a version

### 1. Set the version

Edit `<version>` in `pom.xml`. Releases use plain versions (`0.2.0`), not
`-SNAPSHOT`. Commit the change.

### 2. Build and sign locally

```
./mvnw -Prelease verify
```

```
.\mvnw.cmd -Prelease verify
```

A passphrase prompt appears for the GPG key. This uploads nothing. Success
means `target/` contains three jars and four `.asc` signatures (jar, sources,
javadoc, pom).

Optionally verify the signatures the way Central will:

```
gpg --verify target/synapsys-spring-boot-starter-<version>.jar.asc \
            target/synapsys-spring-boot-starter-<version>.jar
```

### 3. Upload

```
./mvnw -Prelease deploy
```

This stages a bundle on Central and stops. `autoPublish` is `false`, so
**nothing is public yet**. The output prints a `deploymentId`.

### 4. Inspect and publish

Go to `central.sonatype.com` → **Deployments**, find the deployment, and review
the validation results and any warnings.

Then either:

- **Publish** — the version goes live and appears in Central search within
  ~15 minutes, and on `repo1.maven.org` shortly after.
- **Drop** — discards the staged bundle. A version number is only consumed once
  published, so dropping frees it for reuse.

> **Publishing is irreversible.** A published version can never be replaced,
> corrected, or removed. Fix problems before this step, not after.

### 5. Tag the release

```
git tag -a v<version> -m "Release <version>"
```

```
git push origin v<version>
```

## Troubleshooting

**`JAVA_HOME ... is not defined correctly`** — the variable is unset, or you set
it in a shell that was already running. Environment changes do not reach live
shells. Open a new terminal.

**`Could not determine gpg version`** — Maven cannot find `gpg` on the PATH.
Usually the same cause: GnuPG was installed after the current terminal started.
Open a new terminal. On Windows, verify `C:\Program Files\GnuPG\bin` is on the
system PATH.

**`gpg: keyserver receive failed: No data`** — the key is not on the keyserver,
or has not propagated yet. Propagation is usually under a minute. Confirm with:

```
curl -s -o /dev/null -w "%{http_code}\n" \
  "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x830D96AA49CBCA70"
```

`200` means present, `404` means absent.

**`Can't check signature: No public key`** — two GnuPG installs with separate
keyrings. On Windows, Git's bundled gpg reads `~/.gnupg` while Gpg4win reads
`%APPDATA%\gnupg`. Use the same gpg that created the key.

**`UnrecognizedPropertyException` during publish** — the
`central-publishing-maven-plugin` version is older than Central's API. Bump
`central-publishing-plugin.version` in `pom.xml` to the latest.

**Exported key file is unreadable on Windows** — PowerShell's `>` redirect
writes UTF-16, which GnuPG cannot parse. Use `--output` instead:

```
gpg --export-secret-keys --armor --output key.asc 830D96AA49CBCA70
```
