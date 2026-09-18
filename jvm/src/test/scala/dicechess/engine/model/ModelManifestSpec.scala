// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

import munit.FunSuite

import java.nio.file.Files

/** Manifest reading and validation (#78). Every rejection here is a failure mode that would otherwise reach production
  * as a bot that runs and plays worse than it measured, so the tests are written as "which artifacts must be refused",
  * not as "which fields parse".
  */
class ModelManifestSpec extends FunSuite:

  private val engineVersion = ContractFixtures.EngineVersion

  private def valid = ModelManifest(
    manifestVersion = ModelManifest.CurrentVersion,
    modelId = "unit-test-model",
    modelSha256 = "a" * 64,
    modelRole = ModelRole.PositionValue.id,
    featureSchema = "kcp-13",
    featureCount = 13,
    inputName = "input",
    outputName = "output",
    perspective = ModelManifest.MoverPerspective,
    engineCompatibility = ">=0.12.0 <1.0.0"
  )

  private def rejection(result: Either[String, ?]): String =
    result.swap.getOrElse(fail(s"expected a rejection, got $result"))

  test("a current manifest parses every field it declares"):
    val manifest = ModelManifest.load(ContractFixtures.valueManifest).getOrElse(fail("fixture must parse"))
    assertEquals(manifest.manifestVersion, "1.1.0")
    assertEquals(manifest.modelRole, ModelRole.PositionValue.id)
    assertEquals(manifest.featureSchema, "kcp-13")
    assertEquals(manifest.featureCount, 13)
    assertEquals(manifest.inputName, "input")
    assertEquals(manifest.outputName, "output")
    assertEquals(manifest.perspective, ModelManifest.MoverPerspective)
    assertEquals(manifest.calibration.temperature, 1.0)
    assertEquals(manifest.provenance.get("trained"), Some("false"))
    assertEquals(ModelManifest.validate(manifest, engineVersion), Right(()))

  test("a 1.0.0 manifest is read as a position-value model on the mover perspective"):
    val manifest = ModelManifest.load(ContractFixtures.legacyManifest).getOrElse(fail("fixture must parse"))
    assertEquals(manifest.manifestVersion, ModelManifest.LegacyVersion)
    assertEquals(manifest.modelRole, ModelRole.PositionValue.id)
    assertEquals(manifest.perspective, ModelManifest.MoverPerspective)
    assertEquals(manifest.inputName, ModelManifest.DefaultInputName)
    assertEquals(manifest.outputName, ModelManifest.DefaultOutputName)
    assertEquals(manifest.evaluationProfile, Some("standard-kcp"))
    assertEquals(ModelManifest.validate(manifest, engineVersion), Right(()))

  test("a current manifest may not omit the fields 1.0.0 never had"):
    val text = s"""{
      "manifestVersion": "1.1.0", "modelId": "m", "modelSha256": "${"b" * 64}",
      "featureSchema": "kcp-13", "featureCount": 13, "engineCompatibility": ">=0.12.0"
    }"""
    assertEquals(rejection(ModelManifest.parse(text)), "manifestVersion 1.1.0 requires field 'modelRole'")

  test("an unsupported manifest version is refused before anything else is read"):
    val text = """{"manifestVersion": "2.0.0", "modelId": "m"}"""
    assert(rejection(ModelManifest.parse(text)).startsWith("unsupported manifestVersion '2.0.0'"))

  test("a missing or mistyped field names itself"):
    assertEquals(
      rejection(ModelManifest.parse("""{"manifestVersion": "1.0.0"}""")),
      "missing required field 'modelId'"
    )
    val mistyped = """{"manifestVersion": "1.0.0", "modelId": 7}"""
    assertEquals(rejection(ModelManifest.parse(mistyped)), "field 'modelId' must be a string")
    val fractional =
      s"""{"manifestVersion": "1.0.0", "modelId": "m", "modelSha256": "${"c" * 64}",
          "featureSchema": "kcp-13", "featureCount": 12.5, "engineCompatibility": ">=0.12.0"}"""
    assertEquals(rejection(ModelManifest.parse(fractional)), "field 'featureCount' must be a whole number")

  test("malformed JSON is a manifest rejection, not an exception"):
    assert(rejection(ModelManifest.parse("{oops")).startsWith("not valid JSON"))

  test("calibration and provenance are optional, and non-string provenance values are rendered"):
    val text = s"""{
      "manifestVersion": "1.1.0", "modelId": "m", "modelSha256": "${"d" * 64}",
      "modelRole": "chance-collapse", "featureSchema": "raw-board-768-v1", "featureCount": 768,
      "perspective": "side-to-move", "engineCompatibility": ">=0.12.0",
      "calibration": {"temperature": 2.5, "brierScore": 0.21, "calibratedOn": "2026-09-18"},
      "provenance": {"epochs": 12, "shuffled": true, "run": "abc"}
    }"""
    val manifest = ModelManifest.parse(text).getOrElse(fail("must parse"))
    assertEquals(manifest.calibration.temperature, 2.5)
    assertEquals(manifest.calibration.brierScore, Some(0.21))
    assertEquals(manifest.calibration.logLoss, None)
    assertEquals(manifest.calibration.calibratedOn, Some("2026-09-18"))
    assertEquals(manifest.provenance, Map("epochs" -> "12", "shuffled" -> "true", "run" -> "abc"))
    assertEquals(ModelManifest.validate(manifest, engineVersion), Right(()))

  test("a width written as a whole float is the same width"):
    val text = s"""{
      "manifestVersion": "1.0.0", "modelId": "m", "modelSha256": "${"9" * 64}",
      "featureSchema": "kcp-13", "featureCount": 13.0, "engineCompatibility": ">=0.12.0"
    }"""
    assertEquals(ModelManifest.parse(text).map(_.featureCount), Right(13))

  test("provenance must be an object of scalars"):
    def manifestWith(provenance: String) = s"""{
      "manifestVersion": "1.0.0", "modelId": "m", "modelSha256": "${"8" * 64}",
      "featureSchema": "kcp-13", "featureCount": 13, "engineCompatibility": ">=0.12.0",
      "provenance": $provenance
    }"""
    assertEquals(
      ModelManifest.parse(manifestWith("""{"ratio": 0.25}""")).map(_.provenance),
      Right(Map("ratio" -> "0.25"))
    )
    assertEquals(
      rejection(ModelManifest.parse(manifestWith("""{"dataset": {"rows": 10}}"""))),
      "field 'provenance.dataset' must be a string, number or boolean"
    )
    assertEquals(rejection(ModelManifest.parse(manifestWith("\"none\""))), "field 'provenance' must be an object")

  test("calibration must be an object when present"):
    val text = s"""{
      "manifestVersion": "1.0.0", "modelId": "m", "modelSha256": "${"e" * 64}",
      "featureSchema": "kcp-13", "featureCount": 13, "engineCompatibility": ">=0.12.0",
      "calibration": 1.0
    }"""
    assertEquals(rejection(ModelManifest.parse(text)), "field 'calibration' must be an object")

  test("validation refuses a blank id, a malformed digest, an unknown role and an unknown schema"):
    assertEquals(
      rejection(ModelManifest.validate(valid.copy(modelId = "  "), engineVersion)),
      "modelId must not be blank"
    )
    assert(rejection(ModelManifest.validate(valid.copy(modelSha256 = "abc"), engineVersion)).contains("64 hexadecimal"))
    assert(
      rejection(ModelManifest.validate(valid.copy(modelRole = "oracle"), engineVersion)).contains("unknown modelRole")
    )
    assert(
      rejection(ModelManifest.validate(valid.copy(featureSchema = "kcp-14"), engineVersion))
        .contains("unknown featureSchema")
    )

  test("validation refuses a width that contradicts the declared schema"):
    val manifest = valid.copy(featureCount = 27)
    assertEquals(
      rejection(ModelManifest.validate(manifest, engineVersion)),
      "featureCount 27 does not match featureSchema 'kcp-13' (13 columns)"
    )

  test("validation refuses blank tensor names and an unsupported perspective"):
    assertEquals(
      rejection(ModelManifest.validate(valid.copy(inputName = " "), engineVersion)),
      "inputName must not be blank"
    )
    assertEquals(
      rejection(ModelManifest.validate(valid.copy(outputName = ""), engineVersion)),
      "outputName must not be blank"
    )
    assert(
      rejection(ModelManifest.validate(valid.copy(perspective = "white"), engineVersion))
        .contains("unsupported perspective 'white'")
    )

  test("engine compatibility is evaluated with AND semantics over space-separated comparators"):
    def check(constraint: String, version: String) =
      ModelManifest.validate(valid.copy(engineCompatibility = constraint), version).isRight
    assert(check(">=0.12.0 <1.0.0", "0.12.0"))
    assert(check(">=0.12.0 <1.0.0", "0.99.9"))
    assert(!check(">=0.12.0 <1.0.0", "1.0.0"))
    assert(!check(">=0.12.0 <1.0.0", "0.11.9"))
    assert(check("0.12.0", "0.12.0"), "a bare version means equality")
    assert(!check("0.12.0", "0.12.1"))
    assert(check("<=0.12.0 >0.4.0", "0.12.0"))
    assert(!check(">0.12.0", "0.12.0"))

  test("an out-of-range engine prints the constraint that excluded it"):
    assertEquals(
      rejection(ModelManifest.validate(valid, "0.11.0")),
      "engineCompatibility '>=0.12.0 <1.0.0' does not include engine version '0.11.0'"
    )

  test("a blank or ungrammatical constraint is refused, and so is a non-release engine version"):
    assertEquals(
      rejection(ModelManifest.validate(valid.copy(engineCompatibility = "  "), engineVersion)),
      "engineCompatibility must not be blank"
    )
    assert(
      rejection(ModelManifest.validate(valid.copy(engineCompatibility = ">=0.12"), engineVersion))
        .startsWith("invalid engineCompatibility")
    )
    assert(
      rejection(ModelManifest.validate(valid.copy(engineCompatibility = "~>0.12.0"), engineVersion))
        .startsWith("invalid engineCompatibility")
    )
    assert(rejection(ModelManifest.validate(valid, "0.12.1-SNAPSHOT")).startsWith("invalid engine version"))

  test("a legacy manifest may not carry a field its own version does not define"):
    val text = s"""{
      "manifestVersion": "1.0.0", "modelId": "m", "modelSha256": "${"7" * 64}",
      "modelRole": "chance-collapse", "featureSchema": "kcp-13", "featureCount": 13,
      "engineCompatibility": ">=0.12.0"
    }"""
    // Honouring it would make one file mean chance-collapse here and position-value to every 1.0.0-only reader.
    assertEquals(rejection(ModelManifest.parse(text)), "manifestVersion 1.0.0 does not define field 'modelRole'")
    val withPerspective = s"""{
      "manifestVersion": "1.0.0", "modelId": "m", "modelSha256": "${"7" * 64}",
      "perspective": "side-to-move", "featureSchema": "kcp-13", "featureCount": 13,
      "engineCompatibility": ">=0.12.0"
    }"""
    assertEquals(
      rejection(ModelManifest.parse(withPerspective)),
      "manifestVersion 1.0.0 does not define field 'perspective'"
    )

  test("the version grammar admits exactly what the other two implementations admit"):
    def accepts(constraint: String, version: String) =
      ModelManifest.validate(valid.copy(engineCompatibility = constraint), version).isRight
    // A ten-digit component fits in an Int and is accepted by the evaluation service's parser; rejecting it here on a
    // digit-count rule would split the grammar.
    assert(accepts(">=1000000000.0.0", "1000000000.0.0"))
    // One that does not fit is refused, as it is there.
    assert(
      rejection(ModelManifest.validate(valid, "99999999999.0.0")).startsWith("invalid engine version"),
      "an out-of-range component must be refused"
    )
    // Non-ASCII decimal digits are digits to Char.isDigit and not to the evaluation service's `\\d`; the narrower
    // reading is the shared one.
    assert(rejection(ModelManifest.validate(valid, "\u0663.0.0")).startsWith("invalid engine version"))
    assert(
      rejection(ModelManifest.validate(valid.copy(engineCompatibility = ">=\u0663.0.0"), engineVersion))
        .startsWith("invalid engineCompatibility")
    )
    // Leading zeros parse as their value on all three sides.
    assert(accepts("0012.0.0", "12.0.0"))

  test("the digest is computed over the model bytes and compared case-insensitively"):
    val manifest = ModelManifest.load(ContractFixtures.valueManifest).getOrElse(fail("fixture must parse"))
    val digest   = ModelManifest.computeSha256(ContractFixtures.valueModel).getOrElse(fail("fixture must digest"))
    assertEquals(digest, manifest.modelSha256)
    assertEquals(ModelManifest.verifyDigest(ContractFixtures.valueModel, manifest), Right(()))
    val upperCased = manifest.copy(modelSha256 = manifest.modelSha256.toUpperCase)
    assertEquals(ModelManifest.verifyDigest(ContractFixtures.valueModel, upperCased), Right(()))

  test("a digest that describes other bytes is refused"):
    val manifest = valid.copy(modelSha256 = "f" * 64)
    assert(
      rejection(ModelManifest.verifyDigest(ContractFixtures.valueModel, manifest)).startsWith("model SHA-256 mismatch")
    )

  test("an unreadable manifest or model is reported with its path"):
    val missing = ContractFixtures.valueModel.resolveSibling("does-not-exist.json")
    assert(rejection(ModelManifest.load(missing)).contains("not readable"))
    assert(rejection(ModelManifest.computeSha256(missing)).startsWith("cannot digest"))

  test("a manifest that is valid JSON but not an object is refused"):
    assertEquals(rejection(ModelManifest.parse("[1, 2]")), "manifest must be a JSON object")

  test("the digest streams files larger than one buffer"):
    val large = Files.createTempFile("model-contract", ".bin")
    try
      Files.write(large, Array.fill[Byte](200 * 1024)(7))
      val digest = ModelManifest.computeSha256(large).getOrElse(fail("must digest"))
      assertEquals(digest.length, 64)
      assertEquals(digest, ModelManifest.computeSha256(large).getOrElse(fail("must digest")))
    finally Files.deleteIfExists(large)
