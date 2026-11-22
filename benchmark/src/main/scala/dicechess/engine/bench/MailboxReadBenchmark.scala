// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
class MailboxReadBenchmark:

  @Param(Array("initial", "kiwipete", "endgame"))
  var position: String         = uninitialized
  private var mailbox: Mailbox = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    mailbox = BenchmarkPositions.parse(BenchmarkPositions.AllPositions(position)).mailbox

  @Benchmark
  @OperationsPerInvocation(64)
  def readSquares(): Int =
    var index = 0
    var sum   = 0
    while index < 64 do
      sum += mailbox(Square.fromIndex(index)).pieceType.diceValue
      index += 1
    sum
