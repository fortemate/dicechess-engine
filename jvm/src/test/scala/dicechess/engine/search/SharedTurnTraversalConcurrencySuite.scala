// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite
import scala.concurrent.{ExecutionContext, Future}

/** Invocation isolation for #442: only immutable partition tables may cross concurrent traversal boundaries. */
class SharedTurnTraversalConcurrencySuite extends FunSuite:
  private given ExecutionContext = ExecutionContext.global

  test("concurrent independent invocations retain their own prefix boards and borrowed arrays"):
    val states = List(
      "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
      "4k3/8/8/8/8/8/4p3/K7 b - - 0 12",
      "4k3/8/8/8/8/8/8/4K2R w K - 0 1",
      "4k3/8/8/8/8/8/PP6/4K3 w - - 0 1"
    ).map(fen => FenParser.parse(fen).toOption.get)
    val expected = states.map(reference)
    Future
      .sequence((0 until 16).map { index =>
        Future {
          val state     = states(index % states.size)
          val traversal = new TurnGenerator.SharedTraversal(state)
          val entries   = List.newBuilder[(List[Int], String, Long, Boolean)]
          ActiveDicePartitioner.partitionsFor(state).foreach { weighted =>
            traversal.visit(weighted.roll) { (moves, len, result, capture) =>
              entries += ((
                moves.take(len).map(_.toInt).toList,
                FenParser.serialize(result),
                result.zobristHash,
                capture
              ))
              true
            } match
              case BudgetedRules.Outcome.Complete(_, _) => ()
              case other => fail(s"unbounded independent invocation was interrupted: $other")
          }
          assertEquals(entries.result(), expected(index % states.size))
        }
      })
      .map(_ => ())

  private def reference(state: GameState): List[(List[Int], String, Long, Boolean)] =
    ActiveDicePartitioner.partitionsFor(state).toList.flatMap { weighted =>
      val rolled = weighted.roll.applyToState(state)
      TurnGenerator.generateAllLegalTurnPaths(rolled).map { path =>
        val beforeLast = path.init.foldLeft(rolled)((s, move) => s.makeMove(move))
        val capture    = beforeLast.isKingCapture(path.last)
        val raw        = beforeLast.makeMove(path.last)
        val result     = if capture then raw else raw.endTurn()
        (path.map(_.toInt), FenParser.serialize(result), result.zobristHash, capture)
      }
    }
