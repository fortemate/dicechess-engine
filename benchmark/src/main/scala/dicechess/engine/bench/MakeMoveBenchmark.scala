// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import dicechess.engine.domain.*
import dicechess.engine.search.TurnGenerator
import org.openjdk.jmh.infra.Blackhole

import scala.compiletime.uninitialized

@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Thread)
class MakeMoveBenchmark:

  var initialState: GameState   = uninitialized
  var quietMove: Move           = uninitialized
  var doublePawnMove: Move      = uninitialized
  var quietMicroMove: MicroMove = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    initialState = FenParser.parse(FenParser.InitialPosition).toOption.get
    quietMove = Move(Square('g', 1), Square('f', 3), Move.QuietMove)
    doublePawnMove = Move(Square('e', 2), Square('e', 4), Move.DoublePawnPush)
    quietMicroMove = MicroMove(Square('g', 1), Square('f', 3))

  @Benchmark
  def makeMoveQuiet(): GameState =
    initialState.makeMove(quietMove)

  @Benchmark
  def makeMoveDoublePawn(): GameState =
    initialState.makeMove(doublePawnMove)

  @Benchmark
  def makeMicroMoveQuiet(): GameState =
    initialState.makeMove(quietMicroMove)

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  def endTurn(input: EndTurnInput, work: EndTurnWork): GameState =
    work.record(1L, 0L)
    input.state.endTurn()

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  def resultingTurnStates(input: TurnFinalizationInput, work: EndTurnWork, blackhole: Blackhole): Int =
    var normal   = 0L
    var terminal = 0L
    val visited  = TurnGenerator.forEachLegalTurnState(input.state) { (_, _, result, capture) =>
      if capture then terminal += 1 else normal += 1
      blackhole.consume(result)
      true
    }
    work.record(normal, terminal)
    visited

/** Isolated cleanup/finalization, including the common dice-empty state and the public non-empty-dice contract. */
@State(Scope.Thread)
class EndTurnInput:
  @Param(Array("white", "black"))
  var color: String = uninitialized
  @Param(Array("none", "stale", "mixed"))
  var targets: String = uninitialized
  @Param(Array("empty", "rolled"))
  var dice: String     = uninitialized
  var state: GameState = uninitialized
  @Setup(Level.Trial)
  def setup(): Unit =
    require(dice == "empty" || dice == "rolled", s"Unsupported dice mode: $dice")
    state = EndTurnFixtures.state(color, targets, if dice == "empty" then Nil else List(1, 4, 6))
    val actual   = state.endTurn()
    val expected = EndTurnFixtures.reference(state)
    require(actual == expected && actual.zobristHash == Zobrist.computeKey(expected))

/** Result construction has one endTurn per normal path and no endTurn for a terminal path. */
@State(Scope.Thread)
class TurnFinalizationInput:
  @Param(Array("normal", "king-capture"))
  var fixture: String = uninitialized
  @Param(Array("white", "black"))
  var color: String = uninitialized
  @Param(Array("none", "stale", "mixed"))
  var targets: String  = uninitialized
  var state: GameState = uninitialized
  @Setup(Level.Trial)
  def setup(): Unit =
    require(fixture == "normal" || fixture == "king-capture", s"Unsupported fixture: $fixture")
    state = EndTurnFixtures.state(color, targets, List(1, 1, 6), kingCapture = fixture == "king-capture")
    val expected = TurnGenerator.generateAllLegalTurnPaths(state)
    var index    = 0
    var normal   = 0
    var terminal = 0
    TurnGenerator.forEachLegalTurnState(state) { (moves, length, result, capture) =>
      if capture then terminal += 1 else normal += 1
      val path = moves.take(length).toList
      require(path == expected(index))
      val raw       = path.foldLeft(state)((position, move) => position.makeMove(move))
      val reference = if capture then raw else EndTurnFixtures.reference(raw)
      require(result == reference && result.zobristHash == Zobrist.computeKey(reference))
      index += 1
      true
    }
    require(index == expected.length && index > 0)
    require(normal > 0, "every fixture must exercise normal turn finalization")
    require(if fixture == "king-capture" then terminal > 0 else terminal == 0)

private object EndTurnFixtures:
  def state(color: String, targets: String, dice: List[Int], kingCapture: Boolean = false): GameState =
    require(color == "white" || color == "black", s"Unsupported color: $color")
    require(Set("none", "stale", "mixed").contains(targets), s"Unsupported targets: $targets")
    val fen = if kingCapture then "r6r/8/8/3pP3/3Pp3/8/4k3/R3K2R w KQkq - 37 12"
    else "r3k2r/8/8/3pP3/3Pp3/8/8/R3K2R w KQkq - 37 12"
    val base      = BenchmarkPositions.parse(fen)
    val mover     = if color == "white" then Color.White else Color.Black
    val stale     = Square('d', if mover.isWhite then 6 else 3)
    val surviving = Square('d', if mover.isWhite then 3 else 6)
    val ep        = targets match
      case "stale" => Bitboard.fromSquare(stale)
      case "mixed" => Bitboard.fromSquare(stale).add(surviving).add(Square('a', 3)).add(Square('h', 6))
      case _       => Bitboard.empty
    val raw = base.copy(
      flags = base.flags.withActiveColor(mover).withDicePool(dice).withEnPassantFiles(0xa5),
      enPassant = ep,
      zobristKey = 0L
    )
    raw.copy(zobristKey = Zobrist.computeKey(raw))

  def reference(state: GameState): GameState =
    val staleRank = if state.activeColor.isWhite then 6 else 3
    val kept      = (0 until 64).foldLeft(Bitboard.empty) { (targets, index) =>
      val square = Square.fromIndex(index)
      if state.enPassant.contains(square) && square.rank != staleRank then targets.add(square) else targets
    }
    state.copy(
      flags = state.flags.withActiveColor(state.activeColor.opponent).withDicePool(Nil),
      enPassant = kept,
      fullMoveNumber = state.fullMoveNumber + (if state.activeColor.isBlack then 1 else 0),
      zobristKey = 0L
    )

/** Normalize by operations: normalResults equals actual endTurn invocations, including isolated calls. */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class EndTurnWork:
  private var operationTotal = 0L
  private var normalTotal    = 0L
  private var terminalTotal  = 0L
  def operations: Long       = operationTotal
  def normalResults: Long    = normalTotal
  def terminalResults: Long  = terminalTotal
  @Setup(Level.Iteration)
  def reset(): Unit =
    operationTotal = 0L
    normalTotal = 0L
    terminalTotal = 0L
  def record(normal: Long, terminal: Long): Unit =
    operationTotal += 1
    normalTotal += normal
    terminalTotal += terminal
