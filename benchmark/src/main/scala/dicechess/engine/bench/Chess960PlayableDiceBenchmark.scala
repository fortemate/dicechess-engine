// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.Chess960Dfen
import dicechess.engine.search.Chess960TurnGenerator
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** Dice queries at the roll and after one action, including a roll that permits Chess960 castling. */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
class Chess960PlayableDiceBenchmark:
  @Param(Array("start42-pnb", "kiwipete-pnb", "kiwipete-krp"))
  var position: String = uninitialized

  @Param(Array("root", "after-first-move"))
  var prefix: String = uninitialized

  private var snapshot: Chess960Dfen = uninitialized
  private var played: List[String]   = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val (dfen, firstMove) = position match
      case "start42-pnb"  => (Chess960Dfen.startingPosition(42).toOption.get + " PNB", "a1b3")
      case "kiwipete-pnb" => (BenchmarkPositions.Kiwipete + " PNB", "a2a3")
      case _              => (BenchmarkPositions.Kiwipete + " KRP", "e1h1")
    snapshot = Chess960Dfen.parse(dfen).toOption.get
    played = if prefix == "root" then Nil else List(firstMove)

  @Benchmark
  def playableDice(): Option[List[Int]] = Chess960TurnGenerator.playableDice(snapshot, played)
