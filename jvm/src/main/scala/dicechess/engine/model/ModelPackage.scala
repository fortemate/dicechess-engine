// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

import ai.onnxruntime.OrtSession
import dicechess.engine.domain.{Color, GameState}

import java.nio.file.{Files, Path}

/** A validated model package: an ONNX file, the manifest that describes it, and the two things the manifest's ids
  * resolve to — the [[ModelRole]] the model may be wired into and the [[FeatureSchema]] whose rows it must be fed.
  *
  * Holding this value is the proof that the checks ran. A host that has one can wire a search hook without re-deciding
  * which extractor to use or whether the artifact fits: [[ModelPackage.extract]] is the only correct extractor for
  * these bytes, and the role is the only correct place to put them.
  *
  * Loading is deliberately cheap and session-free: it reads the manifest, digests the model file, and stops. The graph
  * itself is checked once a session exists, through [[ModelPackage.validateSession]] — the one check that needs
  * onnxruntime to open the file.
  */
final case class ModelPackage(
    modelPath: Path,
    manifest: ModelManifest,
    role: ModelRole,
    schema: FeatureSchema
):

  /** The feature extractor these bytes were trained on. */
  def extract: (GameState, Color) => Array[Float] = schema.extract

  /** Input width the session must declare — [[FeatureSchema.featureCount]], already agreed with the manifest. */
  def featureCount: Int = schema.featureCount

  /** Checks the loaded graph against the manifest's tensor contract. Call once, right after creating the session and
    * before the first inference.
    */
  def validateSession(session: OrtSession): Either[String, Unit] =
    OnnxModelContract.validate(session, manifest).left.map(error => s"$modelPath: $error")

object ModelPackage:

  /** File names inside a package directory, as the training repository's candidate builder publishes them. */
  val ModelFileName    = "model.onnx"
  val ManifestFileName = "manifest.json"

  /** Loads `<directory>/model.onnx` + `<directory>/manifest.json` — the layout a trained candidate ships in. */
  def load(
      directory: Path,
      engineVersion: String,
      expectedRole: Option[ModelRole] = None
  ): Either[String, ModelPackage] =
    loadFiles(directory.resolve(ModelFileName), directory.resolve(ManifestFileName), engineVersion, expectedRole)

  /** Loads a model and manifest that do not sit in a package directory — an arena runner pointed at two paths, or a
    * fixture pair in a test resource tree.
    *
    * `expectedRole` is what turns the role field from metadata into a gate: the caller states which hook it is about to
    * wire, and an artifact trained for a different question is refused here rather than silently scored.
    */
  def loadFiles(
      modelPath: Path,
      manifestPath: Path,
      engineVersion: String,
      expectedRole: Option[ModelRole] = None
  ): Either[String, ModelPackage] =
    for
      _        <- ensure(Files.isReadable(modelPath), s"$modelPath: model file not readable")
      manifest <- ModelManifest.load(manifestPath)
      _        <- ModelManifest.validate(manifest, engineVersion).left.map(error => s"$manifestPath: $error")
      // `validate` has already proved both ids resolve, so these two lookups are the resolution rather than a second
      // check — their error branches are unreachable by construction, and are written out anyway so that reordering
      // the validation steps cannot turn one into an exception.
      role   <- ModelRole.find(manifest.modelRole).left.map(error => s"$manifestPath: $error")
      schema <- FeatureSchema.find(manifest.featureSchema).left.map(error => s"$manifestPath: $error")
      _      <- ensureRole(manifestPath, role, expectedRole)
      _      <- ModelManifest.verifyDigest(modelPath, manifest).left.map(error => s"$modelPath: $error")
    yield ModelPackage(modelPath, manifest, role, schema)

  private def ensureRole(manifestPath: Path, role: ModelRole, expected: Option[ModelRole]): Either[String, Unit] =
    expected match
      case Some(wanted) if wanted != role =>
        Left(s"$manifestPath: modelRole '${role.id}' cannot serve as '${wanted.id}'")
      case _ => Right(())

  private def ensure(condition: Boolean, error: => String): Either[String, Unit] =
    Either.cond(condition, (), error)
