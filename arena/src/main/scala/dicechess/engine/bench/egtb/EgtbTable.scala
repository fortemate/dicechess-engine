// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.{Color, GameState, PieceType, Square}
import java.io.{BufferedInputStream, DataInputStream, File, FileInputStream, InputStream}

/** O(1) in-memory probe table for 3-piece Dice Chess Endgame Tablebases (EGTB).
  *
  * Backed by a 16-bit fixed-point array (1 MB total memory footprint). Provides instantaneous evaluation of 3-piece
  * endgame positions with exact game-theoretic win probabilities.
  */
final class EgtbTable private (
    val pieceType: PieceType,
    private val vWhite: Array[Short],
    private val vBlack: Array[Short]
):

  /** Probes the win probability for White in [0.0, 1.0].
    *
    * @param kw
    *   White King square index in [0, 63]
    * @param kb
    *   Black King square index in [0, 63]
    * @param pieceSq
    *   Auxiliary White piece square index in [0, 63]
    * @param isWhiteTurn
    *   True if White is to move, false if Black is to move
    * @return
    *   Game-theoretic value V(s) in [0.0, 1.0] (probability of White winning)
    */
  def probe(kw: Int, kb: Int, pieceSq: Int, isWhiteTurn: Boolean): Option[Double] =
    if KQEgtbSolver.isLegal(kw, kb, pieceSq) then
      val idx      = KQEgtbSolver.stateIndex(kw, kb, pieceSq)
      val raw      = if isWhiteTurn then vWhite(idx) else vBlack(idx)
      val unsigned = raw.toInt & 0xffff
      Some(unsigned.toDouble / 65535.0)
    else None

  /** Probes a live GameState directly. Returns Some(probability) if the state matches this table's 3-piece material
    * configuration, or None if the state is not a 3-piece position of this type.
    */
  def probeState(state: GameState): Option[Double] =
    val whitePieces = state.whitePieces
    val blackPieces = state.blackPieces
    val kings       = state.kings

    val kwBit = whitePieces & kings
    val kbBit = blackPieces & kings

    if kwBit.count == 1 && kbBit.count == 1 && blackPieces.count == 1 && whitePieces.count == 2 then
      val auxPieces = whitePieces & ~kings
      if auxPieces.count == 1 then
        val kw        = java.lang.Long.numberOfTrailingZeros(kwBit.value)
        val kb        = java.lang.Long.numberOfTrailingZeros(kbBit.value)
        val auxSq     = java.lang.Long.numberOfTrailingZeros(auxPieces.value)
        val pieceOnSq = state.mailbox(Square.fromIndex(auxSq))
        if pieceOnSq.pieceType == pieceType then probe(kw, kb, auxSq, state.activeColor.isWhite)
        else None
      else None
    else None

object EgtbTable:

  def load(file: File): EgtbTable =
    load(new BufferedInputStream(new FileInputStream(file)))

  def load(inStream: InputStream): EgtbTable =
    val in = new DataInputStream(inStream)
    try
      val magic = new Array[Byte](4)
      in.readFully(magic)
      val magicStr = new String(magic, "US-ASCII")
      require(magicStr == "EGTB", s"Invalid EGTB magic header: $magicStr")

      val version = in.readByte()
      require(version == 1, s"Unsupported EGTB version: $version")

      val typeCode  = in.readByte()
      val pieceType = typeCode match
        case 0 => PieceType.Queen
        case 1 => PieceType.Rook
        case 2 => PieceType.Bishop
        case 3 => PieceType.Knight
        case 4 => PieceType.Pawn
        case _ => throw new IllegalArgumentException(s"Unknown EGTB piece type code: $typeCode")

      val boardSize = in.readShort()
      require(boardSize == 64, s"Unsupported board size: $boardSize")

      val states = KQEgtbSolver.StatesPerTurn
      val vW     = new Array[Short](states)
      val vB     = new Array[Short](states)

      var i = 0
      while i < states do
        vW(i) = in.readShort()
        i += 1

      i = 0
      while i < states do
        vB(i) = in.readShort()
        i += 1

      new EgtbTable(pieceType, vW, vB)
    finally in.close()
