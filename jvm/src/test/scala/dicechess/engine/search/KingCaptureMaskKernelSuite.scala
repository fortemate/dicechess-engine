// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import munit.FunSuite

/** Exhaustive primitive-kernel characterization, independent of KCP geometry and callers. */
class KingCaptureMaskKernelSuite extends FunSuite:

  private val sourcePatterns = List(
    1L,
    -1L,
    Long.MinValue,
    Long.MaxValue,
    1L << 31,
    1L << 32,
    0x55aa55aa55aa55aaL
  )

  private lazy val computeMask =
    val methods = KingCaptureProbability.getClass.getDeclaredMethods.filter(_.getName == "computeCaptureMask")
    assertEquals(methods.length, 1, "expected one private computeCaptureMask method")
    val method = methods.head
    assertEquals(method.getParameterTypes.toList, List.fill(6)(java.lang.Long.TYPE))
    method.setAccessible(true)
    method

  private def invokeKernel(inputs: Array[Long]): Int =
    val args = inputs.map(value => java.lang.Long.valueOf(value).asInstanceOf[Object])
    computeMask.invoke(KingCaptureProbability, args*).asInstanceOf[java.lang.Integer].intValue

  test("computeCaptureMask returns each six-bit argument mask for deterministic Long patterns") {
    var calls = 0
    for pattern <- sourcePatterns do
      for expectedMask <- 0 until 64 do
        val inputs = Array.tabulate(6) { field =>
          if (expectedMask & (1 << field)) != 0 then java.lang.Long.rotateLeft(pattern, field) else 0L
        }
        assertEquals(invokeKernel(inputs), expectedMask, s"pattern=$pattern mask=$expectedMask")
        calls += 1
    assertEquals(calls, 448)
  }
