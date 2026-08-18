package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.Dfen

/** The opening-book key contract shared between the engine (the consumer, via [[OpeningBookBot]]) and the analytics
  * exporter (the producer, `dicechess-analytics`).
  *
  * A key identifies a *position together with its dice roll* and is built as:
  * ```text
  * <piece-placement> <active-color> <castling> <en-passant> <dice>
  * ```
  *
  *   - The first four fields are the canonical normalized FEN from [[dicechess.engine.movegen.Dfen.normalizedFen]] —
  *     byte-for-byte the analytics `normalized_fen`. The half-move and full-move clocks are deliberately **omitted**:
  *     an opening book is independent of the move counters, and dropping them lets concrete positions that differ only
  *     by clock share a single book entry. The en-passant field is canonicalised (kept only when actually capturable)
  *     so positions that differ only by an uncapturable en-passant target — common when a Dice Chess turn double-pushes
  *     mid-turn — share one entry.
  *   - `<dice>` is the rolled piece letters (`p n b r q k`), sorted ascending and cased by the side to move —
  *     upper-case for White, lower-case for Black. This is identical to the analytics `dice_sorted` column. Example for
  *     White rolling bishop + pawn + rook: `BPR`.
  *
  * The value mapped to a key is the chosen continuation as a comma-separated list of long-algebraic micro-moves (e.g.
  * `"e2e4,f1c4"`, with a promotion suffix such as `"e7e8q"` where applicable). [[OpeningBookBot]] matches it against
  * the legal turn paths by move multiset, so the stored order is not significant.
  *
  * Example key: `rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - BPR`
  *
  * Any change to this format is a cross-repository breaking change and must be mirrored in the analytics exporter and
  * its round-trip test.
  */
object OpeningBook:

  /** Builds the canonical book key for `state`, or `None` when no dice have been rolled (the key is only meaningful for
    * a rolled position — a chance node has no book entry).
    *
    * @param state
    *   the current game state; `state.activeColor` cases the dice letters
    * @return
    *   `Some(key)` in the canonical format above, or `None` when `state.dicePool` is empty
    */
  def key(state: GameState): Option[String] =
    val pool = state.flags.dicePool
    if pool.isEmpty then None
    else
      // Canonical normalized FEN == analytics `normalized_fen`; clocks and the engine's dice field are dropped.
      val position = Dfen.normalizedFen(state)
      val letters  = pool.flatMap(PieceType.fromDice).map(_.asNotation).mkString
      val dice     = (if state.flags.activeColor.isWhite then letters.toUpperCase else letters).sorted
      Some(s"$position $dice")
