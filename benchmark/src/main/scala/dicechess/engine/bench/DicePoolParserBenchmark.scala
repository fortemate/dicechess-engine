package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scala.collection.mutable

import scala.compiletime.uninitialized
import scala.util.boundary, boundary.break

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
class DicePoolParserBenchmark {

  @Param(Array("-", "p", "pnb"))
  var dicePool: String = uninitialized

  @Benchmark
  def parseOriginal(): List[Int] = boundary {
    val poolField = dicePool
    if poolField == "-" then Nil
    else {
      val list = mutable.ListBuffer.empty[Int]
      var idx  = 0
      while idx < poolField.length do {
        val char  = poolField.charAt(idx).toLower
        val digit = char match {
          case 'p' => 1
          case 'n' => 2
          case 'b' => 3
          case 'r' => 4
          case 'q' => 5
          case 'k' => 6
          case _   => 0
        }
        if digit > 0 then {
          list += digit
        } else {
          break(Nil) // Benchmark error path not tested
        }
        idx += 1
      }
      list.toList.sorted
    }
  }

  @Benchmark
  def parseNew(): List[Int] = boundary {
    val poolField = dicePool
    if poolField == "-" then Nil
    else {
      var list: List[Int] = Nil
      var idx             = 0
      val len             = poolField.length

      while idx < len do {
        val c     = poolField.charAt(idx)
        val digit = c match {
          case 'p' | 'P' => 1
          case 'n' | 'N' => 2
          case 'b' | 'B' => 3
          case 'r' | 'R' => 4
          case 'q' | 'Q' => 5
          case 'k' | 'K' => 6
          case _         => break(Nil) // Benchmark error path not tested
        }
        list = digit :: list
        idx += 1
      }
      list.sorted
    }
  }
}
