// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.docs

import java.io.File
import java.nio.file.Files
import munit.FunSuite

class BoardSvgRendererSpec extends FunSuite:

  test("renders valid SVG for standard chess position") {
    val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    val svg = BoardSvgRenderer.render(fen)

    assert(svg.startsWith("<svg"), "SVG must start with <svg")
    assert(svg.endsWith("</svg>\n"), "SVG must end with </svg>")
    assert(svg.contains("""viewBox="0 0 360 360""""), "SVG must define 360x360 viewBox")
    assert(svg.contains("""<rect width="360" height="360" fill="#f0d9b5"/>"""), "Must include light square background")

    // Must include piece definitions
    assert(svg.contains("""id="wK""""), "Must contain wK definition")
    assert(svg.contains("""id="bK""""), "Must contain bK definition")
    assert(svg.contains("""id="wP""""), "Must contain wP definition")

    // Count piece uses (starting position has 32 pieces)
    val pieceUses = "<use href=\"#".r.findAllIn(svg).length
    assertEquals(pieceUses, 32, "Initial position must place exactly 32 pieces")
  }

  test("renders minimal blocked promotion position with 3 pieces") {
    val fen = "8/6KP/8/8/8/8/1k6/8 w - - 0 1 PBB"
    val svg = BoardSvgRenderer.render(fen)

    val pieceUses = "<use href=\"#".r.findAllIn(svg).length
    assertEquals(pieceUses, 3, "Position must place exactly 3 pieces")

    assert(svg.contains("""href="#wK""""), "Must place white king")
    assert(svg.contains("""href="#wP""""), "Must place white pawn")
    assert(svg.contains("""href="#bK""""), "Must place black king")
  }

  test("handles flipped board correctly for black perspective") {
    val fen        = "8/6KP/8/8/8/8/1k6/8 b - - 0 1"
    val normalSvg  = BoardSvgRenderer.render(fen, flipped = false)
    val flippedSvg = BoardSvgRenderer.render(fen, flipped = true)

    assertNotEquals(normalSvg, flippedSvg, "Flipped SVG must differ from normal SVG")
  }

  test("saves SVG to file") {
    val tempFile = File.createTempFile("board_test", ".svg")
    try
      val fen = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
      BoardSvgRenderer.saveToFile(tempFile, fen)
      assert(tempFile.exists(), "Saved SVG file must exist")
      val content = Files.readString(tempFile.toPath)
      assert(content.contains("<svg"), "Saved file must contain SVG")
    finally tempFile.delete()
  }
