---
title: Maven Artifact & JVM Integration
description: How the engine is published as a JVM library to the GitHub Packages Maven registry and how downstream Scala, Java, and Kotlin projects consume it.
---

The engine is the **single source of truth for Dice Chess rules** across the ecosystem. JVM
backends — first of all [dicechess-analytics](https://github.com/fortemate/dicechess-analytics)
(the Scala 3 analytics backend) — consume it as a regular Maven dependency instead of
re-implementing game logic.

Non-Scala JVM callers (Java, Kotlin) bind to a dedicated facade rather than to the Scala API
directly — see [Consuming from Java or Kotlin](#consuming-from-java-or-kotlin) below.

Every release publishes the JVM artifact alongside the NPM package:

| Coordinate | Value |
| :--- | :--- |
| Group ID | `com.fortemate` |
| Artifact ID | `dicechess-engine_3` |
| Registry | [GitHub Packages Maven](https://github.com/fortemate/dicechess-engine/packages) |

---

## Consuming the Artifact (sbt)

GitHub Packages requires authentication **even for public packages**, so consumers need a
token with the `read:packages` scope. Locally the `GITHUB_ACTOR` / `GITHUB_TOKEN` environment
variables are used; in GitHub Actions the built-in `GITHUB_TOKEN` works as-is.

```scala
resolvers += "GitHub Packages (dicechess-engine)" at
  "https://maven.pkg.github.com/fortemate/dicechess-engine"

credentials ++= (for {
  user  <- sys.env.get("GITHUB_ACTOR")
  token <- sys.env.get("GITHUB_TOKEN")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

libraryDependencies += "com.fortemate" %% "dicechess-engine" % "<latest release>"
```

---

## Consuming from Java or Kotlin

Java and Kotlin callers depend on the same artifact, but bind to
[`dicechess.engine.jvmapi.JvmApi`](/dicechess-engine/architecture/jvm-api/) rather than to the
Scala API. The Scala surface leans on constructs that do not survive the language boundary
intact — `Either` returns, extension methods (which compile onto synthetic `$package` classes with
no ordinary entry point), and opaque types like `Move` that erase to `int`, turning a
`List[List[Move]]` into an unchecked `List[List[Object]]` of boxed integers. `JvmApi` keeps all of
that away from the caller: its signatures use `java.util` types, primitives, opaque handles passed
straight back to the engine, and one facade-owned result type, `JvmApi.Turn`.

Java callability is pinned by a Java-source test that CI compiles and runs on every build; Kotlin
consumes the same static methods and `java.util` types but is not exercised here — see
[What "Java and Kotlin" is based on](/dicechess-engine/architecture/jvm-api/#what-java-and-kotlin-is-based-on).

Note the `_3` suffix in the artifact ID: Maven has no equivalent of sbt's `%%` operator, so the
Scala binary-version suffix has to be spelled out.

```xml
<properties>
    <dicechess.engine.version><!-- latest release --></dicechess.engine.version>
</properties>

<repositories>
    <repository>
        <id>github-dicechess-engine</id>
        <url>https://maven.pkg.github.com/fortemate/dicechess-engine</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.fortemate</groupId>
        <artifactId>dicechess-engine_3</artifactId>
        <version>${dicechess.engine.version}</version>
    </dependency>
</dependencies>
```

Authentication works the same way as for sbt, but Maven reads it from `~/.m2/settings.xml`
rather than the environment:

```xml
<settings>
    <servers>
        <server>
            <id>github-dicechess-engine</id>
            <username><!-- GitHub username --></username>
            <password><!-- token with read:packages --></password>
        </server>
    </servers>
</settings>
```

The `<id>` must match the `<repository>` id exactly, or Maven sends the request unauthenticated
and GitHub Packages answers `401 Unauthorized`.

[dicechess-bot-java](https://github.com/fortemate/dicechess-bot-java) is the reference consumer.

### The Scala runtime is two artifacts, not one

The engine's POM declares `org.scala-lang:scala3-library_3`, but since the Scala 3.8 library
unification that artifact is an **empty shim** — its jar contains a manifest and nothing else. The
actual runtime classes live in `org.scala-lang:scala-library` of the same version, which the shim
pulls in as a transitive dependency.

Maven, Gradle, and sbt resolve that transitively, so a normal dependency declaration needs no extra
work. It breaks when something keeps `scala-library` off the runtime classpath: a hand-assembled
classpath (a hand-listed `-cp`, a shaded or "fat" jar built from a hand-picked file list, a container
image that copies selected jars), or dependency metadata that excludes it, marks it optional, or
narrows it to `provided` scope. Such a classpath links and starts fine and then fails on the first
real call into the engine:

```text
java.lang.NoClassDefFoundError: scala/util/boundary$Break
```

The fix is to put `scala-library` on the classpath as well (or let the build tool compute the
classpath). Verifying is one command — the shim is the jar with a single entry:

```bash
unzip -l scala3-library_3-*.jar
```

---

## Local Development Against Unreleased Changes

When a downstream project needs engine changes that are not released yet, publish the JVM
artifact to the local Ivy repository:

```bash
mise run publish:local
```

This publishes the current `-SNAPSHOT` version to `~/.ivy2/local`, where sbt resolves it
before any remote registry.

Maven does not read `~/.ivy2/local`, so a Maven-built consumer needs the artifact in the local
Maven repository instead:

```bash
sbt rootJVM/publishM2
```

That writes to `~/.m2/repository`, where Maven picks it up. Point the consumer's
`dicechess.engine.version` at the `-SNAPSHOT` value while iterating, and remember to move it back
to a real release before opening a PR — CI has no access to your local repository, so a
`-SNAPSHOT` dependency that builds locally fails there.

---

## How Publishing Works

- `build.sbt` defines `publishTo` (GitHub Packages) and reads credentials from the
  `GITHUB_ACTOR` / `GITHUB_TOKEN` environment variables; the `benchmark` module is excluded
  via `publish / skip := true`.
- Both CD workflows (`release.yaml` and `publish.yaml`) run
  `sbt "set ThisBuild / version := \"<tag>\"" rootJVM/publish`, so the registry always
  receives the clean release version without the `-SNAPSHOT` suffix.
- The steps are intentionally duplicated in both workflows: tags pushed by `release.yaml`
  via `GITHUB_TOKEN` do not trigger `publish.yaml` (GitHub's recursion guard).

See [CI/CD & Automated Releases](/dicechess-engine/architecture/releases/) for the full pipeline.
