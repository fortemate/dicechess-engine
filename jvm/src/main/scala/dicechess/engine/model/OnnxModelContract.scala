// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

import ai.onnxruntime.{NodeInfo, OnnxJavaType, OrtSession, TensorInfo}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** One tensor of a loaded ONNX graph, as the runtime reports it. Separated from the checks below so the shape rules can
  * be tested without a real session — every dimension and dtype branch is reachable from a value, not from a fixture.
  *
  * @param shape
  *   dimensions as ONNX Runtime states them: a negative entry is a dynamic (symbolic) axis
  */
final case class OnnxTensorContract(name: String, dataType: OnnxJavaType, shape: Vector[Long])

/** Checks that a loaded ONNX graph is the graph its manifest promises, before a single row is scored.
  *
  * The engine feeds a session by *name* (`session.run` takes a name → tensor map) and reads the first output by
  * position, so a graph whose input is called something else fails inside the runtime with a message about a missing
  * input, and a graph whose output is a different tensor than expected is scored anyway — the observed cause of the
  * Python/JVM disagreement this contract exists to end (the repository's own older fixtures name their output
  * `variable`, which the JVM reads by index and the contract now rejects by name).
  *
  * A width mismatch is the other half: `[batch, 27]` fed 13 floats is an error, `[batch, 13]` fed a 13-column row from
  * the wrong schema is not — which is why the schema id is checked in the manifest and the width here. Both must hold.
  */
object OnnxModelContract:

  private given CanEqual[OnnxJavaType, OnnxJavaType] = CanEqual.derived

  /** Validates `session` against `manifest`'s tensor names and feature width.
    *
    * Any runtime failure while reading the graph metadata is reported as a rejection rather than propagated: a caller
    * wiring a model has exactly one decision to make — serve it or refuse it — and an exception from the middle of the
    * check is the same answer with a worse message.
    */
  def validate(session: OrtSession, manifest: ModelManifest): Either[String, Unit] =
    for
      graph <- metadata(session)
      _     <- validateTensors(graph._1, graph._2, manifest)
    yield ()

  /** Both tensor lists of a loaded graph, with any runtime failure turned into a rejection message. */
  private def metadata(session: OrtSession): Either[String, (Vector[OnnxTensorContract], Vector[OnnxTensorContract])] =
    Try((session.getInputInfo, session.getOutputInfo)).toEither.left
      .map(error => s"cannot read the ONNX graph: ${error.getMessage}")
      .flatMap: nodes =>
        for
          inputs  <- tensors(nodes._1, "input")
          outputs <- tensors(nodes._2, "output")
        yield (inputs, outputs)

  /** The shape and dtype rules, stated over metadata so they are testable without onnxruntime loading a file. */
  def validateTensors(
      inputs: Vector[OnnxTensorContract],
      outputs: Vector[OnnxTensorContract],
      manifest: ModelManifest
  ): Either[String, Unit] =
    for
      input  <- single(inputs, "input", manifest.inputName)
      output <- single(outputs, "output", manifest.outputName)
      _      <- ensure(input.dataType == OnnxJavaType.FLOAT, s"input '${input.name}' must have FLOAT dtype")
      _      <- ensure(
        isBatched(input.shape, manifest.featureCount),
        s"input '${input.name}' must have shape [batch,${manifest.featureCount}] with a dynamic batch dimension; got ${render(input.shape)}"
      )
      _ <- ensure(output.dataType == OnnxJavaType.FLOAT, s"output '${output.name}' must have FLOAT dtype")
      _ <- ensure(
        isBatched(output.shape, 1),
        s"output '${output.name}' must have shape [batch,1] with a dynamic batch dimension; got ${render(output.shape)}"
      )
    yield ()

  /** `[batch, width]` with a dynamic first axis. A fixed batch dimension is rejected even when it is 1: the search
    * scores whole chance nodes in one call, so a graph that only accepts one row per run would fail at the first batch
    * — at run time, under a deadline, instead of here.
    */
  private def isBatched(shape: Vector[Long], width: Int): Boolean =
    shape.length == 2 && shape.head < 0 && shape(1) == width.toLong

  private def tensors(
      nodes: java.util.Map[String, NodeInfo],
      kind: String
  ): Either[String, Vector[OnnxTensorContract]] =
    nodes.asScala.toVector.foldLeft[Either[String, Vector[OnnxTensorContract]]](Right(Vector.empty)) {
      case (result, (name, node)) =>
        for
          collected <- result
          tensor    <- node.getInfo match
            case info: TensorInfo => Right(OnnxTensorContract(name, info.`type`, info.getShape.toVector))
            case other            => Left(s"$kind '$name' must be a tensor; got ${other.getClass.getSimpleName}")
        yield collected :+ tensor
    }

  private def single(
      tensors: Vector[OnnxTensorContract],
      kind: String,
      expectedName: String
  ): Either[String, OnnxTensorContract] =
    tensors match
      case Vector(tensor) if tensor.name == expectedName => Right(tensor)
      case _                                             =>
        val found = tensors.map(_.name).sorted.mkString(", ")
        Left(s"ONNX model must expose exactly one $kind named '$expectedName'; found [$found]")

  private def ensure(condition: Boolean, error: => String): Either[String, Unit] =
    Either.cond(condition, (), error)

  private def render(shape: Vector[Long]): String =
    shape.map(dimension => if dimension < 0 then "batch" else dimension.toString).mkString("[", ",", "]")
