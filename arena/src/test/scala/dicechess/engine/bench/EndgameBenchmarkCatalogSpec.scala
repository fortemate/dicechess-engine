// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import munit.FunSuite

class EndgameBenchmarkCatalogSpec extends FunSuite:

  test("loads bundled endgames-v1.json successfully"):
    val suite = EndgameBenchmarkCatalog.load(None).fold(err => fail(s"Failed to load: $err"), identity)
    assertEquals(suite.id, "endgames-v1")
    assertEquals(suite.schemaVersion, 1)
    assertEquals(suite.positions.size, 17)

  test("contains all canonical endgame categories"):
    val suite      = EndgameBenchmarkCatalog.load(None).toOption.get
    val categories = suite.positions.map(_.category).distinct.sorted
    val expected   = List("kbn-vs-k", "lone-king-vs-k2p", "lone-king-vs-kq", "lone-king-vs-kr", "kp-vs-k").sorted
    assertEquals(categories, expected)

  test("all positions have valid kings and matching Chebyshev / Manhattan distances"):
    val suite = EndgameBenchmarkCatalog.load(None).toOption.get
    for pos <- suite.positions do
      assert(!(pos.state.kings & pos.state.whitePieces).isEmpty, s"${pos.id} must have white king")
      assert(!(pos.state.kings & pos.state.blackPieces).isEmpty, s"${pos.id} must have black king")
      assert(pos.kingChebyshevDistance >= 1 && pos.kingChebyshevDistance <= 7, s"${pos.id} invalid chebyshev")
      assert(pos.kingManhattanDistance >= 1 && pos.kingManhattanDistance <= 14, s"${pos.id} invalid manhattan")
      assert(pos.attackerColor != pos.defenderColor, s"${pos.id} attacker must oppose defender")

  test("rejects malformed JSON or invalid schema version"):
    assert(EndgameBenchmarkCatalog.parse("not json").isLeft)
    assert(
      EndgameBenchmarkCatalog
        .parse("""{"schemaVersion": 2, "id": "test", "description": "d", "positions": []}""")
        .isLeft
    )

  test("rejects position with mismatched king distance"):
    val badJson =
      """{
        |  "schemaVersion": 1,
        |  "id": "bad-dist",
        |  "description": "test",
        |  "positions": [
        |    {
        |      "id": "bad-pos",
        |      "category": "lone-king-vs-kq",
        |      "name": "Bad Pos",
        |      "description": "Wrong distance",
        |      "fen": "8/8/8/4k3/8/8/4K3/3Q4 w - - 0 1",
        |      "attackerColor": "white",
        |      "defenderColor": "black",
        |      "kingChebyshevDistance": 1,
        |      "kingManhattanDistance": 1,
        |      "theoreticalOutcome": "attacker-win"
        |    }
        |  ]
        |}""".stripMargin
    val res = EndgameBenchmarkCatalog.parse(badJson)
    assert(res.isLeft)
    assert(res.left.toOption.get.contains("kingChebyshevDistance"))
