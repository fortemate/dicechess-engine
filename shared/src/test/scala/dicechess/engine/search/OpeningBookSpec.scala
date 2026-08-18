package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Pins the cross-repository opening-book key contract: [[OpeningBook.key]] must produce exactly
  * `normalized_fen + " " + dice_sorted` as emitted by the `dicechess-analytics` exporter — the four FEN fields (no
  * clocks) plus the alphabetically sorted, side-cased dice letters. If either side changes, one of these literals
  * breaks.
  */
class OpeningBookSpec extends FunSuite:

  test("key equals analytics 'normalized_fen + space + dice_sorted' (White)") {
    val state = FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 BPR").toOption.get
    assertEquals(OpeningBook.key(state), Some("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - BPR"))
  }

  test("key drops the move clocks and canonicalises the dice order") {
    // Different dice input order and non-default clocks must still yield the canonical key above.
    val state = FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 5 12 RPB").toOption.get
    assertEquals(OpeningBook.key(state), Some("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - BPR"))
  }

  test("key lower-cases the dice when Black is to move") {
    val state = FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1 bpr").toOption.get
    assertEquals(OpeningBook.key(state), Some("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - bpr"))
  }

  test("key is None when no dice have been rolled") {
    val state = FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").toOption.get
    assertEquals(OpeningBook.key(state), None)
  }

  test("key canonicalises an uncapturable en-passant target to '-'") {
    // White pushed h2-h4 (target h3) but no black pawn can take it; the key must drop the target so
    // it matches the analytics exporter's canonical normalized_fen.
    val state = FenParser.parse("rnbqkbnr/pppppppp/8/8/7P/8/PPPPPPP1/RNBQKBNR b KQkq h3 0 1 bpr").toOption.get
    assertEquals(OpeningBook.key(state), Some("rnbqkbnr/pppppppp/8/8/7P/8/PPPPPPP1/RNBQKBNR b KQkq - bpr"))
  }
