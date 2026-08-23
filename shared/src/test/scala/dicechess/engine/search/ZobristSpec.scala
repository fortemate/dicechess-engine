package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

class ZobristSpec extends FunSuite:

  test("incremental Zobrist updates match full recalculations across full turns and endTurn"):
    val initial = FenParser.parse(FenParser.InitialPosition).fold(fail(_), identity).withDicePool(List(1, 2, 4))
    val paths   = TurnGenerator.generateAllLegalTurnPaths(initial)

    assert(paths.nonEmpty)
    for path <- paths do
      val ended = path.foldLeft(initial)((s, move) => s.makeMove(move)).endTurn()
      val computed = Zobrist.computeKey(ended)
      if ended.zobristHash != computed then
        println(s"Path: ${path.map(m => s"${m.fromSquare.toNotation}${m.toSquare.toNotation}")}")
        println(s"Incremental: ${ended.zobristHash}")
        println(s"Computed:    $computed")
        println(s"Diff:        ${ended.zobristHash ^ computed}")
        assertEquals(ended.zobristHash, computed)
