package dicechess.engine.search

import dicechess.engine.domain.*
import munit.FunSuite

/** Pins [[KcpFeatures]]: the vector extends [[RichFeatures]] with the four capture-probability columns, and those
  * columns carry the right mover-perspective sign — capture DANGER for the threatened side, ATTACK for the threatening
  * one.
  */
class KcpFeaturesSpec extends FunSuite:

  private def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  private val KingAttack  = KcpFeatures.columnNames.indexOf("king_capture_attack")
  private val KingDanger  = KcpFeatures.columnNames.indexOf("king_capture_danger")
  private val QueenAttack = KcpFeatures.columnNames.indexOf("queen_capture_attack")
  private val QueenDanger = KcpFeatures.columnNames.indexOf("queen_capture_danger")

  test("columnNames is RichFeatures plus the four capture-probability columns"):
    assertEquals(
      KcpFeatures.columnNames,
      RichFeatures.columnNames ++ List(
        "king_capture_attack",
        "king_capture_danger",
        "queen_capture_attack",
        "queen_capture_danger"
      )
    )
    assertEquals(KcpFeatures.columnNames.length, 13)

  test("the vector length matches columnNames"):
    assertEquals(KcpFeatures.extract(parse(FenParser.InitialPosition), Color.White).length, 13)

  test("the RichFeatures block is reused verbatim as the prefix"):
    val state = parse("r1bqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1") // Black missing a knight
    for color <- List(Color.White, Color.Black) do
      assertEquals(
        KcpFeatures.extract(state, color).take(RichFeatures.columnNames.length).toList,
        RichFeatures.extract(state, color).toList,
        s"RichFeatures prefix diverged for $color"
      )

  test("no king or queen is capturable from the safe start position"):
    val f = KcpFeatures.extract(parse(FenParser.InitialPosition), Color.White)
    for i <- List(KingAttack, KingDanger, QueenAttack, QueenDanger) do assertEquals(f(i), 0f)

  test("a rook bearing on the enemy king is capture DANGER for the threatened side, ATTACK for the other"):
    // Black rook e2 bears on the White king e1 down the open e-file; the Black king (e8) is threatened by nothing.
    val state = parse("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1")
    val white = KcpFeatures.extract(state, Color.White)
    val black = KcpFeatures.extract(state, Color.Black)
    assert(white(KingDanger) > 0f, "White's king is the one in danger")
    assertEquals(white(KingAttack), 0f, "a lone White king cannot reach Black's king")
    // The same threat, from Black's perspective, is an ATTACK.
    assertEquals(black(KingAttack), white(KingDanger))
    assertEquals(black(KingDanger), 0f)
    // No queens on the board.
    assertEquals(white(QueenAttack), 0f)
    assertEquals(white(QueenDanger), 0f)

  test("a rook bearing on the enemy queen raises the queen-capture probability"):
    // White rook e1 bears on the Black queen e5 down the open e-file; both kings are tucked in a corner.
    val state = parse("k7/8/8/4q3/8/8/8/K3R3 w - - 0 1")
    val white = KcpFeatures.extract(state, Color.White)
    assert(white(QueenAttack) > 0f, "White's rook can capture Black's queen")
    // The same threat is queen DANGER from Black's perspective; White has no queen to lose.
    assertEquals(KcpFeatures.extract(state, Color.Black)(QueenDanger), white(QueenAttack))
    assertEquals(white(QueenDanger), 0f)
