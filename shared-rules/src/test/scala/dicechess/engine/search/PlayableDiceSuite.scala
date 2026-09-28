// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.FunSuite

/** The dice a legal turn can still spend (#293).
  *
  * A client dims a die once no legal turn can use it. The answer is about the whole turn, not the next action: in the
  * start position with the dice queen, rook and knight, only a knight can move first, yet the rook can move after it.
  * Each case below was first checked against engine 0.13.0 by walking `getLegalTurnTree` with `applyMove` and reading
  * which dice each continuation left.
  */
class PlayableDiceSuite extends FunSuite:

  private val initial = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private def parse(dfen: String): GameState =
    FenParser.parse(dfen).fold(err => fail(s"Failed to parse DFEN: $err"), identity)

  /** The micro-moves `ucis` name, each generated from the position the one before leaves, dice or no dice. */
  private def movesOf(start: GameState, ucis: Seq[String]): List[Move] =
    ucis
      .foldLeft((start, List.empty[Move])) { case ((state, played), uci) =>
        val move = MoveGenerator
          .generateAllMoves(state)
          .find(_.toUci == uci)
          .getOrElse(fail(s"$uci is not a move in ${FenParser.serialize(state)}"))
        val left = state.diceAfter(move)
        val next = if left.isValid then state.makeMove(move).withDiceSlotsOf(left) else state.makeMove(move)
        (next, played :+ move)
      }
      ._2

  /** The playable dice after `ucis` as piece letters in face order: `Some("NR")` for a knight and a rook. */
  private def playable(dfen: String, ucis: String*): Option[String] =
    val start = parse(dfen)
    PlayableDice
      .of(start, movesOf(start, ucis))
      .map(_.flatMap(PieceType.fromDice).map(_.asNotation).mkString.toUpperCase)

  test("in the start position with bishop, knight and queen, only the knight die is playable") {
    // The four legal turns are the four knight moves, and after any of them the bishop and the queen are still blocked.
    assertEquals(playable(s"$initial BNQ"), Some("N"))
    assertEquals(playable(s"$initial BNQ", "b1a3"), Some(""))
  }

  test("a die whose piece cannot move first is playable when a legal turn spends it later") {
    // Only a knight can move first, but after b1a3 the rook can go a1b1: every legal turn is knight, then rook.
    assertEquals(playable(s"$initial QRN"), Some("NR"))
    assertEquals(playable(s"$initial QRN", "b1a3"), Some("R"))
    assertEquals(playable(s"$initial QRN", "b1a3", "a1b1"), Some(""))
  }

  test("a promotion spends the pawn die, and the new queen can spend the queen die in the same turn") {
    val promotion = "7k/4P3/8/8/8/8/8/4K3 w - - 0 1 PQ"
    assertEquals(playable(promotion), Some("PQ"))
    assertEquals(playable(promotion, "e7e8q"), Some("Q"))
    // Promoting to a rook would leave the queen die unused, so no legal turn begins with it.
    assertEquals(playable(promotion, "e7e8r"), None)
  }

  test("every die is playable when some legal turn spends all three") {
    assertEquals(playable(s"$initial PBQ"), Some("PBQ"))
  }

  test("a repeated face is playable as often as one legal turn spends it") {
    // Both knight dice can be spent in one turn, and the knights never free a bishop.
    assertEquals(playable(s"$initial NNB"), Some("NN"))
  }

  test("a choice between two dice keeps both playable until an action makes it") {
    // One pawn die: e2e3 or g2g3 frees the bishop, a2a3 or b2b3 frees the queen, and no pawn move frees both.
    val choice = "4k3/8/8/8/8/8/PP2P1P1/QN2KB2 w - - 0 1 PBQ"
    assertEquals(playable(choice), Some("PBQ"))
    assertEquals(playable(choice, "e2e3"), Some("B"))
    assertEquals(playable(choice, "b2b3"), Some("Q"))
  }

  test("castling spends the king die and the rook die in one action") {
    val castling = "4k3/8/8/8/8/8/8/4K2R w K - 0 1 RK"
    assertEquals(playable(castling), Some("RK"))
    assertEquals(playable(castling, "e1g1"), Some(""))
  }

  test("a king capture ends the turn, so nothing is playable after it although two dice are left") {
    // White: Ka1, Nb3, Pc2. Black: Kc5. The same position as TurnPrefixLegalitySuite (#279).
    val kingCapture = "8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP"
    assertEquals(playable(kingCapture), Some("PPN"))
    assertEquals(playable(kingCapture, "b3c5"), Some(""))
    // Asked afresh, the position after the capture would still offer both pawn dice.
    val afterCapture = "8/8/8/2N5/8/8/2P5/K7 w - - 0 1 PP"
    assertEquals(playable(afterCapture), Some("PP"))
  }

  test("an action allowed only because the next one takes the king leaves only the capturing die") {
    // c2c4 is legal only as the start of c2c4, Nb3xc5; a quiet knight move after it would end a two-dice turn.
    assertEquals(playable("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP", "c2c4"), Some("N"))
  }

  test("a roll with no legal move and a position without dice have nothing to play") {
    assertEquals(playable("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1 Q"), Some(""))
    assertEquals(playable(initial), Some(""))
  }

  test("there is no answer when the actions played do not begin a legal turn") {
    // A quiet knight move after c2c4 is legal step by step but begins no legal turn.
    assertEquals(playable("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP", "c2c4", "b3a5"), None)
    // No die allows a pawn move.
    assertEquals(playable(s"$initial BNQ", "e2e4"), None)
    // Nothing follows a complete turn.
    assertEquals(playable(s"$initial QRN", "b1a3", "a1b1", "b1a1"), None)
    // Nothing follows a king capture.
    assertEquals(playable("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP", "b3c5", "c2c3"), None)
  }

  test("moves built from their UCI notation alone match the moves the search generates") {
    // A capture, castling and a promotion, each built as UCI names it: two squares, and the piece for a promotion.
    val kingCapture = parse("8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 NPP")
    assertEquals(PlayableDice.of(kingCapture, List(Move(Square('b', 3), Square('c', 5)))), Some(Nil))
    val castling = parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1 RK")
    assertEquals(PlayableDice.of(castling, List(Move(Square('e', 1), Square('g', 1)))), Some(Nil))
    val promotion = parse("7k/4P3/8/8/8/8/8/4K3 w - - 0 1 PQ")
    val toQueen   = Move(Square('e', 7), Square('e', 8), Move.QueenPromotion)
    assertEquals(PlayableDice.of(promotion, List(toQueen)), Some(List(PieceType.Queen.diceValue)))
    // Without its piece, a promotion is not the move the search generates.
    assertEquals(PlayableDice.of(promotion, List(Move(Square('e', 7), Square('e', 8)))), None)
  }

  test("the dice come back as die faces in ascending order, whichever side is to move") {
    val black = parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1 QRN")
    assertEquals(
      PlayableDice.of(black, Nil),
      Some(List(PieceType.Knight.diceValue, PieceType.Rook.diceValue))
    )
  }
