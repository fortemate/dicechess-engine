import sbt.{given, *}
import org.scalajs.linker.interface.{ESVersion, ModuleSplitStyle}
import scala.jdk.CollectionConverters.*

val ScalaV = "3.10.0"

// =============================================================================
// Project Metadata & Publishing Settings
// =============================================================================
ThisBuild / organization         := "com.fortemate"
ThisBuild / organizationName     := "Fortemate"
ThisBuild / organizationHomepage := Some(uri("https://fortemate.com"))
ThisBuild / homepage             := Some(uri("https://fortemate.com"))
ThisBuild / startYear            := Some(2026)
ThisBuild / version              := "0.18.2-SNAPSHOT"
ThisBuild / scalaVersion         := ScalaV

ThisBuild / description   := "Cross-platform high-performance Dice Chess engine, move generator, and AI search."
ThisBuild / licenses      := List(License("AGPL-3.0-only", uri("https://www.gnu.org/licenses/agpl-3.0.txt")))
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

// Publishing (JVM artifact → Maven Central via Sonatype Central Portal).
// sbt-ci-release (see project/plugins.sbt) manages publishTo automatically:
//   - release tag  → Sonatype staging, then promoted to Maven Central
//   - SNAPSHOT     → Sonatype snapshot repository
// Credentials are injected at publish time via SONATYPE_USERNAME / SONATYPE_PASSWORD; the
// GPG key is imported by the CI step and unlocked via PGP_PASSPHRASE (sbt-pgp reads this).
// Local publishing (publishM2, publishLocal) bypasses signing and needs no env vars.

val OnnxRuntimeV = "1.31.1"
// Previous release of com.fortemate:dicechess-rules_3 that the current rules row must stay binary
// compatible with (#246). release.yaml moves it to the version it has just released; keep the
// literal on this one line so that sed finds it.
val RulesMimaBaseline = "0.18.1"

// Fails the build when a coverage run produced no instrumentation metadata (#531).
lazy val assertRulesBinaryCompatible =
  taskKey[Unit]("Fail if dicechess-rules breaks binary compatibility with RulesMimaBaseline (#246)")
lazy val rulesMimaFilters = settingKey[Seq[com.typesafe.tools.mima.core.Problem => Boolean]](
  "Accepted binary incompatibilities of dicechess-rules; every entry carries an issue reference in a comment"
)
lazy val coverageDataCheck = taskKey[Unit]("Verify the coverage run actually instrumented the code")

// Refuse to publish a jar that IS coverage-instrumented.
lazy val assertNoCoverageInstrumentation =
  taskKey[Unit]("Fail if the packaged jar carries scoverage instrumentation")

// Prove the published engine jar carries no bench/arena classes (#564).
lazy val assertNoBenchClasses =
  taskKey[Unit]("Fail if the packaged jar carries dicechess/engine/bench classes")

// Prove the published engine POM marks onnxruntime as optional (#217).
lazy val assertOnnxRuntimeOptionalInPom =
  taskKey[Unit]("Fail if the published engine POM does not mark onnxruntime as optional")

// Prove the published rules POM carries no third-party compile dependency (#219).
lazy val assertRulesPomHasNoThirdPartyDependencies =
  taskKey[Unit]("Fail if the published rules POM declares any non-Scala compile dependency")

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

// The JS rules export root (`js-rules/`) belongs to rootJS ALONE. Its `@JSExportTopLevel(..., "rules")`
// annotations create a second linker module, and the WebAssembly backend rejects that outright
// ("The WebAssembly backend does not support multiple modules"), so rootWasm — which shares `layout("js")`
// with rootJS — must never see these sources. The shared implementation they call lives in
// `js/src/main/scala/dicechess/engine/api/RulesOps.scala` and stays in both rows (ADR 009 / #222).
def jsRulesLayout = Seq(
  Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "js-rules" / "src" / "main" / "scala",
  Test / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "js-rules" / "src" / "test" / "scala"
)

// The rules artifact has no platform-specific sources: one shared-rules/ root for every row.
def rulesLayout = Seq(
  Compile / unmanagedSourceDirectories := Seq(
    (ThisBuild / baseDirectory).value / "shared-rules" / "src" / "main" / "scala"
  ),
  Test / unmanagedSourceDirectories := Seq(
    (ThisBuild / baseDirectory).value / "shared-rules" / "src" / "test" / "scala"
  ),
  Compile / unmanagedResourceDirectories := Seq(
    (ThisBuild / baseDirectory).value / "shared-rules" / "src" / "main" / "resources"
  ),
  Test / unmanagedResourceDirectories := Seq(
    (ThisBuild / baseDirectory).value / "shared-rules" / "src" / "test" / "resources"
  )
)

// Coverage metadata guard shared by every JVM row that runs under scoverage (#531).
def coverageDataCheckSetting = coverageDataCheck := Def.uncached {
  val metadata = coverageDataDir.value / "scoverage-data" / "scoverage.coverage"
  if (!metadata.isFile)
    sys.error(
      s"""Coverage instrumentation metadata is missing: $metadata
         |The compiler did not run, so nothing was measured. Re-run against a cold cache:
         |  mise run coverage""".stripMargin
    )
  streams.value.log.info(s"Coverage instrumentation metadata present: $metadata")
}

// Publish guards shared by every published JVM row (#564, #531, #219): a published jar must be
// uninstrumented, must not carry bench/arena classes, and must ship the licence text.
def publishGuards = Seq(
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
           |  sbt 'clean; <project>/assertNoCoverageInstrumentation; <project>/publish'""".stripMargin
      )
    streams.value.log.info(s"No coverage instrumentation in ${jar.getName}")
  },
  assertNoBenchClasses := Def.uncached {
    val jar    = fileConverter.value.toPath((Compile / packageBin).value).toFile
    val marker = "dicechess/engine/bench/"
    val zip    = new java.util.zip.ZipFile(jar)
    val hits   =
      try zip.entries().asScala.count(_.getName.startsWith(marker))
      finally zip.close()
    if (hits > 0)
      sys.error(
        s"""$jar carries $hits bench class file(s) under $marker.
           |Published artifacts must not ship bench/arena tooling to consumers (see #564).""".stripMargin
      )
    streams.value.log.info(s"No bench classes in ${jar.getName}")
  },
  // A direct `<row>/publish` or `publishLocal` must not bypass the jar guards either; the workflows
  // still run them explicitly so a failure is reported before any registry is touched.
  publishLocal := publishLocal.dependsOn(assertNoCoverageInstrumentation, assertNoBenchClasses).value,
  publish      := publish.dependsOn(assertNoCoverageInstrumentation, assertNoBenchClasses).value,
  // Every published jar carries the licence text (#219). sbt 2 mappings are virtual-file based.
  Compile / packageBin / mappings += licenceMapping.value,
  Compile / packageSrc / mappings += licenceMapping.value,
  Compile / packageDoc / mappings += licenceMapping.value
)

def licenceMapping = Def.task {
  fileConverter.value.toVirtualFile(((ThisBuild / baseDirectory).value / "LICENSE").toPath) -> "META-INF/LICENSE"
}

lazy val commonSettings = Seq(
  name := "dicechess-engine",
  libraryDependencies ++= Seq(
    "io.circe"      %% "circe-core"       % "0.14.17" % Test,
    "io.circe"      %% "circe-generic"    % "0.14.17" % Test,
    "io.circe"      %% "circe-parser"     % "0.14.17" % Test,
    "org.scalameta" %% "munit"            % "1.3.0"   % Test,
    "org.scalameta" %% "munit-scalacheck" % "1.3.1"   % Test
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

// =============================================================================
// dicechess-rules: rule validation, move generation, legal turn enumeration, DFEN, exact
// king-capture probabilities. Zero third-party dependencies. The moved objects keep the package
// `dicechess.engine.search` (split package across the two jars, see ADR 009 / #218).
// =============================================================================
lazy val rules = (projectMatrix in file("shared-rules"))
  .settings(commonSettings)
  .settings(
    name        := "dicechess-rules",
    description := "Dice Chess rules core: domain model, DFEN, bitboard move generation, legal turn enumeration."
  )
  .defaultAxes(VirtualAxis.scalaABIVersion(ScalaV))
  .jvmPlatform(
    scalaVersions = Seq(ScalaV),
    settings = rulesLayout ++ publishGuards ++ Seq(
      coverageMinimumStmtTotal := 95, // measured 98.89 % statement coverage at the split (#218)
      // Binary-compatibility gate (#246, ADR 009 addendum): the rules jar is compared with the previous
      // release using mima-core. The baseline is retrieved ad hoc through dependencyResolution rather than as
      // a libraryDependency: sbt drops a dependency on the project's own module id, and an ad-hoc retrieve
      // keeps the baseline off the compile classpath and out of the published POM. An intentional break is
      // accepted with a ProblemFilters.exclude entry in rulesMimaFilters, each with the issue that justifies it.
      // Scala 3.10 refines boxed-primitive generic signatures (scala/scala3#25944) and adds
      // type parameters to synthetic copy defaults. JVM descriptors are unchanged. Accept only
      // the reviewed members and exact old/new signatures against this baseline; retire on the next release.
      rulesMimaFilters := {
        import com.typesafe.tools.mima.core.{IncompatibleSignatureProblem, Problem, ProblemFilters}
        def signatureChange(member: String, previous: String, current: String): Problem => Boolean = {
          val keep = ProblemFilters.exclude[IncompatibleSignatureProblem](member)
          problem =>
            problem match {
              case IncompatibleSignatureProblem(oldMethod, newMethod)
                  if oldMethod.signature.toString == previous && newMethod.signature.toString == current =>
                keep(problem)
              case _ => true
            }
        }
        if (RulesMimaBaseline == "0.17.1")
          Seq(
            signatureChange(
              "dicechess.engine.domain.GameFlags#package#GameFlags.given_CanEqual_GameFlags_GameFlags()scala.CanEqual",
              "()Lscala/CanEqual<Ljava/lang/Object;Ljava/lang/Object;>;",
              "()Lscala/CanEqual<Ljava/lang/Integer;Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.GameFlags#package#GameFlags.fromList(Int,Int,Int,scala.collection.immutable.List,Int)Int",
              "(IIILscala/collection/immutable/List<Ljava/lang/Object;>;I)I",
              "(IIILscala/collection/immutable/List<Ljava/lang/Integer;>;I)I"
            ),
            signatureChange(
              "dicechess.engine.domain.GameFlags#package#GameFlags.dicePool(Int)scala.collection.immutable.List",
              "(I)Lscala/collection/immutable/List<Ljava/lang/Object;>;",
              "(I)Lscala/collection/immutable/List<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.GameFlags#package#GameFlags.addDie(Int,Int)scala.util.Either",
              "(II)Lscala/util/Either<Ljava/lang/String;Ljava/lang/Object;>;",
              "(II)Lscala/util/Either<Ljava/lang/String;Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.GameFlags#package#GameFlags.withDicePool(Int,scala.collection.immutable.List)Int",
              "(ILscala/collection/immutable/List<Ljava/lang/Object;>;)I",
              "(ILscala/collection/immutable/List<Ljava/lang/Integer;>;)I"
            ),
            signatureChange(
              "dicechess.engine.domain.GameState.withDicePool(scala.collection.immutable.List)dicechess.engine.domain.GameState",
              "(Lscala/collection/immutable/List<Ljava/lang/Object;>;)Ldicechess/engine/domain/GameState;",
              "(Lscala/collection/immutable/List<Ljava/lang/Integer;>;)Ldicechess/engine/domain/GameState;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#Bitboard.given_CanEqual_Bitboard_Bitboard()scala.CanEqual",
              "()Lscala/CanEqual<Ljava/lang/Object;Ljava/lang/Object;>;",
              "()Lscala/CanEqual<Ljava/lang/Long;Ljava/lang/Long;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#Color.given_CanEqual_Color_Color()scala.CanEqual",
              "()Lscala/CanEqual<Ljava/lang/Object;Ljava/lang/Object;>;",
              "()Lscala/CanEqual<Ljava/lang/Integer;Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#MicroMove.given_CanEqual_MicroMove_MicroMove()scala.CanEqual",
              "()Lscala/CanEqual<Ljava/lang/Object;Ljava/lang/Object;>;",
              "()Lscala/CanEqual<Ljava/lang/Integer;Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#MicroMove.apply(Int,Int,scala.Option)Int",
              "(IILscala/Option<Ljava/lang/Object;>;)I",
              "(IILscala/Option<Ljava/lang/Integer;>;)I"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#MicroMove.apply$default$3()scala.Option",
              "()Lscala/Option<Ljava/lang/Object;>;",
              "()Lscala/Option<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#MicroMove.promotion(Int)scala.Option",
              "(I)Lscala/Option<Ljava/lang/Object;>;",
              "(I)Lscala/Option<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#Piece.given_CanEqual_Piece_Piece()scala.CanEqual",
              "()Lscala/CanEqual<Ljava/lang/Object;Ljava/lang/Object;>;",
              "()Lscala/CanEqual<Ljava/lang/Integer;Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#PieceType.given_CanEqual_PieceType_PieceType()scala.CanEqual",
              "()Lscala/CanEqual<Ljava/lang/Object;Ljava/lang/Object;>;",
              "()Lscala/CanEqual<Ljava/lang/Integer;Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#PieceType.all()scala.collection.immutable.List",
              "()Lscala/collection/immutable/List<Ljava/lang/Object;>;",
              "()Lscala/collection/immutable/List<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#PieceType.fromDice(Int)scala.Option",
              "(I)Lscala/Option<Ljava/lang/Object;>;",
              "(I)Lscala/Option<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#Square.given_CanEqual_Square_Square()scala.CanEqual",
              "()Lscala/CanEqual<Ljava/lang/Object;Ljava/lang/Object;>;",
              "()Lscala/CanEqual<Ljava/lang/Integer;Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Models#package#Square.fromNotation(java.lang.String)scala.Option",
              "(Ljava/lang/String;)Lscala/Option<Ljava/lang/Object;>;",
              "(Ljava/lang/String;)Lscala/Option<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Move#package#Move.promotionPieceType(Int)scala.Option",
              "(I)Lscala/Option<Ljava/lang/Object;>;",
              "(I)Lscala/Option<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Turn.this(Int,scala.collection.immutable.List)Unit",
              "(ILscala/collection/immutable/List<Ljava/lang/Object;>;)V",
              "(ILscala/collection/immutable/List<Ljava/lang/Integer;>;)V"
            ),
            signatureChange(
              "dicechess.engine.domain.Turn.microMoves()scala.collection.immutable.List",
              "()Lscala/collection/immutable/List<Ljava/lang/Object;>;",
              "()Lscala/collection/immutable/List<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Turn.copy(Int,scala.collection.immutable.List)dicechess.engine.domain.Turn",
              "(ILscala/collection/immutable/List<Ljava/lang/Object;>;)Ldicechess/engine/domain/Turn;",
              "(ILscala/collection/immutable/List<Ljava/lang/Integer;>;)Ldicechess/engine/domain/Turn;"
            ),
            signatureChange(
              "dicechess.engine.domain.Turn.copy$default$2()scala.collection.immutable.List",
              "()Lscala/collection/immutable/List<Ljava/lang/Object;>;",
              "()Lscala/collection/immutable/List<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Turn._2()scala.collection.immutable.List",
              "()Lscala/collection/immutable/List<Ljava/lang/Object;>;",
              "()Lscala/collection/immutable/List<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.domain.Turn.apply(Int,scala.collection.immutable.List)dicechess.engine.domain.Turn",
              "(ILscala/collection/immutable/List<Ljava/lang/Object;>;)Ldicechess/engine/domain/Turn;",
              "(ILscala/collection/immutable/List<Ljava/lang/Integer;>;)Ldicechess/engine/domain/Turn;"
            ),
            signatureChange(
              "dicechess.engine.movegen.LegalMovesFilter.filterMaximalMoves(dicechess.engine.domain.GameState)scala.collection.immutable.List",
              "(Ldicechess/engine/domain/GameState;)Lscala/collection/immutable/List<Ljava/lang/Object;>;",
              "(Ldicechess/engine/domain/GameState;)Lscala/collection/immutable/List<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.movegen.MoveGenerator.generateMoves(dicechess.engine.domain.GameState,Long)scala.collection.immutable.List",
              "(Ldicechess/engine/domain/GameState;J)Lscala/collection/immutable/List<Ljava/lang/Object;>;",
              "(Ldicechess/engine/domain/GameState;J)Lscala/collection/immutable/List<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.movegen.MoveGenerator.generateMoves(dicechess.engine.domain.GameState)scala.collection.immutable.List",
              "(Ldicechess/engine/domain/GameState;)Lscala/collection/immutable/List<Ljava/lang/Object;>;",
              "(Ldicechess/engine/domain/GameState;)Lscala/collection/immutable/List<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.movegen.MoveGenerator.generateAllMoves(dicechess.engine.domain.GameState)scala.collection.immutable.List",
              "(Ldicechess/engine/domain/GameState;)Lscala/collection/immutable/List<Ljava/lang/Object;>;",
              "(Ldicechess/engine/domain/GameState;)Lscala/collection/immutable/List<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.movegen.Perft.divide(dicechess.engine.domain.GameState,Int)scala.collection.immutable.Map",
              "(Ldicechess/engine/domain/GameState;I)Lscala/collection/immutable/Map<Ljava/lang/String;Ljava/lang/Object;>;",
              "(Ldicechess/engine/domain/GameState;I)Lscala/collection/immutable/Map<Ljava/lang/String;Ljava/lang/Long;>;"
            ),
            signatureChange(
              "dicechess.engine.search.BudgetedRules.visitTurnStates(dicechess.engine.domain.GameState,dicechess.engine.search.BudgetedRules#Budget,scala.Function4)dicechess.engine.search.BudgetedRules#Outcome",
              "(Ldicechess/engine/domain/GameState;Ldicechess/engine/search/BudgetedRules$Budget;Lscala/Function4<[ILjava/lang/Object;Ldicechess/engine/domain/GameState;Ljava/lang/Object;Ljava/lang/Object;>;)Ldicechess/engine/search/BudgetedRules$Outcome<Ldicechess/engine/search/BudgetedRules$TurnVisitSummary;>;",
              "(Ldicechess/engine/domain/GameState;Ldicechess/engine/search/BudgetedRules$Budget;Lscala/Function4<[ILjava/lang/Integer;Ldicechess/engine/domain/GameState;Ljava/lang/Boolean;Ljava/lang/Boolean;>;)Ldicechess/engine/search/BudgetedRules$Outcome<Ldicechess/engine/search/BudgetedRules$TurnVisitSummary;>;"
            ),
            signatureChange(
              "dicechess.engine.search.BudgetedRules.foldTurnStates(dicechess.engine.domain.GameState,java.lang.Object,dicechess.engine.search.BudgetedRules#Budget,scala.Function4)dicechess.engine.search.BudgetedRules#Outcome",
              "<A:Ljava/lang/Object;>(Ldicechess/engine/domain/GameState;TA;Ldicechess/engine/search/BudgetedRules$Budget;Lscala/Function4<TA;Lscala/collection/immutable/List<Ljava/lang/Object;>;Ldicechess/engine/domain/GameState;Ljava/lang/Object;Lscala/Tuple2<TA;Ljava/lang/Object;>;>;)Ldicechess/engine/search/BudgetedRules$Outcome<Ldicechess/engine/search/BudgetedRules$TurnFoldResult<TA;>;>;",
              "<A:Ljava/lang/Object;>(Ldicechess/engine/domain/GameState;TA;Ldicechess/engine/search/BudgetedRules$Budget;Lscala/Function4<TA;Lscala/collection/immutable/List<Ljava/lang/Integer;>;Ldicechess/engine/domain/GameState;Ljava/lang/Boolean;Lscala/Tuple2<TA;Ljava/lang/Boolean;>;>;)Ldicechess/engine/search/BudgetedRules$Outcome<Ldicechess/engine/search/BudgetedRules$TurnFoldResult<TA;>;>;"
            ),
            signatureChange(
              "dicechess.engine.search.BudgetedRules.turnPaths(dicechess.engine.domain.GameState,dicechess.engine.search.BudgetedRules#Budget)dicechess.engine.search.BudgetedRules#Outcome",
              "(Ldicechess/engine/domain/GameState;Ldicechess/engine/search/BudgetedRules$Budget;)Ldicechess/engine/search/BudgetedRules$Outcome<Lscala/collection/immutable/List<Lscala/collection/immutable/List<Ljava/lang/Object;>;>;>;",
              "(Ldicechess/engine/domain/GameState;Ldicechess/engine/search/BudgetedRules$Budget;)Ldicechess/engine/search/BudgetedRules$Outcome<Lscala/collection/immutable/List<Lscala/collection/immutable/List<Ljava/lang/Integer;>;>;>;"
            ),
            signatureChange(
              "dicechess.engine.search.BudgetedRules.kingCapturePath(dicechess.engine.domain.GameState,dicechess.engine.search.BudgetedRules#Budget)dicechess.engine.search.BudgetedRules#Outcome",
              "(Ldicechess/engine/domain/GameState;Ldicechess/engine/search/BudgetedRules$Budget;)Ldicechess/engine/search/BudgetedRules$Outcome<Lscala/Option<Lscala/collection/immutable/List<Ljava/lang/Object;>;>;>;",
              "(Ldicechess/engine/domain/GameState;Ldicechess/engine/search/BudgetedRules$Budget;)Ldicechess/engine/search/BudgetedRules$Outcome<Lscala/Option<Lscala/collection/immutable/List<Ljava/lang/Integer;>;>;>;"
            ),
            signatureChange(
              "dicechess.engine.search.BudgetedRules.kingCaptureRolls(dicechess.engine.domain.GameState,Int,dicechess.engine.search.BudgetedRules#Budget)dicechess.engine.search.BudgetedRules#Outcome",
              "(Ldicechess/engine/domain/GameState;ILdicechess/engine/search/BudgetedRules$Budget;)Ldicechess/engine/search/BudgetedRules$Outcome<Ljava/lang/Object;>;",
              "(Ldicechess/engine/domain/GameState;ILdicechess/engine/search/BudgetedRules$Budget;)Ldicechess/engine/search/BudgetedRules$Outcome<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.search.BudgetedRules#Outcome#Complete.copy$default$2()Long",
              "<missing>",
              "<A:Ljava/lang/Object;>()J"
            ),
            signatureChange(
              "dicechess.engine.search.BudgetedRules#Outcome#Incomplete.copy$default$1()Long",
              "<missing>",
              "<A:Ljava/lang/Object;>()J"
            ),
            signatureChange(
              "dicechess.engine.search.BudgetedRules#TurnFoldResult.copy$default$2()Boolean",
              "<missing>",
              "<A:Ljava/lang/Object;>()Z"
            ),
            signatureChange(
              "dicechess.engine.search.CompactRoll#package#CompactRoll.given_CanEqual_CompactRoll_CompactRoll()scala.CanEqual",
              "()Lscala/CanEqual<Ljava/lang/Object;Ljava/lang/Object;>;",
              "()Lscala/CanEqual<Ljava/lang/Integer;Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.search.CompactRoll#package#CompactRoll.fromSortedList(scala.collection.immutable.List)Int",
              "(Lscala/collection/immutable/List<Ljava/lang/Object;>;)I",
              "(Lscala/collection/immutable/List<Ljava/lang/Integer;>;)I"
            ),
            signatureChange(
              "dicechess.engine.search.CompactRoll#package#CompactRoll.toList(Int)scala.collection.immutable.List",
              "(I)Lscala/collection/immutable/List<Ljava/lang/Object;>;",
              "(I)Lscala/collection/immutable/List<Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.search.DiceRolls.weighted()Array[scala.Tuple2]",
              "()[Lscala/Tuple2<Lscala/collection/immutable/List<Ljava/lang/Object;>;Ljava/lang/Object;>;",
              "()[Lscala/Tuple2<Lscala/collection/immutable/List<Ljava/lang/Integer;>;Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.search.DiceRolls.byWeightDescending()Array[scala.Tuple2]",
              "()[Lscala/Tuple2<Lscala/collection/immutable/List<Ljava/lang/Object;>;Ljava/lang/Object;>;",
              "()[Lscala/Tuple2<Lscala/collection/immutable/List<Ljava/lang/Integer;>;Ljava/lang/Integer;>;"
            ),
            signatureChange(
              "dicechess.engine.search.PlayableDice.of(dicechess.engine.domain.GameState,scala.collection.immutable.Seq)scala.Option",
              "(Ldicechess/engine/domain/GameState;Lscala/collection/immutable/Seq<Ljava/lang/Object;>;)Lscala/Option<Lscala/collection/immutable/List<Ljava/lang/Object;>;>;",
              "(Ldicechess/engine/domain/GameState;Lscala/collection/immutable/Seq<Ljava/lang/Integer;>;)Lscala/Option<Lscala/collection/immutable/List<Ljava/lang/Integer;>;>;"
            ),
            signatureChange(
              "dicechess.engine.search.TurnGenerator.generateAllLegalTurnPaths(dicechess.engine.domain.GameState)scala.collection.immutable.List",
              "(Ldicechess/engine/domain/GameState;)Lscala/collection/immutable/List<Lscala/collection/immutable/List<Ljava/lang/Object;>;>;",
              "(Ldicechess/engine/domain/GameState;)Lscala/collection/immutable/List<Lscala/collection/immutable/List<Ljava/lang/Integer;>;>;"
            ),
            signatureChange(
              "dicechess.engine.search.TurnGenerator.forEachLegalTurnPath(dicechess.engine.domain.GameState,scala.Function2)Unit",
              "(Ldicechess/engine/domain/GameState;Lscala/Function2<[ILjava/lang/Object;Lscala/runtime/BoxedUnit;>;)V",
              "(Ldicechess/engine/domain/GameState;Lscala/Function2<[ILjava/lang/Integer;Lscala/runtime/BoxedUnit;>;)V"
            ),
            signatureChange(
              "dicechess.engine.search.TurnGenerator.forEachLegalTurnState(dicechess.engine.domain.GameState,scala.Function4)Int",
              "(Ldicechess/engine/domain/GameState;Lscala/Function4<[ILjava/lang/Object;Ldicechess/engine/domain/GameState;Ljava/lang/Object;Ljava/lang/Object;>;)I",
              "(Ldicechess/engine/domain/GameState;Lscala/Function4<[ILjava/lang/Integer;Ldicechess/engine/domain/GameState;Ljava/lang/Boolean;Ljava/lang/Boolean;>;)I"
            )
          )
        else Seq.empty
      },
      assertRulesBinaryCompatible := Def.uncached {
        import com.typesafe.tools.mima.lib.MiMaLib
        val log     = streams.value.log
        val mimaLog = new com.typesafe.tools.mima.core.util.log.Logging {
          def verbose(msg: String): Unit = log.debug(msg)
          def debug(msg: String): Unit   = log.debug(msg)
          def warn(msg: String): Unit    = log.warn(msg)
          def error(msg: String): Unit   = log.error(msg)
        }
        val conv       = fileConverter.value
        val newJar     = conv.toPath((Compile / packageBin).value).toFile
        val resolver   = dependencyResolution.value
        val baselineId = ("com.fortemate" % "dicechess-rules_3" % RulesMimaBaseline)
          .intransitive() // explicit _3: no scalaModuleInfo here
        val retrieveDir  = target.value / "mima-baseline"
        val baselineJars = resolver
          .retrieve(resolver.wrapDependencyInModule(baselineId), retrieveDir, log)
          .fold(
            w =>
              sys.error(s"could not resolve the rules baseline $RulesMimaBaseline: ${w.resolveException.getMessage}"),
            identity
          )
        val oldJar: java.io.File = baselineJars
          .find(f =>
            f.getName.startsWith("dicechess-rules_3") && f.getName.endsWith(".jar") && !f.getName.contains("-sources")
          )
          .getOrElse(
            sys.error(s"baseline jar dicechess-rules_3 $RulesMimaBaseline was not among ${baselineJars.map(_.getName)}")
          )
        val classpath = (Compile / fullClasspath).value.map(entry => conv.toPath(entry.data).toFile)
        val problems  = new MiMaLib(classpath, mimaLog).collectProblems(oldJar, newJar, Nil)
        val filters   = rulesMimaFilters.value
        val remaining = problems.filter(problem => filters.forall(keep => keep(problem)))
        if (remaining.nonEmpty)
          sys.error(
            s"""dicechess-rules ${version.value} is not binary compatible with $RulesMimaBaseline (${remaining.size} problem(s)):
               |${remaining.map(p => "  " + p.description("current")).mkString("\n")}
               |An intentional break needs a rulesMimaFilters entry with an issue reference, e.g.
               |${remaining.flatMap(_.howToFilter).distinct.map("  " + _).mkString("\n")}""".stripMargin
          )
        log.info(
          s"dicechess-rules ${version.value} is binary compatible with $RulesMimaBaseline (${problems.size} problem(s) reported, ${problems.size - remaining.size} accepted by filters)"
        )
      },
      Test / exportJars := false,
      coverageDataCheckSetting,
      assertRulesPomHasNoThirdPartyDependencies := Def.uncached {
        val pomFile   = fileConverter.value.toPath(makePom.value).toFile
        val xml       = scala.xml.XML.loadFile(pomFile)
        val deps      = xml \ "dependencies" \ "dependency"
        val offending = deps.filter { d =>
          val scope = (d \ "scope").text.trim
          val group = (d \ "groupId").text.trim
          (scope.isEmpty || scope == "compile" || scope == "runtime") && group != "org.scala-lang"
        }
        if (offending.nonEmpty)
          sys.error(
            s"""${pomFile.getName} declares third-party compile/runtime dependencies for dicechess-rules:
               |${offending.map(d => "  " + (d \ "groupId").text + ":" + (d \ "artifactId").text).mkString("\n")}
               |The rules artifact must depend on the Scala standard library only (ADR 009).""".stripMargin
          )
        streams.value.log.info(s"Verified ${pomFile.getName} has no third-party compile dependency")
      },
      publishLocal := publishLocal.dependsOn(assertRulesPomHasNoThirdPartyDependencies).value,
      publish      := publish.dependsOn(assertRulesPomHasNoThirdPartyDependencies).value,
      Compile / doc / scalacOptions ++= Seq(
        "-project",
        name.value,
        "-project-version",
        version.value,
        "-project-footer",
        "Fortemate Dice Chess Rules",
        "-source-links:shared-rules=github://fortemate/dicechess-engine/main#shared-rules",
        "-social-links:github::https://github.com/fortemate/dicechess-engine",
        "-groups",
        "-author",
        "-snippet-compiler:compile"
      )
    )
  )
  .jsPlatform(
    scalaVersions = Seq(ScalaV),
    settings = rulesLayout ++ Seq(
      coverageEnabled                 := false,
      scalaJSUseMainModuleInitializer := false,
      scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
    )
  )

lazy val rulesJVM = rules.jvm(ScalaV)
lazy val rulesJS  = rules.js(ScalaV)

lazy val root = (projectMatrix in file("."))
  .dependsOn(rules % "compile->compile;test->test")
  .settings(commonSettings)
  .defaultAxes(VirtualAxis.scalaABIVersion(ScalaV))
  .jvmPlatform(
    scalaVersions = Seq(ScalaV),
    settings = layout("jvm") ++ publishGuards ++ Seq(
      coverageMinimumStmtTotal                          := 90,
      libraryDependencies += "com.microsoft.onnxruntime" % "onnxruntime" % OnnxRuntimeV % Optional,
      Test / exportJars                                 := false,
      coverageDataCheckSetting,
      assertOnnxRuntimeOptionalInPom := Def.uncached {
        val pomFile = fileConverter.value.toPath(makePom.value).toFile
        val xml     = scala.xml.XML.loadFile(pomFile)
        val deps    = xml \ "dependencies" \ "dependency"
        val onnxDep = deps.find { d =>
          (d \ "groupId").text.trim == "com.microsoft.onnxruntime" &&
          (d \ "artifactId").text.trim == "onnxruntime"
        }
        onnxDep match {
          case None =>
            sys.error(s"onnxruntime dependency was not found in generated POM: $pomFile")
          case Some(dep) =>
            val isOptional = (dep \ "optional").text.trim == "true"
            if (!isOptional) {
              sys.error(
                s"""onnxruntime in $pomFile is not marked <optional>true</optional>.
                   |Found dependency node:
                   |$dep""".stripMargin
              )
            }
        }
        streams.value.log.info(s"Verified onnxruntime is marked optional in ${pomFile.getName}")
      },
      publishLocal := publishLocal.dependsOn(assertOnnxRuntimeOptionalInPom).value,
      publish      := publish.dependsOn(assertOnnxRuntimeOptionalInPom).value,
      Compile / doc / scalacOptions ++= Seq(
        "-project",
        name.value,
        "-project-version",
        version.value,
        "-project-footer",
        "Fortemate Dice Chess Engine",
        "-source-links:shared=github://fortemate/dicechess-engine/main#shared",
        "-source-links:jvm=github://fortemate/dicechess-engine/main#jvm",
        "-social-links:github::https://github.com/fortemate/dicechess-engine",
        "-groups",
        "-author",
        "-snippet-compiler:compile"
      )
    )
  )
  .jsPlatform(
    scalaVersions = Seq(ScalaV),
    settings = layout("js") ++ jsRulesLayout ++ Seq(
      coverageEnabled                 := false,
      scalaJSUseMainModuleInitializer := false,
      // Two export roots, one linker output (ADR 009 / #222): `DiceChess` in module `main` and the rules-only
      // `DiceChess` in module `rules`, published as the `.` and `./rules` subpaths of the same npm package.
      // FewestModules is what the split needs — one module per export root plus one internal module for the
      // code both reach, so importing `./rules` never downloads `dicechess.engine.search`. The smaller-module
      // styles were measured on these sources and rejected on the numbers, not on taste: SmallModulesFor
      // ("dicechess") gives 960,709 B through `./rules` in 26 modules and SmallModulesFor("dicechess",
      // "scala") 961,529 B in 50, against 891,712 B in 2 here — per-class modules cost more in boilerplate
      // than the finer granularity saves.
      scalaJSLinkerConfig ~= {
        _.withModuleKind(ModuleKind.ESModule)
          .withModuleSplitStyle(ModuleSplitStyle.FewestModules)
      }
    )
  )

lazy val rootJVM = root.jvm(ScalaV)
lazy val rootJS  = root.js(ScalaV)

// Explicit root aggregate project to avoid sbt 2 empty synthetic root issues.
lazy val dicechessEngine = (project in file("."))
  // apiDocs is deliberately outside the aggregate: it re-compiles the rules + engine sources only to
  // produce one unified scaladoc site, so `testOnly *` / `coverage` must not touch it (CI and the docs
  // deployment call `apiDocs/doc` explicitly).
  .aggregate(rulesJVM, rulesJS, rootJVM, rootJS, rootWasm, rulesSmoke, benchmark, arena, cli)
  .settings(
    name := "dicechess-engine-aggregate",
    // sonaRelease runs from this unpublished aggregate project. Override sbt's default
    // aggregate-based name and random suffix with the published, Scala-cross-versioned JVM
    // coordinate. sonaDeploymentName is Central Portal display metadata only: it does not
    // change the staged bundle, artifact coordinates, or the number of deployments.
    sonaDeploymentName := {
      def coordinate(id: ModuleID, sv: String, sbv: String) = {
        val m = CrossVersion(sv, sbv)(id)
        s"${m.organization}:${m.name}:${m.revision}"
      }
      // One Central Portal deployment carries both coordinates of a release (ADR 009 / #219).
      coordinate((rootJVM / projectID).value, (rootJVM / scalaVersion).value, (rootJVM / scalaBinaryVersion).value) +
        " + " + coordinate(
          (rulesJVM / projectID).value,
          (rulesJVM / scalaVersion).value,
          (rulesJVM / scalaBinaryVersion).value
        )
    },
    publish / skip := true
  )

lazy val rootWasm = project
  .in(file(".wasm"))
  .dependsOn(rulesJS % "compile->compile;test->test")
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
    scalacOptions -= "-Werror",
    libraryDependencies += "com.microsoft.onnxruntime" % "onnxruntime" % OnnxRuntimeV
  )

lazy val arena = project
  .in(file("arena"))
  .dependsOn(rootJVM)
  .settings(commonSettings)
  .settings(
    name := "dicechess-arena",
    libraryDependencies ++= Seq(
      "com.monovore"             %% "decline"     % "2.6.2",
      "org.typelevel"            %% "cats-core"   % "2.13.0",
      "com.microsoft.onnxruntime" % "onnxruntime" % OnnxRuntimeV
    ),
    publish / skip := true,
    Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "jvm" / "src" / "test" / "resources",
    Test / exportJars        := false,
    coverageExcludedPackages := "dicechess\\.engine\\.bench\\.egtb\\..*",
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
      "com.monovore"             %% "decline"     % "2.6.2",
      "org.typelevel"            %% "cats-core"   % "2.13.0",
      "org.jline"                 % "jline"       % "4.4.8",
      "com.microsoft.onnxruntime" % "onnxruntime" % OnnxRuntimeV
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

// Consumer smoke check for the rules artifact (#218): compiles and runs against rulesJVM ALONE, so a
// rules-only program must work without a single search class or onnxruntime on the classpath.
lazy val rulesSmoke = project
  .in(file("rules-smoke"))
  .dependsOn(rulesJVM)
  .settings(commonSettings)
  .settings(
    name                    := "dicechess-rules-smoke",
    publish / skip          := true,
    coverageEnabled         := false,
    Compile / doc / sources := Seq.empty
  )

// The docs site publishes ONE API tree (/api/). sbt 2 documents a project from its own sources, so a
// project that sees all three JVM source roots reproduces the pre-split tree; it is never published
// and has no tests (#219, ADR 009).
lazy val apiDocs = project
  .in(file("api-docs"))
  .settings(commonSettings)
  .settings(
    name                                              := "dicechess-api-docs",
    scalaVersion                                      := ScalaV,
    publish / skip                                    := true,
    coverageEnabled                                   := false,
    libraryDependencies += "com.microsoft.onnxruntime" % "onnxruntime" % OnnxRuntimeV,
    Compile / unmanagedSourceDirectories              := Seq(
      (ThisBuild / baseDirectory).value / "shared-rules" / "src" / "main" / "scala",
      (ThisBuild / baseDirectory).value / "shared" / "src" / "main" / "scala",
      (ThisBuild / baseDirectory).value / "jvm" / "src" / "main" / "scala"
    ),
    Test / unmanagedSourceDirectories := Seq.empty,
    Compile / doc / scalacOptions ++= Seq(
      "-project",
      "dicechess-engine",
      "-project-version",
      version.value,
      "-project-footer",
      "Fortemate Dice Chess Engine",
      "-source-links:shared-rules=github://fortemate/dicechess-engine/main#shared-rules",
      "-source-links:shared=github://fortemate/dicechess-engine/main#shared",
      "-source-links:jvm=github://fortemate/dicechess-engine/main#jvm",
      "-social-links:github::https://github.com/fortemate/dicechess-engine",
      "-groups",
      "-author",
      "-snippet-compiler:compile"
    )
  )
