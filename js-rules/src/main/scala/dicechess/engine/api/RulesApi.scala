// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.api

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Rules-only JavaScript API, the export root of the `rules` module (npm subpath `./rules`).
  *
  * It exposes the DFEN functions a host needs to render and validate a game — legal moves, applying a move, ending a
  * turn, perft, the dice-to-piece mapping and the canonical key — and nothing that reaches `dicechess.engine.search`. A
  * host that imports this root therefore downloads the rules half of the bundle only; the bots, the opening book,
  * Monte-Carlo equity and time management stay behind the full `.` entry (`DiceChess` in
  * [[dicechess.engine.api.JsApi]]).
  *
  * The two roots share one module for the code they have in common, so an application that imports both (the play site
  * hosts `/live` next to the practice bot) still instantiates the move generator's tables once.
  *
  * The function names and their semantics are identical to their `DiceChess` counterparts, so a rules-only consumer
  * migrates by changing the import specifier alone:
  *
  * ```js
  * import { DiceChess } from '@fortemate/dicechess-engine/rules'
  * ```
  */
@JSExportTopLevel("DiceChess", "rules")
object RulesApi:

  /** Returns all legal moves as an array of UCI strings (e.g. `["e2e4", "e7e8q"]`).
    *
    * @param dfen
    *   The position in DiceChess Forsyth-Edwards Notation (including the dice pool).
    * @return
    *   An array of full UCI move strings; empty for an invalid DFEN.
    */
  @JSExport
  @JSExportTopLevel("getLegalUciMoves", "rules")
  def getLegalUciMoves(dfen: String): js.Array[String] = RulesOps.getLegalUciMoves(dfen)

  /** Counts the number of leaf nodes at a given depth for a DFEN position.
    *
    * @param dfen
    *   The position in DFEN notation.
    * @param depth
    *   Search depth (0 or greater).
    * @return
    *   Total number of leaf nodes as a Double.
    */
  @JSExport
  @JSExportTopLevel("perft", "rules")
  def perft(dfen: String, depth: Int): Double = RulesOps.perft(dfen, depth)

  /** Generates pseudo-legal moves for the active color and current dice pool.
    *
    * If no dice pool is present in the DFEN, generates all pseudo-legal moves for all piece types.
    *
    * @param dfen
    *   The position in DFEN notation.
    * @return
    *   An array of UCI move strings.
    */
  @JSExport
  @JSExportTopLevel("generateMoves", "rules")
  def generateMoves(dfen: String): js.Array[String] = RulesOps.generateMoves(dfen)

  /** Returns the piece type associated with a dice roll.
    *
    * @param dice
    *   The dice roll (1-6).
    * @return
    *   The piece notation (p, n, b, r, q, k) or null if invalid.
    */
  @JSExport
  @JSExportTopLevel("getPieceFromDice", "rules")
  def getPieceFromDice(dice: Int): String | Null = RulesOps.getPieceFromDice(dice)

  /** Applies a move to the given DFEN and returns the resulting state.
    *
    * The result keeps the dice the move did not spend; castling spends the king and the rook die. A move that no die in
    * the pool allows is refused, and a position without dice, including one whose dice are spent, accepts any
    * pseudo-legal move.
    *
    * @param dfen
    *   The starting board state in DiceChess Forsyth-Edwards Notation (DFEN).
    * @param from
    *   The algebraic notation of the starting square.
    * @param to
    *   The algebraic notation of the target square.
    * @param promotion
    *   The optional piece type to promote to (e.g. "q").
    * @return
    *   The updated DFEN string after applying the move, or `undefined` if the move is pseudo-illegal or no die allows
    *   it.
    */
  @JSExport
  @JSExportTopLevel("applyMove", "rules")
  def applyMove(dfen: String, from: String, to: String, promotion: js.UndefOr[String]): js.UndefOr[String] =
    RulesOps.applyMove(dfen, from, to, promotion)

  /** Explicitly ends the current turn, clearing the dice pool and stale en-passant targets and advancing the turn
    * markers.
    *
    * @param dfen
    *   The current board state in DiceChess Forsyth-Edwards Notation.
    * @return
    *   The updated DFEN string finalized for the next player, or `undefined` if invalid.
    */
  @JSExport
  @JSExportTopLevel("endTurn", "rules")
  def endTurn(dfen: String): js.UndefOr[String] = RulesOps.endTurn(dfen)

  /** Returns the canonical key of `dfen` — the DFEN of the position's symmetry-class representative, shared by all
    * symmetry-equivalent positions. Useful as a cache key.
    *
    * @return
    *   the canonical DFEN, or `undefined` for an invalid DFEN.
    */
  @JSExport
  @JSExportTopLevel("canonicalKey", "rules")
  def canonicalKey(dfen: String): js.UndefOr[String] = RulesOps.canonicalKey(dfen)
