package dicechess.engine.movegen

import dicechess.engine.domain.*

/** Canonical DFEN normalization, shared with the `dicechess-analytics` position store.
  *
  * The position dedup key (`normalized_fen` in analytics) and the opening-book key (`OpeningBook.key`) are the first
  * four DFEN fields. Raw [[dicechess.engine.domain.FenParser.serialize]] writes an en-passant target after *every* pawn
  * double-push, even when no capture is possible. In Dice Chess a turn spans several micro-moves, so the en-passant
  * field at turn end is path-dependent: the same tactically-identical position serializes under many en-passant
  * variants (target set vs cleared), which fragments the position store and the opening-book / equity samples.
  *
  * [[normalizedFen]] removes that ambiguity with the X-FEN convention: an en-passant target is kept only when the side
  * to move can actually capture it. Whether a capture exists is a move-generation property, so this lives next to
  * [[PawnGeneration]] rather than in the pure-domain [[dicechess.engine.domain.FenParser]] (which must stay free of
  * move-generation knowledge, and whose `serialize`/`parse` round-trip must remain intact).
  *
  * The four-field result is, by contract, byte-for-byte the analytics `normalized_fen`. Any change here is a
  * cross-repository breaking change and must be mirrored in the analytics normalizer and its round-trip test.
  */
object Dfen:

  /** The capturable subset of `state.enPassant`: the en-passant target squares the side to move can actually reach with
    * a pawn capture. Empty when the naive target(s) cannot be captured — no adjacent friendly pawn, wrong rank, or
    * (defensively) an occupied target square.
    */
  def canonicalEnPassant(state: GameState): Bitboard =
    val color              = state.flags.activeColor
    val ownColor: Bitboard = if color.isWhite then state.whitePieces else state.blackPieces
    val ownPawns           = state.pawns & ownColor
    if ownPawns.isEmpty || state.enPassant.isEmpty then Bitboard.empty
    else
      // A side captures en passant onto rank 6 (White) or rank 3 (Black); targets on any other
      // rank are stale and uncapturable by the side to move. A real en-passant square is always
      // empty, so occupied squares (possible only in malformed/legacy data) are excluded too.
      val validRank = if color.isWhite then 6 else 3
      val occupied  = state.whitePieces | state.blackPieces
      var bits      = state.enPassant.value
      var targets   = Bitboard.empty
      while bits != 0L do
        val sq = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(bits))
        if sq.rank == validRank && !occupied.contains(sq) then targets = targets.add(sq)
        bits &= bits - 1
      if targets.isEmpty then Bitboard.empty
      else
        PawnGeneration.eastCaptures(ownPawns, targets, color) |
          PawnGeneration.westCaptures(ownPawns, targets, color)

  /** The first four DFEN fields with the en-passant field canonicalised — byte-for-byte the analytics `normalized_fen`.
    * Placement, active colour and castling are taken verbatim from [[dicechess.engine.domain.FenParser.serialize]] so
    * only the en-passant field can change.
    */
  def normalizedFen(state: GameState): String =
    val head = FenParser.serialize(state).split(" ").take(3).mkString(" ")
    s"$head ${renderEnPassant(canonicalEnPassant(state))}"

  /** Parses `fen` and returns its [[normalizedFen]], or `Left` with the parse error. */
  def normalize(fen: String): Either[String, String] =
    FenParser.parse(fen).map(normalizedFen)

  /** Renders an en-passant bitboard as a DFEN field: `-` when empty, otherwise the squares in ascending order, matching
    * [[dicechess.engine.domain.FenParser.serialize]].
    */
  private def renderEnPassant(ep: Bitboard): String =
    if ep.isEmpty then "-"
    else
      val sb   = new StringBuilder
      var bits = ep.value
      while bits != 0L do
        sb.append(Square.fromIndex(java.lang.Long.numberOfTrailingZeros(bits)).toNotation)
        bits &= bits - 1
      sb.toString
