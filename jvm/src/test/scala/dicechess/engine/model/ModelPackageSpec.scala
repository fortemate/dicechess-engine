// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

import ai.onnxruntime.{OrtEnvironment, OrtSession}
import dicechess.engine.domain.{Color, FenParser}
import dicechess.engine.search.KcpFeatures
import munit.FunSuite

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.util.Using

/** Loading a package is the moment every promise in a manifest is either proved or refused, so these tests are about
  * what reaches a search and what never does (#78).
  */
class ModelPackageSpec extends FunSuite:

  private val engineVersion = ContractFixtures.EngineVersion

  private def rejection(result: Either[String, ?]): String =
    result.swap.getOrElse(fail(s"expected a rejection, got $result"))

  /** A package directory in the layout a trained candidate ships: `model.onnx` + `manifest.json`. */
  private def packagedIn(manifest: Path)(body: Path => Unit): Unit =
    val directory = Files.createTempDirectory("model-package")
    try
      Files.copy(ContractFixtures.valueModel, directory.resolve(ModelPackage.ModelFileName))
      Files.copy(manifest, directory.resolve(ModelPackage.ManifestFileName))
      body(directory)
    finally Files.walk(directory).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))

  test("a package directory loads into the role and the extractor its manifest names"):
    packagedIn(ContractFixtures.valueManifest): directory =>
      val loaded = ModelPackage.load(directory, engineVersion).getOrElse(fail("package must load"))
      assertEquals(loaded.role, ModelRole.PositionValue)
      assertEquals(loaded.schema.id, "kcp-13")
      assertEquals(loaded.featureCount, 13)
      assertEquals(loaded.modelPath, directory.resolve(ModelPackage.ModelFileName))
      // The resolved extractor is the engine's own kcp-13 extractor, not merely something of the right width.
      val position = FenParser.parse(FenParser.InitialPosition).toOption.get
      assertEquals(
        loaded.extract(position, Color.White).toList,
        KcpFeatures.extract(position, Color.White).toList
      )

  test("the expected role is a gate: a collapse model cannot be loaded as a value model"):
    packagedIn(ContractFixtures.collapseManifest): directory =>
      val loaded = ModelPackage.load(directory, engineVersion, Some(ModelRole.ChanceCollapse))
      assertEquals(loaded.map(_.role), Right(ModelRole.ChanceCollapse))
      val refused = rejection(ModelPackage.load(directory, engineVersion, Some(ModelRole.PositionValue)))
      assert(refused.contains("modelRole 'chance-collapse' cannot serve as 'position-value'"), refused)

  test("a legacy manifest loads as a value model and satisfies the value-model gate"):
    packagedIn(ContractFixtures.legacyManifest): directory =>
      val loaded = ModelPackage.load(directory, engineVersion, Some(ModelRole.PositionValue))
      assertEquals(loaded.map(_.manifest.manifestVersion), Right(ModelManifest.LegacyVersion))

  test("an engine outside the manifest's compatibility range cannot load the package"):
    packagedIn(ContractFixtures.valueManifest): directory =>
      val refused = rejection(ModelPackage.load(directory, "0.11.0"))
      assert(refused.contains("does not include engine version '0.11.0'"), refused)
      assert(refused.contains(ModelPackage.ManifestFileName), refused)

  test("a manifest whose digest describes other bytes cannot load the package"):
    val directory = Files.createTempDirectory("model-package-digest")
    try
      Files.copy(ContractFixtures.legacyNamedModel, directory.resolve(ModelPackage.ModelFileName))
      Files.copy(ContractFixtures.valueManifest, directory.resolve(ModelPackage.ManifestFileName))
      assert(rejection(ModelPackage.load(directory, engineVersion)).contains("model SHA-256 mismatch"))
    finally Files.walk(directory).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))

  test("a missing model or manifest is reported with the path that is missing"):
    val directory = Files.createTempDirectory("model-package-empty")
    try
      val missingModel = rejection(ModelPackage.load(directory, engineVersion))
      assert(missingModel.contains(ModelPackage.ModelFileName), missingModel)
      assert(missingModel.contains("model file not readable"), missingModel)
      Files.copy(ContractFixtures.valueModel, directory.resolve(ModelPackage.ModelFileName))
      val missingManifest = rejection(ModelPackage.load(directory, engineVersion))
      assert(missingManifest.contains(ModelPackage.ManifestFileName), missingManifest)
      assert(missingManifest.contains("not readable"), missingManifest)
    finally Files.walk(directory).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))

  test("files outside a package directory load through the explicit-paths entry point"):
    val loaded = ModelPackage
      .loadFiles(ContractFixtures.valueModel, ContractFixtures.valueManifest, engineVersion)
      .getOrElse(fail("fixture pair must load"))
    assertEquals(loaded.manifest.modelId, "synthetic-kcp13-value")

  test("a loaded package validates the session it is about to be served from"):
    val loaded = ModelPackage
      .loadFiles(ContractFixtures.valueModel, ContractFixtures.valueManifest, engineVersion)
      .getOrElse(fail("fixture pair must load"))
    val env = OrtEnvironment.getEnvironment
    Using.resource(env.createSession(loaded.modelPath.toString, new OrtSession.SessionOptions())): session =>
      assertEquals(loaded.validateSession(session), Right(()))

  test("a package whose graph contradicts its manifest is refused at the session, with the model path"):
    val copied = Files.createTempFile("model-contract-mismatch", ".onnx")
    try
      Files.copy(ContractFixtures.legacyNamedModel, copied, StandardCopyOption.REPLACE_EXISTING)
      val manifest = ModelManifest
        .load(ContractFixtures.valueManifest)
        .map(_.copy(modelSha256 = ModelManifest.computeSha256(copied).getOrElse(fail("must digest"))))
        .getOrElse(fail("fixture must parse"))
      val loaded = ModelPackage(copied, manifest, ModelRole.PositionValue, FeatureSchema.Kcp13)
      val env    = OrtEnvironment.getEnvironment
      Using.resource(env.createSession(copied.toString, new OrtSession.SessionOptions())): session =>
        val refused = rejection(loaded.validateSession(session))
        assert(refused.contains(copied.toString), refused)
        assert(refused.contains("exactly one output named 'output'"), refused)
    finally Files.deleteIfExists(copied)
