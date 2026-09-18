// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import java.nio.file.{Files, Path, StandardCopyOption}

/** The synthetic ONNX fixture, unpacked to a filesystem path.
  *
  * [[dicechess.engine.search.OnnxEvalSearch]] loads a model by path, and the fixture ships as a classpath resource, so
  * every ONNX benchmark needs this six-line dance in its trial setup. It lived three times over before this object —
  * enough for the duplication detector to notice, and enough that a change to the fixture's name would have to be made
  * in three places.
  *
  * No trained weights live in this repository; this is the same throwaway model the tests use, whose own compute is
  * negligible by design.
  */
object BenchmarkModelFixture:

  private val Resource = "/synthetic_test_model.onnx"

  /** Copies the fixture to a temp file that is deleted when the JVM exits.
    *
    * @param prefix
    *   names the temp file after the benchmark that asked for it, so a leftover is traceable
    */
  def unpack(prefix: String): Path =
    val modelFile = Files.createTempFile(prefix, ".onnx")
    modelFile.toFile.deleteOnExit()
    val resource = getClass.getResourceAsStream(Resource)
    try Files.copy(resource, modelFile, StandardCopyOption.REPLACE_EXISTING)
    finally resource.close()
    modelFile
