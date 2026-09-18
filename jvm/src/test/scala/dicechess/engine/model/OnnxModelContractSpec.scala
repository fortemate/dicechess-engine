// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

import ai.onnxruntime.{OnnxJavaType, OrtEnvironment, OrtSession}
import munit.FunSuite

import scala.util.Using

/** The graph check, against real sessions for the two cases that matter in practice and against metadata values for the
  * shape and dtype rules — a fixture per rejected shape would be four more committed binaries proving what a `Vector`
  * proves here (#78).
  */
class OnnxModelContractSpec extends FunSuite:

  private val manifest = ModelManifest.load(ContractFixtures.valueManifest).getOrElse(fail("fixture must parse"))

  private def tensor(name: String, shape: Vector[Long], dataType: OnnxJavaType = OnnxJavaType.FLOAT) =
    OnnxTensorContract(name, dataType, shape)

  private def input(shape: Vector[Long], dataType: OnnxJavaType = OnnxJavaType.FLOAT) =
    Vector(tensor("input", shape, dataType))

  private def output(shape: Vector[Long], dataType: OnnxJavaType = OnnxJavaType.FLOAT) =
    Vector(tensor("output", shape, dataType))

  private def rejection(result: Either[String, ?]): String =
    result.swap.getOrElse(fail(s"expected a rejection, got $result"))

  private def withSession[A](modelPath: String)(body: OrtSession => A): A =
    val env = OrtEnvironment.getEnvironment
    Using.resource(env.createSession(modelPath, new OrtSession.SessionOptions()))(body)

  test("a conforming model passes with the tensor names and width its manifest declares"):
    withSession(ContractFixtures.valueModel.toString): session =>
      assertEquals(OnnxModelContract.validate(session, manifest), Right(()))

  test("a model whose output tensor is named differently is refused by name, not scored by index"):
    withSession(ContractFixtures.legacyNamedModel.toString): session =>
      val mobility = manifest.copy(featureSchema = "kcp-mobility-27-v1", featureCount = 27)
      val failure  = rejection(OnnxModelContract.validate(session, mobility))
      assertEquals(failure, "ONNX model must expose exactly one output named 'output'; found [variable]")

  test("a model of the wrong width is refused even when every name matches"):
    withSession(ContractFixtures.valueModel.toString): session =>
      val wrongWidth = manifest.copy(featureSchema = "kcp-mobility-27-v1", featureCount = 27)
      val failure    = rejection(OnnxModelContract.validate(session, wrongWidth))
      assertEquals(failure, "input 'input' must have shape [batch,27] with a dynamic batch dimension; got [batch,13]")

  test("the declared input name is what is required, so a renamed tensor pair validates"):
    val renamed = manifest.copy(inputName = "features", outputName = "win_probability")
    val tensors = Vector(tensor("features", Vector(-1L, 13L)))
    val results = Vector(tensor("win_probability", Vector(-1L, 1L)))
    assertEquals(OnnxModelContract.validateTensors(tensors, results, renamed), Right(()))
    assert(
      rejection(OnnxModelContract.validateTensors(input(Vector(-1L, 13L)), results, renamed)).contains("'features'")
    )

  test("a fixed batch dimension is refused, including a batch of one"):
    assert(
      rejection(OnnxModelContract.validateTensors(input(Vector(1L, 13L)), output(Vector(-1L, 1L)), manifest))
        .contains("got [1,13]")
    )
    assert(
      rejection(OnnxModelContract.validateTensors(input(Vector(-1L, 13L)), output(Vector(4L, 1L)), manifest))
        .contains("got [4,1]")
    )

  test("a rank other than two is refused on either side"):
    assert(
      rejection(OnnxModelContract.validateTensors(input(Vector(-1L)), output(Vector(-1L, 1L)), manifest))
        .contains("got [batch]")
    )
    assert(
      rejection(OnnxModelContract.validateTensors(input(Vector(-1L, 13L)), output(Vector(-1L, 1L, 1L)), manifest))
        .contains("got [batch,1,1]")
    )

  test("a non-FLOAT tensor is refused on either side"):
    assertEquals(
      rejection(
        OnnxModelContract
          .validateTensors(input(Vector(-1L, 13L), OnnxJavaType.DOUBLE), output(Vector(-1L, 1L)), manifest)
      ),
      "input 'input' must have FLOAT dtype"
    )
    assertEquals(
      rejection(
        OnnxModelContract
          .validateTensors(input(Vector(-1L, 13L)), output(Vector(-1L, 1L), OnnxJavaType.INT64), manifest)
      ),
      "output 'output' must have FLOAT dtype"
    )

  test("a graph with more than one input or output is refused, listing what it has"):
    val two     = Vector(tensor("input", Vector(-1L, 13L)), tensor("dice", Vector(-1L, 6L)))
    val failure = rejection(OnnxModelContract.validateTensors(two, output(Vector(-1L, 1L)), manifest))
    assertEquals(failure, "ONNX model must expose exactly one input named 'input'; found [dice, input]")
    assertEquals(
      rejection(OnnxModelContract.validateTensors(input(Vector(-1L, 13L)), Vector.empty, manifest)),
      "ONNX model must expose exactly one output named 'output'; found []"
    )
