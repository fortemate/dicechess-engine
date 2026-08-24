package dicechess.engine.domain

/** Board-symmetry transforms and position canonicalization for Dice Chess.
  *
  * Two positions related by a rules-preserving symmetry have identical win probability, so collapsing them to a single
  * canonical representative lets downstream consumers pool statistics (analytics) and share results across equivalent
  * positions (search transposition tables, Monte-Carlo caches).
  *
  * This object provides two exact symmetries: **color-flip** (always legal) and **horizontal mirror** (a
  * win-probability symmetry only when there are no castling rights — see below). Horizontal *shift* is intentionally
  * excluded: translation is not an isometry of the bounded 8x8 board (it changes a piece's distance to the edge, and
  * therefore its mobility), so it does **not** preserve win probability.
  *
  * ## Color-flip
  *
  * A vertical board mirror (rank `r` <-> rank `9 - r`) combined with swapping every piece's colour and the side to
  * move. Because Black's downward pawns become White's upward pawns, the result is a strategically identical position
  * with the side to move relabelled (white-to-move <-> black-to-move). It is an **involution**:
  * `colorFlip(colorFlip(s)) == s`.
  */
object Symmetry:

  /** Vertically mirrors a [[dicechess.engine.domain.Bitboard]] (rank `r` <-> rank `9 - r`).
    *
    * In LERF mapping each rank occupies one byte of the underlying `Long`, so reversing the eight bytes reverses the
    * ranks while keeping files intact.
    */
  private inline def flipVertical(bb: Bitboard): Bitboard =
    Bitboard(java.lang.Long.reverseBytes(bb.value))

  /** Returns the color-flipped position: a vertical mirror combined with a colour and side-to-move swap.
    *
    * Castling rights are swapped between colours while keeping their side (White king-side <-> Black king-side). The
    * en-passant target squares are mirrored vertically; their files — and therefore the [[GameFlags]] file mask — are
    * unchanged. Dice pool, half-move clock and full-move number are preserved, which makes the transform an exact
    * involution.
    */
  def colorFlip(state: GameState): GameState =
    val mailbox = new Array[Piece](64)
    var i       = 0
    while i < 64 do
      val src = state.mailbox(Square.fromIndex(i ^ 56))
      mailbox(i) = if src.isEmpty then Piece.Empty else Piece(src.color.opponent, src.pieceType)
      i += 1

    // Castling bits are K=0, Q=1, k=2, q=3 — swap the White (0-1) and Black (2-3) pairs, keeping king/queen side.
    val cr        = state.flags.castlingRights
    val swappedCr = ((cr & 0x3) << 2) | ((cr & 0xc) >>> 2)

    val flippedFlags = GameFlags.fromList(
      color = state.activeColor.opponent,
      castlingRights = swappedCr,
      enPassantFiles = state.flags.enPassantFiles,
      dicePool = state.flags.dicePool,
      halfMoveClock = state.flags.halfMoveClock
    )

    state.copy(
      whitePieces = flipVertical(state.blackPieces),
      blackPieces = flipVertical(state.whitePieces),
      pawns = flipVertical(state.pawns),
      knights = flipVertical(state.knights),
      bishops = flipVertical(state.bishops),
      rooks = flipVertical(state.rooks),
      queens = flipVertical(state.queens),
      kings = flipVertical(state.kings),
      mailbox = Mailbox.fromBuilder(mailbox),
      flags = flippedFlags,
      enPassant = flipVertical(state.enPassant),
      // Every position-relevant field changed; drop the cached key so zobristHash recomputes (see GameState.zobristHash).
      zobristKey = 0L
    )

  /** Horizontally mirrors a [[dicechess.engine.domain.Bitboard]] (file `f` <-> file `7 - f`).
    *
    * Reversing all 64 bits flips both rank and file; reversing the bytes afterwards restores the rank order, leaving
    * only the files mirrored.
    */
  private inline def flipHorizontal(bb: Bitboard): Bitboard =
    Bitboard(java.lang.Long.reverseBytes(java.lang.Long.reverse(bb.value)))

  /** Returns the file-mirrored position (a <-> h). A pure spatial reflection: colours and the side to move are
    * unchanged, pieces move to the mirrored file, castling rights swap king-side <-> queen-side, and the en-passant
    * files are mirrored.
    *
    * This is an **involution**, but it only preserves win probability when there are **no castling rights**: the board
    * mirror sends the king from the e-file to the d-file, which is not a legal castling configuration. [[canonical]]
    * therefore only folds the mirror when castling rights are absent.
    */
  def horizontalMirror(state: GameState): GameState =
    val mailbox = new Array[Piece](64)
    var i       = 0
    while i < 64 do
      mailbox(i) = state.mailbox(Square.fromIndex(i ^ 7)) // flip file, keep rank and colour
      i += 1

    // Swap king-side <-> queen-side within each colour: K<->Q (bits 0,1) and k<->q (bits 2,3).
    val cr        = state.flags.castlingRights
    val swappedCr = ((cr & 0x5) << 1) | ((cr & 0xa) >>> 1)
    // Mirror the 8-bit en-passant file mask: file f -> 7 - f.
    val epFiles         = state.flags.enPassantFiles
    val mirroredEpFiles = (java.lang.Integer.reverse(epFiles) >>> 24) & 0xff

    val mirroredFlags = state.flags.withCastlingRights(swappedCr).withEnPassantFiles(mirroredEpFiles)

    state.copy(
      whitePieces = flipHorizontal(state.whitePieces),
      blackPieces = flipHorizontal(state.blackPieces),
      pawns = flipHorizontal(state.pawns),
      knights = flipHorizontal(state.knights),
      bishops = flipHorizontal(state.bishops),
      rooks = flipHorizontal(state.rooks),
      queens = flipHorizontal(state.queens),
      kings = flipHorizontal(state.kings),
      mailbox = Mailbox.fromBuilder(mailbox),
      flags = mirroredFlags,
      enPassant = flipHorizontal(state.enPassant),
      // Every position-relevant field changed; drop the cached key so zobristHash recomputes (see GameState.zobristHash).
      zobristKey = 0L
    )

  /** Canonical representative of a position's symmetry class.
    *
    * First color-flips to a **white-to-move** position (so a position and its color-flip share a representative). Then,
    * **only when there are no castling rights** (where the file mirror is a valid win-probability symmetry), it also
    * folds horizontal mirror by choosing whichever of the position and its mirror has the smaller DFEN. The result is
    * deterministic and idempotent: `canonical(canonical(s)) == canonical(s)`.
    */
  def canonical(state: GameState): GameState =
    val sideCanonical = if state.activeColor.isBlack then colorFlip(state) else state
    if sideCanonical.flags.castlingRights == 0 then
      val mirrored = horizontalMirror(sideCanonical)
      if FenParser.serialize(mirrored) < FenParser.serialize(sideCanonical) then mirrored else sideCanonical
    else sideCanonical

  /** Stable canonical key for a position's symmetry class: the DFEN of [[canonical]].
    *
    * Symmetry-equivalent positions produce the same key. The key retains the half-move/full-move counters and dice pool
    * from the DFEN; callers that need a counter-independent identity (e.g. statistics dedup) should normalise those
    * fields themselves.
    */
  def canonicalKey(state: GameState): String =
    FenParser.serialize(canonical(state))
