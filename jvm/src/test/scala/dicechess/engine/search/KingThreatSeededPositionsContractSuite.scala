// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import munit.FunSuite

class KingThreatSeededPositionsContractSuite extends FunSuite:
  private val baselineSha256 = "e769a4e94ddf9c717dd402d131d5f62b2bfadb472a17418ddf8372548f48b8f2"

  private def generator(suite: KingThreatSuite): Method =
    val methods = Option(suite.getClass.getDeclaredMethods).getOrElse(fail("Missing declared methods"))
    val matches = methods.filter { method =>
      Option(method.getName).contains("generateSeededPositions") && method.getParameterCount == 1 &&
      method.getParameterTypes.headOption.flatMap(paramType => Option(paramType.getName)).contains("int")
    }
    assertEquals(matches.length, 1)
    val method = matches.head
    method.setAccessible(true)
    method

  private def positions(suite: KingThreatSuite, count: Int): List[(GameState, Square)] =
    generator(suite).invoke(suite, Int.box(count)).asInstanceOf[List[(GameState, Square)]]

  private def canonical(state: GameState, target: Square): String =
    val mailbox = (0 until 64)
      .map { index =>
        val piece = state.mailbox(Square.fromIndex(index))
        if piece.isEmpty then 0 else (piece.color.value << 3) | piece.pieceType.diceValue
      }
      .mkString(",")
    List(
      FenParser.serialize(state),
      target.index.toString,
      state.whitePieces.value.toString,
      state.blackPieces.value.toString,
      state.pawns.value.toString,
      state.knights.value.toString,
      state.bishops.value.toString,
      state.rooks.value.toString,
      state.queens.value.toString,
      state.kings.value.toString,
      mailbox,
      state.flags.value.toString,
      state.enPassant.value.toString,
      state.halfMoveClock.toString,
      state.fullMoveNumber.toString,
      state.zobristKey.toString,
      state.zobristHash.toString
    ).mkString("\t")

  private def canonicalRows(suite: KingThreatSuite, count: Int): List[String] =
    positions(suite, count).map { case (state, target) => canonical(state, target) }

  private def digest(lines: List[String]): String =
    val text = lines.map(_ + "\n").mkString
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))

  test("negative, zero, and short requests preserve count and prefix behavior") {
    val suite = new KingThreatSuite
    assertEquals(positions(suite, -2), Nil)
    assertEquals(positions(suite, 0), Nil)
    val all = canonicalRows(suite, 200)
    assertEquals(all.size, 200)
    for count <- List(1, 60, 61) do assertEquals(canonicalRows(suite, count), all.take(count), s"count=$count")
  }

  test("the complete seeded corpus matches its frozen canonical fingerprint") {
    val rows = canonicalRows(new KingThreatSuite, 200)
    assertEquals(rows.size, 200)
    assertEquals(digest(rows), baselineSha256)
  }

  test("the extended seeded corpus preserves outcomes beyond the 200-position oracle prefix") {
    val rows = canonicalRows(new KingThreatSuite, 1000)
    assertEquals(rows.size, 1000)
    assertEquals(digest(rows), "94861f69b10f857da7b9f5fe03743218ad4517a94af8b2111c477d527066dee3")
  }

  test("each invocation resets the seed, on both reused and new suite instances") {
    val suite = new KingThreatSuite
    val first = canonicalRows(suite, 200)
    assertEquals(canonicalRows(suite, 200), first)
    assertEquals(canonicalRows(new KingThreatSuite, 200), first)
  }

  test("targets are enemy kings and active color alternates within each generated game") {
    val rows = positions(new KingThreatSuite, 200)
    rows.foreach { case (state, target) =>
      val targetPiece = state.mailbox(target)
      assertEquals(targetPiece.pieceType, PieceType.King)
      assertEquals(targetPiece.color, state.activeColor.opponent)
      val enemyKings =
        if state.activeColor.isWhite then state.blackPieces & state.kings else state.whitePieces & state.kings
      assert(enemyKings.contains(target))
    }
    val initial = canonical(rows.head._1, rows.head._2)
    rows.sliding(2).foreach {
      case List(_, current) if canonical(current._1, current._2) == initial =>
        assertEquals(current._1.activeColor, Color.White, "a restarted game begins with White")
      case List(previous, current) =>
        assertEquals(current._1.activeColor, previous._1.activeColor.opponent)
      case _ => ()
    }
  }
