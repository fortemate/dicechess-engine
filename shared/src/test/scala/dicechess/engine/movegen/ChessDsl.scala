package dicechess.engine.movegen

import dicechess.engine.domain.*

/** Data structure representing a single Chess move generator test case.
  *
  * The dice pool is embedded in the 7th field of `fen` (e.g., `"... w KQkq - 0 1 16"` for dice `[1, 6]`) and parsed
  * automatically by [[dicechess.engine.domain.FenParser]].
  *
  * @param fen
  *   The position in Forsyth-Edwards Notation, with an optional 7th field encoding the dice pool.
  * @param expectedMoves
  *   The expected legal moves in UCI notation (e.g., `"e2e4"`, `"e7e8q"`).
  * @param title
  *   An optional short title for the test scenario.
  * @param description
  *   An optional longer description of the test scenario.
  */
case class MoveGenTestCase(
    fen: String,
    expectedMoves: List[String],
    title: Option[String] = None,
    description: Option[String] = None
) derives CanEqual

/** Custom DSL and utilities for writing elegant and compact move generator tests.
  */
object ChessDsl:

  extension (move: Move)
    /** Converts a Move into its standard algebraic notation string (e.g., "e2e4" or "e7e8q").
      */
    def toNotation: String = move.toUci

  extension (fen: String)
    /** Appends a single die value as the 7th FEN field, returning a [[FenWithDice]] builder.
      *
      * Example: `"rnbqkbnr/... w KQkq - 0 1".withDice(1)` → FEN `"rnbqkbnr/... w KQkq - 0 1 1"`
      */
    def withDice(die: Int): FenWithDice =
      FenWithDice(s"$fen $die")

    @scala.annotation.targetName("withDicePiece")
    def withDice(die: PieceType): FenWithDice =
      FenWithDice(s"$fen ${die.diceValue}")

    // 2-dice roll
    def withDice(dice: (Int, Int)): FenWithDice =
      FenWithDice(s"$fen ${dice._1}${dice._2}")

    @scala.annotation.targetName("withDicePiece2")
    def withDice(dice: (PieceType, PieceType)): FenWithDice =
      FenWithDice(s"$fen ${dice._1.diceValue}${dice._2.diceValue}")

    // 3-dice roll
    def withDice(dice: (Int, Int, Int)): FenWithDice =
      FenWithDice(s"$fen ${dice._1}${dice._2}${dice._3}")

    @scala.annotation.targetName("withDicePiece3")
    def withDice(dice: (PieceType, PieceType, PieceType)): FenWithDice =
      FenWithDice(s"$fen ${dice._1.diceValue}${dice._2.diceValue}${dice._3.diceValue}")

    // General string dice representation (e.g. "P", "PN", "brk")
    @scala.annotation.targetName("withDiceString")
    def withDice(diceStr: String): FenWithDice =
      FenWithDice(s"$fen $diceStr")

    // General list fallback
    def withDice(diceList: List[Int]): FenWithDice =
      FenWithDice(s"$fen ${diceList.mkString}")

    /** Assigns a title when the FEN already includes the dice pool in its 7th field. */
    def titled(title: String): FenWithDiceAndTitle =
      FenWithDiceAndTitle(fen, title)

    /** Assigns a description when the FEN already includes the dice pool in its 7th field. */
    def describedAs(desc: String): FenWithDiceAndDesc =
      FenWithDiceAndDesc(fen, desc)

    /** Directly specifies expected moves when the FEN already includes the dice pool in its 7th field. */
    def shouldYield(moves: String*): MoveGenTestCase =
      MoveGenTestCase(fen, moves.toList, None, None)

  /** Intermediate builder that holds an FEN string (with dice in the 7th field) before the expected moves are given. */
  case class FenWithDice(fen: String):
    /** Assigns a title to this test case.
      */
    def titled(title: String): FenWithDiceAndTitle =
      FenWithDiceAndTitle(fen, title)

    /** Assigns a description to this test case.
      */
    def describedAs(desc: String): FenWithDiceAndDesc =
      FenWithDiceAndDesc(fen, desc)

    /** Specifies the expected legal moves that the generator should produce.
      */
    def shouldYield(moves: String*): MoveGenTestCase =
      MoveGenTestCase(fen, moves.toList, None, None)

  case class FenWithDiceAndTitle(fen: String, title: String):
    /** Assigns a description to this test case.
      */
    def describedAs(desc: String): FenWithDiceAndTitleAndDesc =
      FenWithDiceAndTitleAndDesc(fen, title, desc)

    /** Specifies the expected legal moves that the generator should produce.
      */
    def shouldYield(moves: String*): MoveGenTestCase =
      MoveGenTestCase(fen, moves.toList, Some(title), None)

  case class FenWithDiceAndDesc(fen: String, desc: String):
    /** Assigns a title to this test case.
      */
    def titled(title: String): FenWithDiceAndTitleAndDesc =
      FenWithDiceAndTitleAndDesc(fen, title, desc)

    /** Specifies the expected legal moves that the generator should produce.
      */
    def shouldYield(moves: String*): MoveGenTestCase =
      MoveGenTestCase(fen, moves.toList, None, Some(desc))

  case class FenWithDiceAndTitleAndDesc(fen: String, title: String, desc: String):
    /** Specifies the expected legal moves that the generator should produce.
      */
    def shouldYield(moves: String*): MoveGenTestCase =
      MoveGenTestCase(fen, moves.toList, Some(title), Some(desc))
