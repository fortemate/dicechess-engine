import sbt.{given, *}
import org.scalajs.linker.interface.ESVersion
import scala.jdk.CollectionConverters.*

// =============================================================================
// Project Metadata & Publishing Settings
// =============================================================================
ThisBuild / organization         := "com.fortemate"
ThisBuild / organizationName     := "Fortemate"
ThisBuild / organizationHomepage := Some(uri("https://fortemate.com"))
ThisBuild / homepage             := Some(uri("https://fortemate.com"))
ThisBuild / startYear            := Some(2026)
ThisBuild / version              := "0.2.1-SNAPSHOT"
ThisBuild / scalaVersion         := "3.8.4"

ThisBuild / description   := "Cross-platform high-performance Dice Chess engine, move generator, and AI search."
ThisBuild / licenses      := List(License("AGPL-3.0", uri("https://www.gnu.org/licenses/agpl-3.0.txt")))
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / scmInfo := Some(
  ScmInfo(
    uri("https://github.com/fortemate/dicechess-engine"),
    "scm:git@github.com:fortemate/dicechess-engine.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id = "rabestro",
    name = "Jegors Čemisovs",
    email = "jegors.cemisovs@gmail.com",
    url = uri("https://fortemate.com")
  )
)

// Publishing (JVM artifact → GitHub Packages Maven registry).
ThisBuild / publishTo := Some(
  "GitHub Packages" at "https://maven.pkg.github.com/fortemate/dicechess-engine"
)
ThisBuild / credentials ++= (for {
  user  <- sys.env.get("GITHUB_ACTOR")
  token <- sys.env.get("GITHUB_TOKEN")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

val ScalaV = "3.8.4"

// Fails the build when a coverage run produced no instrumentation metadata (#531).
lazy val coverageDataCheck = taskKey[Unit]("Verify the coverage run actually instrumented the code")

// Refuse to publish a jar that IS coverage-instrumented.
lazy val assertNoCoverageInstrumentation =
  taskKey[Unit]("Fail if the packaged jar carries scoverage instrumentation")

// Prove the published engine jar carries no bench/arena classes (#564).
lazy val assertNoBenchClasses =
  taskKey[Unit]("Fail if the packaged jar carries dicechess/engine/bench classes")

// projectMatrix layout: map to shared/ + jvm/ + js/
def layout(platformDir: String) = Seq(
  Compile / unmanagedSourceDirectories := Seq(
    (ThisBuild / baseDirectory).value / "shared" / "src" / "main" / "scala",
    (ThisBuild / baseDirectory).value / platformDir / "src" / "main" / "scala"
  ),
  Test / unmanagedSourceDirectories := Seq(
    (ThisBuild / baseDirectory).value / "shared" / "src" / "test" / "scala",
    (ThisBuild / baseDirectory).value / platformDir / "src" / "test" / "scala",
    (ThisBuild / baseDirectory).value / platformDir / "src" / "test" / "java"
  ),
  Compile / unmanagedResourceDirectories := Seq(
    (ThisBuild / baseDirectory).value / "shared" / "src" / "main" / "resources",
    (ThisBuild / baseDirectory).value / platformDir / "src" / "main" / "resources"
  ),
  Test / unmanagedResourceDirectories := Seq(
    (ThisBuild / baseDirectory).value / "shared" / "src" / "test" / "resources",
    (ThisBuild / baseDirectory).value / platformDir / "src" / "test" / "resources"
  )
)

lazy val commonSettings = Seq(
  name := "dicechess-engine",
  libraryDependencies ++= Seq(
    "io.circe"      %% "circe-core"       % "0.14.16" % Test,
    "io.circe"      %% "circe-generic"    % "0.14.16" % Test,
    "io.circe"      %% "circe-parser"     % "0.14.16" % Test,
    "org.scalameta" %% "munit"            % "1.3.0"   % Test,
    "org.scalameta" %% "munit-scalacheck" % "1.3.0"   % Test
  ),
  semanticdbEnabled        := true,
  semanticdbVersion        := scalafixSemanticdb.revision,
  coverageExcludedFiles    := ".*Main\\.scala",
  coverageMinimumStmtTotal := 85,
  coverageFailOnMinimum    := true,
  scalacOptions ++= Seq(
    "-Werror",                  // Fail compilation on warnings
    "-Wunused:all",             // Fail on unused imports, privates, locals, and implicits
    "-language:strictEquality", // Prevent comparing different types
    "-Yexplicit-nulls",         // Make null explicit
    "-explain",                 // Explain type errors in detail
    "-feature",                 // Emit warning for usages of features that should be imported explicitly
    "-deprecation"              // Emit warning for usages of deprecated APIs
  )
)

lazy val root = (projectMatrix in file("."))
  .settings(commonSettings)
  .defaultAxes(VirtualAxis.scalaABIVersion(ScalaV))
  .jvmPlatform(
    scalaVersions = Seq(ScalaV),
    settings = layout("jvm") ++ Seq(
      coverageMinimumStmtTotal                          := 90,
      libraryDependencies += "com.microsoft.onnxruntime" % "onnxruntime" % "1.29.0",
      Test / exportJars                                 := false,
      coverageDataCheck                                 := Def.uncached {
        val metadata = coverageDataDir.value / "scoverage-data" / "scoverage.coverage"
        if (!metadata.isFile)
          sys.error(
            s"""Coverage instrumentation metadata is missing: $metadata
               |
               |The compiler did not run, so nothing was measured and the coverage
               |threshold could not be enforced (see #531). Re-run against a cold cache:
               |
               |  mise run coverage""".stripMargin
          )
        streams.value.log.info(s"Coverage instrumentation metadata present: $metadata")
      },
      assertNoCoverageInstrumentation := Def.uncached {
        val jar    = fileConverter.value.toPath((Compile / packageBin).value).toFile
        val marker = "scala/runtime/coverage/Invoker"
        val zip    = new java.util.zip.ZipFile(jar)
        val hits   =
          try
            zip.entries().asScala.count { entry =>
              entry.getName.endsWith(".class") && {
                val bytes = zip.getInputStream(entry).readAllBytes()
                new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains(marker)
              }
            }
          finally zip.close()
        if (hits > 0)
          sys.error(
            s"""$jar is coverage-instrumented: $hits class file(s) reference $marker.
               |Restart the server and rebuild before publishing:
               |  sbt shutdown
               |  sbt 'clean; rootJVM/assertNoCoverageInstrumentation; rootJVM/publish'""".stripMargin
          )
        streams.value.log.info(s"No coverage instrumentation in ${jar.getName}")
      },
      assertNoBenchClasses := Def.uncached {
        val jar    = fileConverter.value.toPath((Compile / packageBin).value).toFile
        val marker = "dicechess/engine/bench/"
        val zip    = new java.util.zip.ZipFile(jar)
        val hits   =
          try
            zip.entries().asScala.count { entry =>
              entry.getName.startsWith(marker)
            }
          finally zip.close()
        if (hits > 0)
          sys.error(
            s"""$jar carries $hits bench class file(s) under $marker.
               |The engine artifact must not ship bench/arena tooling to consumers (see #564).""".stripMargin
          )
        streams.value.log.info(s"No bench classes in ${jar.getName}")
      },
      Compile / doc / scalacOptions ++= Seq(
        "-project",
        name.value,
        "-project-version",
        version.value,
        "-project-footer",
        "Fortemate Dice Chess Engine",
        "-source-links:src/main/scala=https://github.com/fortemate/dicechess-engine/blob/main/src/main/scala€{FILE_PATH}.scala#L€{LINE}",
        "-social-links:github::https://github.com/fortemate/dicechess-engine",
        "-groups",
        "-author",
        "-snippet-compiler:compile"
      )
    )
  )
  .jsPlatform(
    scalaVersions = Seq(ScalaV),
    settings = layout("js") ++ Seq(
      coverageEnabled                 := false,
      scalaJSUseMainModuleInitializer := false,
      scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
    )
  )

lazy val rootJVM = root.jvm(ScalaV)
lazy val rootJS  = root.js(ScalaV)

// Explicit root aggregate project to avoid sbt 2 empty synthetic root issues.
lazy val dicechessEngine = (project in file("."))
  .aggregate(rootJVM, rootJS, rootWasm, benchmark, arena, cli)
  .settings(
    name           := "dicechess-engine-aggregate",
    publish / skip := true
  )

lazy val rootWasm = project
  .in(file(".wasm"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(layout("js"))
  .settings(
    name                            := "dicechess-engine-wasm",
    coverageEnabled                 := false,
    scalaJSUseMainModuleInitializer := false,
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
    }
  )

lazy val benchmark = project
  .in(file("benchmark"))
  .dependsOn(rootJVM)
  .enablePlugins(JmhPlugin)
  .settings(
    name                    := "dicechess-benchmark",
    Compile / doc / sources := Seq.empty,
    coverageEnabled         := false,
    publish / skip          := true,
    scalacOptions -= "-Werror"
  )

lazy val arena = project
  .in(file("arena"))
  .dependsOn(rootJVM)
  .settings(commonSettings)
  .settings(
    name := "dicechess-arena",
    libraryDependencies ++= Seq(
      "com.monovore"  %% "decline"   % "2.6.2",
      "org.typelevel" %% "cats-core" % "2.13.0"
    ),
    publish / skip := true,
    Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "jvm" / "src" / "test" / "resources",
    Test / exportJars        := false,
    coverageMinimumStmtTotal := 70,
    coverageFailOnMinimum    := true,
    coverageDataCheck        := Def.uncached {
      val metadata = coverageDataDir.value / "scoverage-data" / "scoverage.coverage"
      if (!metadata.isFile)
        sys.error(
          s"""Coverage instrumentation metadata is missing: $metadata
             |The compiler did not run, so nothing was measured. Re-run against a cold cache:
             |  mise run coverage""".stripMargin
        )
      streams.value.log.info(s"Coverage instrumentation metadata present: $metadata")
    }
  )

lazy val cli = project
  .in(file("cli"))
  .dependsOn(rootJVM)
  .settings(commonSettings)
  .settings(
    name           := "dicechess-cli",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.monovore"  %% "decline"   % "2.6.2",
      "org.typelevel" %% "cats-core" % "2.13.0",
      "org.jline"      % "jline"     % "4.3.1"
    ),
    coverageMinimumStmtTotal := 60,
    coverageDataCheck        := Def.uncached {
      val metadata = coverageDataDir.value / "scoverage-data" / "scoverage.coverage"
      if (!metadata.isFile)
        sys.error(
          s"""Coverage instrumentation metadata is missing: $metadata
             |The compiler did not run, so nothing was measured. Re-run against a cold cache:
             |  mise run coverage""".stripMargin
        )
      streams.value.log.info(s"Coverage instrumentation metadata present: $metadata")
    }
  )
