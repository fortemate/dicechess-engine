package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.util.boundary
import boundary.break

import dicechess.engine.domain.*

@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class EnPassantParserBenchmark:

  @Param(Array("-", "e3", "a6", "e3g3"))
  var enPassant: String = uninitialized

  @Benchmark
  def parseOriginal(): Either[String, (Int, Bitboard)] = boundary {
    var epFiles     = 0
    var enPassantBb = Bitboard.empty
    if enPassant != "-" then {
      var idx = 0
      while idx < enPassant.length do {
        if idx + 2 <= enPassant.length then {
          val notation = enPassant.substring(idx, idx + 2)
          Square.fromNotation(notation) match {
            case Some(sq) =>
              epFiles |= (1 << (sq.file - 'a'))
              enPassantBb = enPassantBb.add(sq)
            case None => break(Left(s"Invalid en-passant notation '$notation'"))
          }
        } else {
          break(Left(s"Invalid en-passant field '$enPassant'"))
        }
        idx += 2
      }
    }
    Right((epFiles, enPassantBb))
  }

  private inline def parseEnPassantNew(
      enPassantField: String
  )(using boundary.Label[Either[String, (Int, Bitboard)]]): (Int, Bitboard) =
    if enPassantField == "-" then (0, Bitboard.empty)
    else {
      val len = enPassantField.length
      if len == 0 || len % 2 != 0 then break(Left(s"Invalid en-passant field '$enPassantField'"))

      var epFiles     = 0
      var enPassantBb = Bitboard.empty
      var idx         = 0
      while idx < len do {
        val fileChar = enPassantField.charAt(idx)
        val rankChar = enPassantField.charAt(idx + 1)

        if fileChar < 'a' || fileChar > 'h' || rankChar < '1' || rankChar > '8' then
          break(Left(s"Invalid en-passant notation '$fileChar$rankChar'"))

        val sq = Square.fromIndex((rankChar - '1') * 8 + (fileChar - 'a'))
        if enPassantBb.contains(sq) then break(Left(s"Duplicate en-passant square '$fileChar$rankChar'"))

        epFiles |= (1 << (fileChar - 'a'))
        enPassantBb = enPassantBb.add(sq)
        idx += 2
      }
      (epFiles, enPassantBb)
    }

  @Benchmark
  def parseNew(): Either[String, (Int, Bitboard)] = boundary {
    Right(parseEnPassantNew(enPassant))
  }
