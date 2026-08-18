package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.util.boundary
import boundary.break

@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class CastlingParserBenchmark:

  @Param(Array("-", "KQ", "KQkq", "kq"))
  var castling: String = uninitialized

  @Benchmark
  def parseOriginal(): Either[String, Int] = {
    var castlingInt = 0
    if castling.contains('K') then castlingInt |= 1
    if castling.contains('Q') then castlingInt |= 2
    if castling.contains('k') then castlingInt |= 4
    if castling.contains('q') then castlingInt |= 8
    // The original didn't even do validation, so we just return Right
    Right(castlingInt)
  }

  private inline def parseCastlingNew(cStr: String)(using boundary.Label[Either[String, Int]]): Int = {
    val len = cStr.length
    if len < 1 || len > 4 then break(Left(s"Invalid castling field length: $len"))

    if cStr == "-" then 0
    else {
      var castlingInt = 0
      cStr.foreach {
        case 'K' => castlingInt |= 1
        case 'Q' => castlingInt |= 2
        case 'k' => castlingInt |= 4
        case 'q' => castlingInt |= 8
        case c   => break(Left(s"Invalid castling character '$c'"))
      }
      castlingInt
    }
  }

  @Benchmark
  def parseNew(): Either[String, Int] = boundary {
    Right(parseCastlingNew(castling))
  }
