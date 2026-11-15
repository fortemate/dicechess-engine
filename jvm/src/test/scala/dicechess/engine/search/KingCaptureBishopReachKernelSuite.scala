// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class KingCaptureBishopReachKernelSuite extends FunSuite:
  private val kernel =
    val methods = Option(KingCaptureProbability.getClass.getDeclaredMethods).getOrElse(fail("Missing KCP methods"))
    val method  = methods.iterator
      .flatMap(m => Option(m))
      .find(m => Option(m.getName).contains("canBishopReach") && m.getParameterCount == 3)
      .getOrElse(fail("Missing canBishopReach(Long, Long, Long)"))
    method.setAccessible(true)
    method

  private def bit(square: Square): Long = Bitboard.fromSquare(square).value

  private def evaluate(activeBishops: Long, allPieces: Long, validLandings: Long): Boolean =
    Option(
      kernel.invoke(
        KingCaptureProbability,
        Long.box(activeBishops),
        Long.box(allPieces),
        Long.box(validLandings)
      )
    )
      .getOrElse(fail("The bishop reach kernel returned no result"))
      .asInstanceOf[java.lang.Boolean]
      .booleanValue()

  test("zero valid landings return false even with an active bishop") {
    val bishop = bit(Square('c', 1))
    assertEquals(evaluate(bishop, bishop, 0L), false)
  }

  test("empty bishops cannot reach a valid landing") {
    assertEquals(evaluate(0L, 0L, bit(Square('e', 3))), false)
  }

  test("blocked diagonals cannot reach landings beyond the blockers") {
    val bishop   = bit(Square('c', 1))
    val blockers = bit(Square('b', 2)) | bit(Square('d', 2))
    val landings = bit(Square('a', 3)) | bit(Square('e', 3))
    assertEquals(evaluate(bishop, bishop | blockers, landings), false)
  }

  test("a clear bishop diagonal reaches its landing") {
    val bishop = bit(Square('c', 1))
    assertEquals(evaluate(bishop, bishop, bit(Square('e', 3))), true)
  }

  test("a reachable first bishop stops while another bishop remains") {
    val bishops = bit(Square('c', 1)) | bit(Square('h', 6))
    assertEquals(evaluate(bishops, bishops, bit(Square('e', 3))), true)
  }
