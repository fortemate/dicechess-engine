package dicechess.engine.movegen

import dicechess.engine.domain.*
import munit.FunSuite

/** Pins the canonical en-passant normalization shared with `dicechess-analytics`. An en-passant target survives
  * [[Dfen.normalizedFen]] only when the side to move can actually capture it; every other case collapses to `-`, so
  * tactically-identical positions share one `normalized_fen`.
  */
class DfenSpec extends FunSuite:

  private def norm(fen: String): String =
    Dfen.normalizedFen(FenParser.parse(fen).toOption.get)

  test("non-capturable double-push drops the en-passant target") {
    // White pushed h2-h4; Black has no pawn on g4, so e.p. is impossible.
    assertEquals(
      norm("rnbqkbnr/pppppppp/8/8/7P/8/PPPPPPP1/RNBQKBNR b KQkq h3 0 1"),
      "rnbqkbnr/pppppppp/8/8/7P/8/PPPPPPP1/RNBQKBNR b KQkq -"
    )
  }

  test("en-passant target on an occupied square is dropped") {
    // The legacy "h2h4 h1h3" shape: a rook sits on h3, so the e.p. square is blocked.
    assertEquals(
      norm("rnbqkbnr/pppppppp/8/8/7P/7R/PPPPPPP1/RNBQKBN1 b Qkq h3 0 1"),
      "rnbqkbnr/pppppppp/8/8/7P/7R/PPPPPPP1/RNBQKBN1 b Qkq -"
    )
  }

  test("a genuinely capturable en-passant target is kept (Black to move)") {
    // Black pawn on d4 can take the white e4 pawn en passant via e3.
    assertEquals(
      norm("rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"),
      "rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3"
    )
  }

  test("a genuinely capturable en-passant target is kept (White to move)") {
    // White pawn on d5 can take the black e5 pawn en passant via e6.
    assertEquals(
      norm("rnbqkbnr/pppp1ppp/8/3Pp3/8/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 1"),
      "rnbqkbnr/pppp1ppp/8/3Pp3/8/8/PPP1PPPP/RNBQKBNR w KQkq e6"
    )
  }

  test("multi-target en-passant keeps only the capturable squares") {
    // White double-pushed a/c/e pawns; a lone black pawn on b4 can take a3 and c3, but not e3.
    assertEquals(
      norm("rnbqkbnr/p1pppppp/8/8/PpP1P3/8/1P1P1PPP/RNBQKBNR b KQkq a3c3e3 0 1"),
      "rnbqkbnr/p1pppppp/8/8/PpP1P3/8/1P1P1PPP/RNBQKBNR b KQkq a3c3"
    )
  }

  test("a position without an en-passant target is unchanged") {
    assertEquals(
      norm("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"),
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"
    )
  }

  test("the two paths to the same board collapse to one normalized FEN") {
    val occupiedEp = "rnbqkbnr/pppppppp/8/8/7P/7R/PPPPPPP1/RNBQKBN1 b Qkq h3 0 1"
    val clearedEp  = "rnbqkbnr/pppppppp/8/8/7P/7R/PPPPPPP1/RNBQKBN1 b Qkq - 0 1"
    assertEquals(norm(occupiedEp), norm(clearedEp))
  }

  test("normalize(String) parses then normalizes") {
    assertEquals(
      Dfen.normalize("rnbqkbnr/pppppppp/8/8/7P/8/PPPPPPP1/RNBQKBNR b KQkq h3 0 1"),
      Right("rnbqkbnr/pppppppp/8/8/7P/8/PPPPPPP1/RNBQKBNR b KQkq -")
    )
  }

  test("normalize(String) reports a parse failure") {
    assert(Dfen.normalize("not a valid fen").isLeft)
  }

  test("serialize still emits the naive en-passant target (round-trip unchanged)") {
    val fen = "rnbqkbnr/pppppppp/8/8/7P/8/PPPPPPP1/RNBQKBNR b KQkq h3 0 1"
    assertEquals(FenParser.serialize(FenParser.parse(fen).toOption.get), fen)
  }
