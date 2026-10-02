// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench.egtb

import java.io.File

object EgtbSolverMain:

  def main(args: Array[String]): Unit =
    var endgame    = "kq-vs-k"
    var threads    = Runtime.getRuntime.availableProcessors()
    var iterations = 200
    var discount   = 0.995
    var epsilon    = 1e-4
    var outputPath = "kq_vs_k.egtb"

    var i = 0
    while i < args.length do
      args(i) match
        case "--endgame" if i + 1 < args.length =>
          endgame = args(i + 1); i += 2
        case "--threads" if i + 1 < args.length =>
          threads = args(i + 1).toInt; i += 2
        case "--iterations" if i + 1 < args.length =>
          iterations = args(i + 1).toInt; i += 2
        case "--discount" if i + 1 < args.length =>
          discount = args(i + 1).toDouble; i += 2
        case "--epsilon" if i + 1 < args.length =>
          epsilon = args(i + 1).toDouble; i += 2
        case "--output" if i + 1 < args.length =>
          outputPath = args(i + 1); i += 2
        case _ =>
          i += 1

    println("=" * 80)
    println("🎲♟️  Dice Chess Stochastic Endgame Tablebase (EGTB) Solver")
    println(s"Endgame      : $endgame")
    println(s"Threads      : $threads")
    println(s"Max Iter     : $iterations")
    println(s"Discount (γ) : $discount")
    println(s"Epsilon (ε)  : $epsilon")
    println(s"Output File  : $outputPath")
    println("=" * 80)

    endgame.toLowerCase match
      case "kq-vs-k" | "kqvk" =>
        val config = KQEgtbSolver.SolverConfig(
          discount = discount,
          epsilon = epsilon,
          maxIterations = iterations,
          threads = threads
        )
        val result = KQEgtbSolver.solve(config)

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

        val file = new File(outputPath)
        KQEgtbSolver.saveTable(file, result.vWhite, result.vBlack)
        println(s"Successfully generated and verified EGTB table: ${file.getAbsolutePath}")

      case "kr-vs-k" | "krvk" =>
        val config = KREgtbSolver.SolverConfig(
          discount = discount,
          epsilon = epsilon,
          maxIterations = iterations,
          threads = threads
        )
        val result = KREgtbSolver.solve(config)

        println("\n" + "=" * 80)
        println("🏆 SOLVER CONVERGENCE & SUMMARY:")
        println(s"Total Iterations : ${result.iterations}")
        println(f"Final maxDelta   : ${result.maxDelta}%.6f (target ε = $epsilon)")
        println(f"Total Solve Time : ${result.elapsedMs / 1000.0}%.2fs")
        println(f"Avg White Win %% : ${result.avgWhiteValue * 100.0}%.2f%% (White to move)")
        println(f"Avg Black Win %% : ${result.avgBlackValue * 100.0}%.2f%% (Black to move)")
        println(s"Decisive Wins (W >= 0.95): ${result.whiteWinCount} / ${KREgtbSolver.StatesPerTurn} states")
        println(s"Black Upset Risk (B < 0.60): ${result.blackUpsetCount} / ${KREgtbSolver.StatesPerTurn} states")
        println("=" * 80)

        val file = new File(if outputPath == "kq_vs_k.egtb" then "kr_vs_k.egtb" else outputPath)
        KREgtbSolver.saveTable(file, result.vWhite, result.vBlack)
        println(s"Successfully generated and verified EGTB table: ${file.getAbsolutePath}")

      case other =>
        System.err.println(s"Unsupported endgame: $other. Currently supported: kq-vs-k")
        sys.exit(1)
