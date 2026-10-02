// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import dicechess.engine.domain.PieceType
import java.io.File
import java.nio.file.{Files, Path, Paths}

object EgtbSolverMain:

  val DefaultKqEgtbFileName = "kq_vs_k.egtb"

  val AuthorizedBaseDir: Path =
    Option(System.getProperty("dicechess.egtb.authorizedDir"))
      .map(p => Paths.get(p).toAbsolutePath.normalize())
      .getOrElse(Paths.get("").toAbsolutePath.normalize())

  def validateOutputPath(outputPath: String, baseDir: Path = AuthorizedBaseDir): Path =
    val candidate = Paths.get(outputPath)
    val resolved  = baseDir.resolve(candidate).normalize()
    val realBase  = if Files.exists(baseDir) then baseDir.toRealPath() else baseDir

    if !resolved.startsWith(baseDir) && !resolved.startsWith(realBase) then
      throw new IllegalArgumentException(
        s"Refusing to write EGTB to unauthorized path '$outputPath'. Output path resolves to '$resolved', outside authorized directory '$baseDir'."
      )

    var checkDir = resolved.getParent
    while checkDir != null && !Files.exists(checkDir) do checkDir = checkDir.getParent
    if checkDir != null then
      val realExistingParent = checkDir.toRealPath()
      if !realExistingParent.startsWith(realBase) then
        throw new IllegalArgumentException(
          s"Refusing to write EGTB to unauthorized path '$outputPath'. Directory resolves via symlink to '$realExistingParent', outside authorized directory '$realBase'."
        )

    resolved

  def main(args: Array[String]): Unit =
    var endgame    = "kq-vs-k"
    var threads    = Runtime.getRuntime.availableProcessors()
    var iterations = 200
    var discount   = 0.995
    var epsilon    = 1e-4
    var outputPath = DefaultKqEgtbFileName
    var maxKw      = 64
    var force      = false

    var i = 0
    while i < args.length do
      args(i) match
        case "--endgame" if i + 1 < args.length =>
          endgame = args(i + 1)
          i += 2
        case "--threads" if i + 1 < args.length =>
          threads = args(i + 1).toInt
          i += 2
        case "--iterations" if i + 1 < args.length =>
          iterations = args(i + 1).toInt
          i += 2
        case "--discount" if i + 1 < args.length =>
          discount = args(i + 1).toDouble
          i += 2
        case "--epsilon" if i + 1 < args.length =>
          epsilon = args(i + 1).toDouble
          i += 2
        case "--output" if i + 1 < args.length =>
          outputPath = args(i + 1)
          i += 2
        case "--max-kw" if i + 1 < args.length =>
          maxKw = args(i + 1).toInt
          i += 2
        case "--force" =>
          force = true
          i += 1
        case _ =>
          i += 1

    validateOutputPath(outputPath)

    println("=" * 80)
    println("🎲♟️  Dice Chess Stochastic Endgame Tablebase (EGTB) Solver")
    println(s"Endgame      : $endgame")
    println(s"Threads      : $threads")
    println(s"Max Iter     : $iterations")
    println(s"Discount (γ) : $discount")
    println(s"Epsilon (ε)  : $epsilon")
    println(s"Output File  : $outputPath")
    println(s"Force Overwrite: $force")
    println("=" * 80)

    val config = EgtbConfig(
      discount = discount,
      epsilon = epsilon,
      maxIterations = iterations,
      threads = threads,
      maxKw = maxKw
    )

    solveEndgame(endgame, config, outputPath, epsilon, force)

  private def solveEndgame(
      endgame: String,
      config: EgtbConfig,
      outputPath: String,
      epsilon: Double,
      force: Boolean
  ): Unit =
    endgame.toLowerCase match
      case "kq-vs-k" | "kqvk" =>
        val result = KQEgtbSolver.solve(config)
        printSummaryAndSave(result, outputPath, DefaultKqEgtbFileName, PieceType.Queen, epsilon, force)

      case "kr-vs-k" | "krvk" =>
        val result = KREgtbSolver.solve(config)
        printSummaryAndSave(result, outputPath, "kr_vs_k.egtb", PieceType.Rook, epsilon, force)

      case "kb-vs-k" | "kbvk" =>
        val result = KBEgtbSolver.solve(config)
        printSummaryAndSave(result, outputPath, "kb_vs_k.egtb", PieceType.Bishop, epsilon, force)

      case "kn-vs-k" | "knvk" =>
        val result = KNEgtbSolver.solve(config)
        printSummaryAndSave(result, outputPath, "kn_vs_k.egtb", PieceType.Knight, epsilon, force)

      case "kp-vs-k" | "kpvk" =>
        val kqFile  = new File(DefaultKqEgtbFileName)
        val kqTable =
          if kqFile.exists() then EgtbTable.load(kqFile)
          else
            val res = getClass.getResourceAsStream(s"/egtb/$DefaultKqEgtbFileName")
            require(res != null, s"$DefaultKqEgtbFileName tablebase required for KPvK solver but not found")
            EgtbTable.load(res)
        val result = KPEgtbSolver.solve(kqTable, config)
        printSummaryAndSave(result, outputPath, "kp_vs_k.egtb", PieceType.Pawn, epsilon, force)

      case other =>
        System.err.println(
          s"Unsupported endgame: $other. Currently supported: kq-vs-k, kr-vs-k, kb-vs-k, kn-vs-k, kp-vs-k"
        )
        sys.exit(1)

  private def printSummaryAndSave(
      result: EgtbResult,
      outputPath: String,
      defaultName: String,
      pieceType: PieceType,
      epsilon: Double,
      force: Boolean
  ): Unit =
    println("\n" + "=" * 80)
    println("🏆 SOLVER CONVERGENCE & SUMMARY:")
    println(s"Total Iterations : ${result.iterations}")
    println(f"Final maxDelta   : ${result.maxDelta}%.6f (target ε = $epsilon)")
    println(f"Total Solve Time : ${result.elapsedMs / 1000.0}%.2fs")
    println(f"Avg White Win %% : ${result.avgWhiteValue * 100.0}%.2f%% (White to move)")
    println(f"Avg Black Win %% : ${result.avgBlackValue * 100.0}%.2f%% (Black to move)")
    println(s"Decisive Wins (W >= 0.95): ${result.whiteWinCount} / ${KQEgtbSolver.StatesPerTurn} states")
    println(s"Black Upset Risk (B < 0.60): ${result.blackUpsetCount} / ${KQEgtbSolver.StatesPerTurn} states")
    println("=" * 80)

    val targetPath    = if outputPath == DefaultKqEgtbFileName then defaultName else outputPath
    val validatedPath = validateOutputPath(targetPath)
    EgtbTable.save(
      validatedPath.toFile,
      pieceType,
      result.vWhite,
      result.vBlack,
      force = force,
      allowedDir = Some(AuthorizedBaseDir)
    )
