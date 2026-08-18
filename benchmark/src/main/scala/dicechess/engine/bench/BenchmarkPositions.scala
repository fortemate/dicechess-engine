package dicechess.engine.bench

import dicechess.engine.domain.*

/** Standard benchmark positions covering diverse board configurations.
  *
  * Each position is chosen to stress a different aspect of the engine:
  *   - **Initial**: balanced, all pieces, many pawns
  *   - **Kiwipete**: complex middlegame with pins, en passant, castling
  *   - **Endgame**: sparse board, rook + pawns
  *   - **Castling**: both sides can castle, open files
  *   - **Promotion**: pawn about to promote
  */
object BenchmarkPositions:
  val Initial: String   = FenParser.InitialPosition
  val Kiwipete: String  = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
  val Endgame: String   = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"
  val Castling: String  = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1"
  val Promotion: String = "k7/4P3/8/8/8/8/8/4K3 w - - 0 1"

  val AllPositions: Map[String, String] = Map(
    "initial"   -> Initial,
    "kiwipete"  -> Kiwipete,
    "endgame"   -> Endgame,
    "castling"  -> Castling,
    "promotion" -> Promotion
  )

  def parse(fen: String): GameState =
    FenParser.parse(fen).getOrElse(sys.error(s"Invalid FEN: $fen"))
