package dicechess.engine

import scala.scalajs.js.annotation.*
import scala.scalajs.js
import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import scala.util.Random

/** The `EngineFacade` provides a JavaScript-friendly API to interact with the Dice Chess Scala engine.
  *
  * It exposes methods to generate bot moves, query piece types, fetch legal moves filtered by dice, and apply moves
  * directly to FEN strings.
  */
@JSExportTopLevel("EngineFacade")
object EngineFacade {

  /** Computes a bot move for the given DFEN.
    *
    * @param dfen
    *   The current board state in DiceChess FEN notation (includes the dice pool).
    * @param seed
    *   Optional random seed for deterministic move selection.
    * @return
    *   A dictionary containing `from` and `to` square notations, and an optional `promotion` piece, or `undefined` if
    *   no legal moves exist.
    * @example
    *   ```scala
    *   val move = EngineFacade.getBotMove("rnbqkbnr/... w - - 0 1 r", 12345)
    *   // Returns js.Dictionary("from" -> "e2", "to" -> "e4", ...)
    *   ```
    */
  @JSExport
  @JSExportTopLevel("getBotMove")
  def getBotMove(
      dfen: String,
      seed: js.UndefOr[Int] = js.undefined
  ): js.UndefOr[js.Dictionary[String]] =
    if Option(dfen).isEmpty then js.undefined
    else
      FenParser.parse(dfen) match {
        case Right(state) =>
          val moves = MoveGenerator.generateMoves(state)
          if moves.isEmpty then {
            js.undefined
          } else {
            // The user specifically requested: "Always beat the king if it possible in legal moves"
            val kingCapture = moves.find(m => state.mailbox.get(m.toSquare).exists(_.pieceType == PieceType.King))

            val chosenMove = kingCapture.getOrElse {
              val rng = seed.toOption.map(new Random(_)).getOrElse(Random)
              moves(rng.nextInt(moves.length))
            }

            val dict = js.Dictionary[String](
              "from" -> chosenMove.fromSquare.toNotation,
              "to"   -> chosenMove.toSquare.toNotation
            )

            // Chessground promotion format is "q", "r", "b", "n"
            chosenMove.promotionPieceType.foreach(pt => dict.put("promotion", pt.asNotation))

            dict
          }
        case Left(_) =>
          js.undefined
      }

  /** Retrieves the dice value (1-6) of the piece at the specified square.
    *
    * @param dfen
    *   The current board state in DiceChess FEN notation.
    * @param square
    *   The algebraic notation of the square (e.g. "e2").
    * @return
    *   The integer dice value corresponding to the piece type (1=Pawn..6=King), or `undefined` if the square is empty
    *   or invalid.
    * @example
    *   ```scala
    *   val pt = EngineFacade.getPieceTypeAt(dfen, "e2") // returns 1 for a pawn
    *   ```
    */
  @JSExport
  @JSExportTopLevel("getPieceTypeAt")
  def getPieceTypeAt(dfen: String, square: String): js.UndefOr[Int] =
    if Option(dfen).isEmpty || Option(square).isEmpty then js.undefined
    else
      FenParser.parse(dfen) match {
        case Right(state) =>
          Square.fromNotation(square) match {
            case Some(sq) =>
              state.mailbox
                .get(sq)
                .map(_.pieceType.diceValue)
                .fold(js.undefined)(v => v)
            case None => js.undefined
          }
        case Left(_) => js.undefined
      }

  /** Applies a move to the given DFEN and returns the resulting state.
    *
    * @param dfen
    *   The starting board state in DiceChess FEN notation.
    * @param from
    *   The algebraic notation of the starting square.
    * @param to
    *   The algebraic notation of the target square.
    * @param promotion
    *   The optional piece type to promote to (e.g. "q").
    * @return
    *   The updated DFEN string after applying the move, or `undefined` if the move is pseudo-illegal.
    * @example
    *   ```scala
    *   val newDfen = EngineFacade.applyMove(dfen, "e2", "e4", js.undefined)
    *   ```
    */
  @JSExport
  @JSExportTopLevel("applyMoveFacade")
  def applyMove(dfen: String, from: String, to: String, promotion: js.UndefOr[String]): js.UndefOr[String] =
    if Option(dfen).isEmpty || Option(from).isEmpty || Option(to).isEmpty then js.undefined
    else
      FenParser.parse(dfen) match {
        case Right(state) =>
          (Square.fromNotation(from), Square.fromNotation(to)) match {
            case (Some(fromSq), Some(toSq)) =>
              // Generate all pseudo-legal moves for standard generation (no dice roll constraint to find the human's move)
              val moves = MoveGenerator.generateAllMoves(state)

              val moveOpt = moves.find { m =>
                m.fromSquare == fromSq && m.toSquare == toSq &&
                (!m.isPromotion || promotion.isEmpty ||
                  m.promotionPieceType.exists(_.asNotation == promotion.get))
              }

              moveOpt match {
                case Some(move) =>
                  val newState = state.makeMove(move)
                  FenParser.serialize(newState)
                case None =>
                  js.undefined
              }
            case _ => js.undefined
          }
        case Left(_) =>
          js.undefined
      }

  /** Explicitly ends the current turn to mark a clear boundary between players in a multi-micro-move sequence.
    *
    * Frontend consumers should call this when a player has exhausted their dice or finished their sequence. This
    * function guarantees the game state is correctly finalized for the next player by cleaning up stale en-passant
    * targets from the previous turn, clearing the dice pool, and advancing the turn markers (color toggle, move
    * counts). It serves as the single entrypoint for advancing the DFEN state between player turns.
    *
    * @param dfen
    *   The current board state in DiceChess FEN notation.
    * @return
    *   The updated DFEN string finalized for the next player, or `undefined` if invalid.
    */
  @JSExport
  @JSExportTopLevel("endTurnFacade")
  def endTurn(dfen: String): js.UndefOr[String] =
    if Option(dfen).isEmpty then js.undefined
    else
      FenParser.parse(dfen) match {
        case Right(state) => FenParser.serialize(state.endTurn())
        case Left(_)      => js.undefined
      }
}
