package dicechess.engine.search

import dicechess.engine.domain.*

/** The raw board as 12 binary planes of 64 squares — no hand-crafted features at all.
  *
  * This is the input contract of the private training pipeline's raw-board value net, whose premise is that a small MLP
  * over the bare board has capacity a 9-feature GBDT lacks. Where [[OnnxFeatures]] / [[RichFeatures]] / [[KcpFeatures]]
  * hand the model a summary of the position, this hands it the position.
  *
  * Layout, which the Python side (`board.py::encode_fen`) must match bit for bit:
  *   - planes `0..5` are the '''mover's''' Pawn, Knight, Bishop, Rook, Queen, King, in
  *     [[dicechess.engine.domain.PieceType]] order;
  *   - planes `6..11` are the opponent's, same order;
  *   - within a plane the index is the [[dicechess.engine.domain.Square]] index seen from the mover (`0` = the mover's
  *     own a1 corner), so for Black the ranks are mirrored (`rank 1 <-> rank 8`) while files are left alone;
  *   - a square holding a piece is `1.0f`, everything else `0.0f`.
  *
  * Both halves of that convention — own-pieces-first and rank mirroring — make the encoding mover-canonical: side to
  * move stops being an input dimension, exactly as the `own minus opponent` convention does for the material features.
  * The starting array is symmetric under this transform, so both colors encode it identically.
  *
  * Unlike [[RichFeatures]] this needs '''no move generation'''. That does '''not''' make it cheaper, which is worth
  * stating plainly because the opposite was assumed when this was written: extraction gets cheaper, but the model
  * behind it does not. Measured on the one-ply arena over the same 400/1600-game harness, a raw-board net costs ~0.195
  * s/game against ~0.0375 s/game for a rich(9) GBDT — '''~5x more per evaluation''', because a 768-wide input through a
  * 256x32 MLP outweighs the two `generateAllMoves` passes it saves. A 2-ply run at `candidateLimit = 24` corroborates
  * it: rich finishes 400 games in ~25 minutes, this did not finish in two hours.
  *
  * So the reason to use this set is '''strength per evaluation''', not throughput: at one ply it scores 57.3% against
  * the `aggressive` baseline where rich(9) scores 48.0%, while costing 1/253 of the `kcp` teacher's 60.8%. Anything
  * that budgets leaf evaluations — a deeper search, or a bot under a clock — has to weigh that 5x.
  *
  * @note
  *   Dice-free by construction, like every other feature set here: it scores an afterstate whose next roll is unknown.
  */
object RawBoardFeatures:

  /** Piece planes: 6 piece types for the mover, then 6 for the opponent. */
  val PlaneCount = 12

  /** Input width: [[PlaneCount]] planes of 64 squares. */
  val Width: Int = PlaneCount * 64

  /** Plane names in [[extract]]'s order, `own_p … opp_k`.
    *
    * Kept for the same reason [[RichFeatures.columnNames]] exists — it is the train/serve layout contract, and a CSV
    * enrichment over 768 columns would use it — even though this set is meant to be computed at inference time rather
    * than written to a training CSV (768 columns × millions of rows is far larger than the FEN it is derived from).
    */
  val columnNames: List[String] =
    for
      side      <- List("own", "opp")
      pieceType <- List("p", "n", "b", "r", "q", "k")
      square    <- 0 until 64
    yield s"${side}_${pieceType}_$square"

  /** The 768-long plane vector for `state` from `color`'s perspective (see the layout in the class doc).
    *
    * Reads the type and color bitboards rather than scanning all 64 mailbox squares: this runs once per candidate state
    * inside [[OnnxEvalSearch.onnxEvalBatch]], so it walks only the ~32 occupied squares and skips a `Square.fromIndex`
    * bounds check per square. The output array is the one allocation left.
    */
  def extract(state: GameState, color: Color): Array[Float] =
    val planes = new Array[Float](Width)
    val mirror = color.isBlack
    val own    = if color.isWhite then state.whitePieces else state.blackPieces
    val opp    = if color.isWhite then state.blackPieces else state.whitePieces

    var pieceType = 0
    while pieceType < 6 do
      val typeBoard = pieceType match
        case 0 => state.pawns
        case 1 => state.knights
        case 2 => state.bishops
        case 3 => state.rooks
        case 4 => state.queens
        case _ => state.kings
      fillPlane(planes, typeBoard & own, pieceType * 64, mirror)
      fillPlane(planes, typeBoard & opp, (6 + pieceType) * 64, mirror)
      pieceType += 1
    planes

  /** Sets `planes(offset + squareFromMover)` for every square in `board`, consuming one set bit per iteration. */
  private def fillPlane(planes: Array[Float], board: Bitboard, offset: Int, mirror: Boolean): Unit =
    var bits = board.value
    while bits != 0L do
      val square = java.lang.Long.numberOfTrailingZeros(bits)
      // Mirror ranks for Black so plane index 0 is always the mover's own a1 corner; files are untouched.
      val index = if mirror then ((7 - (square >> 3)) << 3) | (square & 7) else square
      planes(offset + index) = 1.0f
      bits &= bits - 1L
