// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.api

import dicechess.engine.domain.*
import dicechess.engine.movegen.{LegalMovesFilter, MoveGenerator, Perft}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** Rules-level implementations shared by the two JavaScript export roots.
  *
  * Every function here reaches `domain` and `movegen` only. That is what makes the `./rules` subpath cheap: the linker
  * places this object in a module shared by both roots, so the search package stays behind the full `.` entry and a
  * rules-only host never downloads it (ADR 009, #222).
  *
  * Nothing here may reference [[dicechess.engine.api.JsApi]] or [[dicechess.engine.EngineFacade]]: a reference from the
  * rules root to either of them would pull their whole export surface — and with it `BotRegistry` and the search
  * package — into the shared module, silently undoing the split.
  */
private[engine] object RulesOps:

  /** @see [[dicechess.engine.api.JsApi.getLegalUciMoves]] */
  def getLegalUciMoves(dfen: String): js.Array[String] =
    if Option(dfen).isEmpty then js.Array()
    else
      FenParser.parse(dfen) match
        case Left(_)      => js.Array()
        case Right(state) => LegalMovesFilter.filterMaximalMoves(state).map(_.toUci).toJSArray

  /** @see [[dicechess.engine.api.JsApi.perft]] */
  def perft(dfen: String, depth: Int): Double =
    if Option(dfen).isEmpty || depth < 0 then 0.0
    else
      FenParser.parse(dfen) match
        case Left(_)      => 0.0
        case Right(state) => Perft.countNodes(state, depth).toDouble

  /** @see [[dicechess.engine.api.JsApi.generateMoves]] */
  def generateMoves(dfen: String): js.Array[String] =
    if Option(dfen).isEmpty then js.Array()
    else
      FenParser.parse(dfen) match
        case Left(_)      => js.Array()
        case Right(state) =>
          val moves =
            if state.dicePool.isEmpty then MoveGenerator.generateAllMoves(state)
            else MoveGenerator.generateMoves(state)
          moves.map(_.toUci).toJSArray

  /** @see [[dicechess.engine.api.JsApi.getPieceFromDice]] */
  def getPieceFromDice(dice: Int): String | Null =
    PieceType.fromDice(dice).map(_.asNotation).orNull

  /** @see [[dicechess.engine.api.JsApi.applyMove]] */
  def applyMove(dfen: String, from: String, to: String, promotion: js.UndefOr[String]): js.UndefOr[String] =
    if Option(dfen).isEmpty || Option(from).isEmpty || Option(to).isEmpty then js.undefined
    else
      FenParser.parse(dfen) match
        case Right(state) =>
          (Square.fromNotation(from), Square.fromNotation(to)) match
            case (Some(fromSq), Some(toSq)) =>
              // Pseudo-legal generation without the dice constraint: the caller's move is already chosen, and the
              // dice pool only decides which piece types a turn may still move.
              val moveOpt = MoveGenerator.generateAllMoves(state).find { m =>
                m.fromSquare == fromSq && m.toSquare == toSq &&
                (!m.isPromotion || promotion.isEmpty ||
                  m.promotionPieceType.exists(_.asNotation == promotion.get))
              }
              moveOpt match
                case Some(move) =>
                  // `makeMove` empties the pool, so the dice this action leaves are put back, exactly as the turn
                  // generator chains micro-moves; castling spends the king and the rook die. An action no die allows,
                  // and any move in a position without dice, is applied as before and leaves the pool empty.
                  val survived = state.diceAfter(move)
                  val next     = state.makeMove(move)
                  FenParser.serialize(if survived.isValid then next.withDiceSlotsOf(survived) else next)
                case None => js.undefined
            case _ => js.undefined
        case Left(_) => js.undefined

  /** @see [[dicechess.engine.api.JsApi.endTurn]] */
  def endTurn(dfen: String): js.UndefOr[String] =
    if Option(dfen).isEmpty then js.undefined
    else
      FenParser.parse(dfen) match
        case Right(state) => FenParser.serialize(state.endTurn())
        case Left(_)      => js.undefined

  /** @see [[dicechess.engine.api.JsApi.canonicalKey]] */
  def canonicalKey(dfen: String): js.UndefOr[String] =
    if Option(dfen).isEmpty then js.undefined
    else FenParser.parse(dfen).toOption.map(Symmetry.canonicalKey).orUndefined
