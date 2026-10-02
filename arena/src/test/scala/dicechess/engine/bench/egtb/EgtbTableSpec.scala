// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.{FenParser, PieceType, Square}
import java.io.File
import munit.FunSuite

class EgtbTableSpec extends FunSuite:

  val kqFile = new File("arena/src/main/resources/egtb/kq_vs_k.egtb")
  val krFile = new File("arena/src/main/resources/egtb/kr_vs_k.egtb")

  test("load KQvK tablebase and verify header and metadata"):
    assume(kqFile.exists(), "kq_vs_k.egtb must exist for test")
    val table = EgtbTable.load(kqFile)
    assertEquals(table.pieceType, PieceType.Queen)

  test("load KRvK tablebase and verify header and metadata"):
    assume(krFile.exists(), "kr_vs_k.egtb must exist for test")
    val table = EgtbTable.load(krFile)
    assertEquals(table.pieceType, PieceType.Rook)

  test("KQvK probes are well-formed probabilities in [0.0, 1.0]"):
    assume(kqFile.exists(), "kq_vs_k.egtb must exist for test")
    val table = EgtbTable.load(kqFile)

    // Cornered Black King: Ke1, Qd1, Kh8
    val kw = Square('e', 1).index
    val q  = Square('d', 1).index
    val kb = Square('h', 8).index

    val pWhite = table.probe(kw, kb, q, isWhiteTurn = true)
    assert(pWhite.isDefined)
    assert(pWhite.get >= 0.0 && pWhite.get <= 1.0)
    // White is winning decisively
    assert(pWhite.get >= 0.90, s"Expected White win >= 0.90, got ${pWhite.get}")

    val pBlack = table.probe(kw, kb, q, isWhiteTurn = false)
    assert(pBlack.isDefined)
    assert(pBlack.get >= 0.0 && pBlack.get <= 1.0)

  test("probeState matches probe result for standard FEN"):
    assume(kqFile.exists(), "kq_vs_k.egtb must exist for test")
    val table = EgtbTable.load(kqFile)

    val fen   = "7k/8/8/8/8/8/8/3QK3 w - - 0 1"
    val state = FenParser.parse(fen).toOption.get

    val stateProbe = table.probeState(state)
    assert(stateProbe.isDefined)

    val directProbe = table.probe(
      Square('e', 1).index,
      Square('h', 8).index,
      Square('d', 1).index,
      isWhiteTurn = true
    )
    assertEquals(stateProbe, directProbe)

  test("KRvK provides decisive winning gradient for White"):
    assume(krFile.exists(), "kr_vs_k.egtb must exist for test")
    val table = EgtbTable.load(krFile)

    // Cornered Black King: Ke1, Ra1, Kh8
    val fen   = "7k/8/8/8/8/8/8/R3K3 w - - 0 1"
    val state = FenParser.parse(fen).toOption.get

    val probe = table.probeState(state)
    assert(probe.isDefined)
    assert(probe.get >= 0.85, s"Expected KRvK win prob >= 0.85, got ${probe.get}")
