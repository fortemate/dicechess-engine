// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.{FenParser, PieceType, Square}
import java.io.File
import java.nio.file.{FileAlreadyExistsException, Files, Paths}
import munit.FunSuite

class EgtbTableSpec extends FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  val kqFile = new File("arena/src/main/resources/egtb/kq_vs_k.egtb")
  val krFile = new File("arena/src/main/resources/egtb/kr_vs_k.egtb")
  val kbFile = new File("arena/src/main/resources/egtb/kb_vs_k.egtb")
  val knFile = new File("arena/src/main/resources/egtb/kn_vs_k.egtb")
  val kpFile = new File("arena/src/main/resources/egtb/kp_vs_k.egtb")

  test("load KQvK tablebase and verify header and metadata"):
    assume(kqFile.exists(), "kq_vs_k.egtb must exist for test")
    val table = EgtbTable.load(kqFile)
    assertEquals(table.pieceType, PieceType.Queen)

  test("load KRvK tablebase and verify header and metadata"):
    assume(krFile.exists(), "kr_vs_k.egtb must exist for test")
    val table = EgtbTable.load(krFile)
    assertEquals(table.pieceType, PieceType.Rook)

  test("load KBvK tablebase and verify header and metadata"):
    assume(kbFile.exists(), "kb_vs_k.egtb must exist for test")
    val table = EgtbTable.load(kbFile)
    assertEquals(table.pieceType, PieceType.Bishop)

  test("load KNvK tablebase and verify header and metadata"):
    assume(knFile.exists(), "kn_vs_k.egtb must exist for test")
    val table = EgtbTable.load(knFile)
    assertEquals(table.pieceType, PieceType.Knight)

  test("load KPvK tablebase and verify header and metadata"):
    assume(kpFile.exists(), "kp_vs_k.egtb must exist for test")
    val table = EgtbTable.load(kpFile)
    assertEquals(table.pieceType, PieceType.Pawn)

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

  test("KQEgtbSolver executes 1 iteration correctly"):
    val res = KQEgtbSolver.solve(KQEgtbSolver.SolverConfig(maxIterations = 1, threads = 2, maxKw = 4))
    assertEquals(res.iterations, 1)
    assert(res.avgWhiteValue > 0.0)

  test("KREgtbSolver executes 1 iteration correctly"):
    val res = KREgtbSolver.solve(KREgtbSolver.SolverConfig(maxIterations = 1, threads = 2, maxKw = 4))
    assertEquals(res.iterations, 1)
    assert(res.avgWhiteValue > 0.0)

  test("KBEgtbSolver executes 1 iteration correctly"):
    val res = KBEgtbSolver.solve(KBEgtbSolver.SolverConfig(maxIterations = 1, threads = 2, maxKw = 4))
    assertEquals(res.iterations, 1)
    assert(res.avgWhiteValue > 0.0)

  test("KNEgtbSolver executes 1 iteration correctly"):
    val res = KNEgtbSolver.solve(KNEgtbSolver.SolverConfig(maxIterations = 1, threads = 2, maxKw = 4))
    assertEquals(res.iterations, 1)
    assert(res.avgWhiteValue > 0.0)

  test("KPEgtbSolver executes 1 iteration correctly"):
    assume(kqFile.exists(), "kq_vs_k.egtb must exist for test")
    val kqTable = EgtbTable.load(kqFile)
    val res     = KPEgtbSolver.solve(kqTable, KPEgtbSolver.SolverConfig(maxIterations = 1, threads = 2, maxKw = 4))
    assertEquals(res.iterations, 1)
    assert(res.avgWhiteValue > 0.0)

  test("EgtbSolverMain runs single iteration execution"):
    val testDir = Paths.get("target", "test-egtb")
    Files.createDirectories(testDir)
    val tmp = Files.createTempFile(testDir, "egtb_test", ".egtb").toFile
    tmp.delete()
    try
      EgtbSolverMain.main(
        Array(
          "--endgame",
          "kq-vs-k",
          "--iterations",
          "1",
          "--threads",
          "2",
          "--max-kw",
          "4",
          "--output",
          tmp.getAbsolutePath
        )
      )
      assert(tmp.length() == 1048584L)
    finally tmp.delete()

  test("EgtbTable.save rejects overwriting existing file without force"):
    val tmp = File.createTempFile("egtb_safe", ".egtb")
    try
      Files.writeString(tmp.toPath, "DO-NOT-OVERWRITE")
      intercept[FileAlreadyExistsException]:
        EgtbTable.save(
          tmp,
          PieceType.Queen,
          new Array[Float](KQEgtbSolver.StatesPerTurn),
          new Array[Float](KQEgtbSolver.StatesPerTurn),
          force = false,
          allowedDir = None
        )
      assertEquals(Files.readString(tmp.toPath), "DO-NOT-OVERWRITE")
    finally tmp.delete()

  test("EgtbTable.save allows overwriting existing file when force is true"):
    val tmp = File.createTempFile("egtb_force", ".egtb")
    try
      Files.writeString(tmp.toPath, "CAN-BE-OVERWRITTEN")
      EgtbTable.save(
        tmp,
        PieceType.Queen,
        new Array[Float](KQEgtbSolver.StatesPerTurn),
        new Array[Float](KQEgtbSolver.StatesPerTurn),
        force = true,
        allowedDir = None
      )
      assertEquals(tmp.length(), 1048584L)
    finally tmp.delete()

  test("EgtbSolverMain with --force overwrites existing file"):
    val testDir = Paths.get("target", "test-egtb")
    Files.createDirectories(testDir)
    val tmp = Files.createTempFile(testDir, "egtb_main_force", ".egtb").toFile
    try
      Files.writeString(tmp.toPath, "INITIAL-DATA")
      EgtbSolverMain.main(
        Array(
          "--endgame",
          "kq-vs-k",
          "--iterations",
          "1",
          "--threads",
          "2",
          "--max-kw",
          "4",
          "--output",
          tmp.getAbsolutePath,
          "--force"
        )
      )
      assertEquals(tmp.length(), 1048584L)
    finally tmp.delete()

  test("EgtbSolverMain rejects absolute path outside authorized directory"):
    intercept[IllegalArgumentException]:
      EgtbSolverMain.main(
        Array(
          "--endgame",
          "kq-vs-k",
          "--iterations",
          "1",
          "--threads",
          "1",
          "--max-kw",
          "1",
          "--output",
          "/tmp/dicechess-egtb-poc/nested/created.egtb"
        )
      )

  test("EgtbSolverMain rejects relative path escaping authorized directory"):
    intercept[IllegalArgumentException]:
      EgtbSolverMain.main(
        Array(
          "--endgame",
          "kq-vs-k",
          "--iterations",
          "1",
          "--threads",
          "1",
          "--max-kw",
          "1",
          "--output",
          "../../unauthorized.egtb"
        )
      )

  test("EgtbTable.save rejects path outside allowedDir"):
    val tmp = File.createTempFile("egtb_unauth", ".egtb")
    try
      intercept[IllegalArgumentException]:
        EgtbTable.save(
          tmp,
          PieceType.Queen,
          new Array[Float](KQEgtbSolver.StatesPerTurn),
          new Array[Float](KQEgtbSolver.StatesPerTurn),
          allowedDir = Some(Paths.get("arena"))
        )
    finally tmp.delete()

  test("EgtbTable.save rejects writing to symbolic link"):
    val target   = File.createTempFile("egtb_symlink_target", ".egtb")
    val linkPath = Paths.get(target.getParent, s"symlink_${System.nanoTime()}.egtb")
    try
      Files.createSymbolicLink(linkPath, target.toPath)
      intercept[IllegalArgumentException]:
        EgtbTable.save(
          linkPath.toFile,
          PieceType.Queen,
          new Array[Float](KQEgtbSolver.StatesPerTurn),
          new Array[Float](KQEgtbSolver.StatesPerTurn),
          force = true,
          allowedDir = None
        )
    finally
      Files.deleteIfExists(linkPath)
      target.delete()
