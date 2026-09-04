package dicechess.engine.movegen

import dicechess.engine.movegen.ChessDsl.*

/** Golden move generator test fixtures defined via [[ChessDsl]].
  *
  * Shared across JVM, Scala.js, and WebAssembly test suites, and consumed by [[DocGenerator]] to publish the visual
  * catalog on the documentation site.
  */
object MoveGenFixtures:

  val oneDieSuites: List[MoveGenTestCase] = List(
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 P"
      .titled("Initial position: pawn moves")
      .describedAs(
        "Standard starting position. With a Pawn (1) rolled, any white pawn can advance one or two squares forward."
      )
      .shouldYield(
        "a2a3",
        "a2a4",
        "b2b3",
        "b2b4",
        "c2c3",
        "c2c4",
        "d2d3",
        "d2d4",
        "e2e3",
        "e2e4",
        "f2f3",
        "f2f4",
        "g2g3",
        "g2g4",
        "h2h3",
        "h2h4"
      ),
    "4k3/8/8/8/8/8/8/4K3 w - - 0 1 R"
      .titled("King only under Rook roll")
      .describedAs(
        "The active player only has a King on the board. With a Rook (4) rolled, there are no rooks to move, and the King cannot act as a Rook. No legal moves are generated."
      )
      .shouldYield(),
    "4k3/8/8/8/8/8/8/4K3 w - - 0 1 K"
      .titled("King on the first row")
      .describedAs(
        "The King is located on e1 with no other pieces on the board. Under a King (6) roll, all standard adjacent moves are legal."
      )
      .shouldYield(
        "e1d1",
        "e1d2",
        "e1e2",
        "e1f2",
        "e1f1"
      ),
    "rnbqkbnr/pppppppp/8/8/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 0 1 Q"
      .titled("Queen moves from f3")
      .describedAs(
        "The Queen is on f3 in a semi-open board. Under a Queen (5) roll, it can move along any unobstructed diagonal, rank, or file."
      )
      .shouldYield(
        "f3d1",
        "f3e2",
        "f3g4",
        "f3h5",
        "f3a3",
        "f3b3",
        "f3c3",
        "f3d3",
        "f3e3",
        "f3g3",
        "f3h3",
        "f3f4",
        "f3f5",
        "f3f6",
        "f3f7"
      ),
    "rnbqkbnr/ppp1pppp/8/3pP3/2B5/5Q2/PPPP1PPP/RNB1K1NR w KQkq d6 0 1 P"
      .titled("En Passant and Path Blockage")
      .describedAs(
        "A complex pawn scenario: the pawn on e5 can capture the black d5 pawn en passant (exd6). The c2 pawn's two-square advance is blocked by the bishop on c4, so only c2-c3 is legal. The f2 pawn is completely blocked by the queen on f3."
      )
      .shouldYield(
        "a2a3",
        "a2a4",
        "b2b3",
        "b2b4",
        "c2c3",
        "d2d3",
        "d2d4",
        "e5e6",
        "e5d6",
        "g2g3",
        "g2g4",
        "h2h3",
        "h2h4"
      ),
    "r1bqk2r/ppp2ppp/2n1pn2/3pP3/1bP5/1P3Q1N/PB1P1PPP/RN2KB1R w KQkq d6 0 1 P"
      .titled("En Passant, Pawn Choices, and Forward Blockage")
      .describedAs(
        "The C pawn can either capture or move forward one square. The D pawn can move one or two squares forward, exposing the white king to check. The E pawn can capture en passant or take the knight, but cannot move forward."
      )
      .shouldYield(
        "a2a3",
        "a2a4",
        "c4c5",
        "c4d5",
        "d2d3",
        "d2d4",
        "e5d6",
        "e5f6",
        "g2g3",
        "g2g4"
      ),
    "5k2/3P4/2K5/8/8/8/8/8 w - - 0 1 P"
      .titled("Pawn promotion")
      .describedAs(
        "A pawn on the seventh rank can advance to the eighth rank and promote to any piece (usually a queen). In this case, with a roll of 1, the only legal move is d7d8, which results in a pawn promotion."
      )
      .shouldYield(
        "d7d8b",
        "d7d8n",
        "d7d8q",
        "d7d8r"
      ),
    "5k2/3P4/2K5/8/8/8/8/8 w - - 0 1 K"
      .titled("King and pawn, king move")
      .describedAs(
        "A pawn on the seventh rank can advance to the eighth rank and promote to any piece (usually a queen). In this case, with a roll of 6, the king moves."
      )
      .shouldYield(
        "c6b5",
        "c6b6",
        "c6b7",
        "c6c5",
        "c6c7",
        "c6d5",
        "c6d6"
      ),
    "4k3/3P4/8/2K5/8/8/8/8 w - - 0 1 P"
      .titled("Pawn promotion or capture")
      .describedAs(
        "With a roll of Pawn (1), the pawn on d7 can promote to any piece on d8, or capture the black king on e8."
      )
      .shouldYield(
        "d7d8b",
        "d7d8n",
        "d7d8q",
        "d7d8r",
        "d7e8"
      ),
    "rnbqkbnr/1p1p1ppp/8/pPpPp3/P3P3/2N2Q1N/2P2PPP/1RB1KB1R w Kkq a6c6e6 0 1 P"
      .titled("Three en passant targets")
      .describedAs(
        "In this position, there are three en passant targets: a6, c6, and e6. The pawn on b5 can capture the pawn on a5 en passant (b5a6), or the pawn on c5 en passant (b5c6). The pawn on d5 can capture the pawn on c5 en passant (d5c6), or the pawn on e5 en passant (d5e6). Additionally, both pawns can move forward one square, and the g2 pawn can move one or two squares forward."
      )
      .shouldYield(
        "b5b6",
        "b5a6",
        "b5c6",
        "d5c6",
        "d5d6",
        "d5e6",
        "g2g3",
        "g2g4"
      )
  )

  val twoDiceSuites: List[MoveGenTestCase] = List(
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PN"
      .titled("Initial position: pawn and knight moves")
      .describedAs(
        "Starting position with two dice rolled: Pawn (1) and Knight (2). Since no micro-move can block another in the initial turn, the legal moves are the sum of all individual legal pawn moves and all individual legal knight moves."
      )
      .shouldYield(
        "a2a3",
        "a2a4",
        "b2b3",
        "b2b4",
        "c2c3",
        "c2c4",
        "d2d3",
        "d2d4",
        "e2e3",
        "e2e4",
        "f2f3",
        "f2f4",
        "g2g3",
        "g2g4",
        "h2h3",
        "h2h4",
        "b1a3",
        "b1c3",
        "g1f3",
        "g1h3"
      ),
    "r1b1k1nr/pppp1ppp/2n1pq2/8/1bB1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 0 1 RK"
      .titled("Short castling")
      .describedAs(
        "Short castling is possible in this position"
      )
      .shouldYield(
        "e1e2",
        "e1f1",
        "a1b1",
        "h1f1",
        "h1g1",
        "e1g1"
      ),
    "r1bqk1nr/ppppbppp/2n5/6B1/3Q4/5N2/PPP1PPPP/R3KBNR w KQkq - 0 1 RK"
      .titled("Long castling")
      .describedAs(
        "Long castling is possible in this position"
      )
      .shouldYield(
        "e1d1",
        "e1d2",
        "a1b1",
        "a1c1",
        "a1d1",
        "e1c1"
      ),
    "3k4/5P2/2P5/8/5K2/8/8/8 w - - 0 1 PN"
      .titled("Force promotion to knight")
      .describedAs(
        "Forcing a pawn to promote to a knight in this position"
      )
      .shouldYield(
        "f7f8n"
      ),
    "3k4/5P2/2P5/8/5K2/8/8/8 w - - 0 1 PB"
      .titled("Force promotion to bishop")
      .describedAs(
        "Forcing a pawn to promote to a bishop in this position"
      )
      .shouldYield(
        "f7f8b"
      ),
    "3k4/5P2/2P5/8/5K2/8/8/8 w - - 0 1 PR"
      .titled("Force promotion to rook")
      .describedAs(
        "Forcing a pawn to promote to a rook in this position"
      )
      .shouldYield(
        "f7f8r"
      ),
    "3k4/5P2/2P5/8/5K2/8/8/8 w - - 0 1 PQ"
      .titled("Force promotion to queen")
      .describedAs(
        "Forcing a pawn to promote to a queen in this position"
      )
      .shouldYield(
        "f7f8q"
      ),
    "8/3k1P2/2P5/8/5K2/8/8/8 w - - 0 1 PN"
      .titled("Beat the King or force promotion to knight")
      .describedAs(
        "Beat the King (1 micro move) or forcing a pawn to promote to a knight in this position (2 micro moves). Both options are legal and the player can choose which one to execute."
      )
      .shouldYield(
        "c6d7",
        "f7f8n"
      ),
    "8/3k1P2/2P5/8/5K2/8/8/8 w - - 0 1 PB"
      .titled("Beat the King or force promotion to bishop")
      .describedAs(
        "Beat the King (1 micro move) or forcing a pawn to promote to a bishop in this position (2 micro moves). Both options are legal and the player can choose which one to execute."
      )
      .shouldYield(
        "c6d7",
        "f7f8b"
      ),
    "8/3k1P2/2P5/8/5K2/8/8/8 w - - 0 1 PR"
      .titled("Beat the King or force promotion to rook")
      .describedAs(
        "Beat the King (1 micro move) or forcing a pawn to promote to a rook in this position (2 micro moves). Both options are legal and the player can choose which one to execute."
      )
      .shouldYield(
        "c6d7",
        "f7f8r"
      ),
    "8/3k1P2/2P5/8/5K2/8/8/8 w - - 0 1 PQ"
      .titled("Beat the King or force promotion to queen")
      .describedAs(
        "Beat the King (1 micro move) or forcing a pawn to promote to a queen in this position (2 micro moves). Both options are legal and the player can choose which one to execute."
      )
      .shouldYield(
        "c6d7",
        "f7f8q"
      ),
    "4k3/8/8/8/8/6b1/8/4K2R w K - 0 1 RK"
      .titled("The king can castle out of check")
      .describedAs(
        "In this position, the white king is in check from the black bishop on g3. However, the king can still castle to escape the check. The legal moves include both the king's possible moves and the castling move, which allows the king to move two squares towards the rook and the rook to move to the square next to the king on the opposite side."
      )
      .shouldYield(
        "e1d1",
        "e1d2",
        "e1e2",
        "e1f1",
        "e1f2",
        "h1f1",
        "h1g1",
        "e1g1",
        "h1h2",
        "h1h3",
        "h1h4",
        "h1h5",
        "h1h6",
        "h1h7",
        "h1h8"
      ),
    "4k3/8/8/8/1b6/8/8/R3K3 w Q - 0 1 RK"
      .titled("The king can castle out of check (long castling)")
      .describedAs(
        "In this position, the white king is in check from the black bishop on b4. However, the king can still castle to escape the check. The legal moves include both the king's possible moves and the castling move, which allows the king to move two squares towards the rook and the rook to move to the square next to the king on the opposite side."
      )
      .shouldYield(
        "e1d1",
        "e1d2",
        "e1e2",
        "e1f1",
        "e1f2",
        "a1b1",
        "a1c1",
        "a1d1",
        "e1c1",
        "a1a2",
        "a1a3",
        "a1a4",
        "a1a5",
        "a1a6",
        "a1a7",
        "a1a8"
      ),
    "4k3/8/8/2b5/8/8/8/4K2R w K - 0 1 RK"
      .titled("After short castling, the king may be in check")
      .describedAs(
        "In Dice Chess, after a short castling, the king can end up in check and this will be considered a legal move."
      )
      .shouldYield(
        "e1d1",
        "e1d2",
        "e1e2",
        "e1f1",
        "e1f2",
        "h1f1",
        "h1g1",
        "e1g1",
        "h1h2",
        "h1h3",
        "h1h4",
        "h1h5",
        "h1h6",
        "h1h7",
        "h1h8"
      ),
    "4k3/8/8/6b1/8/8/8/R3K3 w Q - 0 1 RK"
      .titled("After long castling, the king may be in check")
      .describedAs(
        "In Dice Chess, after a long castling, the king can end up in check and this will be considered a legal move."
      )
      .shouldYield(
        "e1d1",
        "e1d2",
        "e1e2",
        "e1f1",
        "e1f2",
        "a1b1",
        "a1c1",
        "a1d1",
        "e1c1",
        "a1a2",
        "a1a3",
        "a1a4",
        "a1a5",
        "a1a6",
        "a1a7",
        "a1a8"
      ),
    "4k3/8/8/8/8/8/8/R3K2R w K - 0 1 RK"
      .titled("Only short castling is possible")
      .describedAs(
        "In this position the rook on square A1 has already made a move and therefore only short castling is possible."
      )
      .shouldYield(
        "e1d1",
        "e1d2",
        "e1e2",
        "e1f1",
        "e1f2",
        "h1f1",
        "h1g1",
        "e1g1",
        "h1h2",
        "h1h3",
        "h1h4",
        "h1h5",
        "h1h6",
        "h1h7",
        "h1h8",
        "a1b1",
        "a1c1",
        "a1d1",
        "a1a2",
        "a1a3",
        "a1a4",
        "a1a5",
        "a1a6",
        "a1a7",
        "a1a8"
      ),
    "4k3/8/8/8/8/8/8/R3K2R w Q - 0 1 RK"
      .titled("Only long castling is possible")
      .describedAs(
        "In this position the rook on square H1 has already made a move and therefore only long castling is possible."
      )
      .shouldYield(
        "e1d1",
        "e1d2",
        "e1e2",
        "e1f1",
        "e1f2",
        "h1f1",
        "h1g1",
        "e1c1",
        "h1h2",
        "h1h3",
        "h1h4",
        "h1h5",
        "h1h6",
        "h1h7",
        "h1h8",
        "a1b1",
        "a1c1",
        "a1d1",
        "a1a2",
        "a1a3",
        "a1a4",
        "a1a5",
        "a1a6",
        "a1a7",
        "a1a8"
      ),
    "4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1 RK"
      .titled("Both castling options are possible")
      .describedAs(
        "In this position both rooks and the king have not made a move and therefore both short and long castling are possible."
      )
      .shouldYield(
        "e1d1",
        "e1d2",
        "e1e2",
        "e1f1",
        "e1f2",
        "h1f1",
        "h1g1",
        "e1c1",
        "e1g1",
        "h1h2",
        "h1h3",
        "h1h4",
        "h1h5",
        "h1h6",
        "h1h7",
        "h1h8",
        "a1b1",
        "a1c1",
        "a1d1",
        "a1a2",
        "a1a3",
        "a1a4",
        "a1a5",
        "a1a6",
        "a1a7",
        "a1a8"
      ),
    "rnbqkbnr/pppppppp/8/8/1P6/8/P1PPPPPP/RNBQKBNR w KQkq - 0 1 PR"
      .titled("en passant is not possible on own pawn")
      .describedAs(
        "In this position, the white pawn on b4 has just moved two squares forward from b2 to b4. However, the en passant capture is not possible for the white pawn on a2 because it cannot capture its own piece. Therefore, the legal moves for the white player are limited to moving the pawns on a2 and h2."
      )
      .shouldYield(
        "a2a3",
        "a2a4",
        "h2h3",
        "h2h4"
      )
  )

  val threeDiceSuites: List[MoveGenTestCase] = List(
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 PNB"
      .titled("Initial position: path optimization filter")
      .describedAs(
        "Starting position with all three dice rolled: Pawn (1), Knight (2), and Bishop (3). According to the Dice Chess maximum micro-moves rules, quiet a/c/f/h pawn moves are completely filtered out because they do not form or enable a valid 3-move sequence (e.g. Pawn -> Bishop -> Knight)."
      )
      .shouldYield(
        "b2b3",
        "b2b4",
        "d2d3",
        "d2d4",
        "e2e3",
        "e2e4",
        "g2g3",
        "g2g4",
        "b1a3",
        "b1c3",
        "g1f3",
        "g1h3"
      ),
    "k2n1q2/1p2Pp2/4r3/2pP4/8/8/1PK5/8 w - c6 0 1 PPP"
      .titled("Pawns with three dice")
      .describedAs(
        "In this position, the white pawn on d5 can move to c6, d6, or e6 using one of the '1' dice. The white pawn on e7 can promote to a knight, bishop, queen, or rook on d8, e8, or f8 using the remaining two '1' dice."
      )
      .shouldYield(
        "b2b3",
        "b2b4",
        "d5c6",
        "d5d6",
        "d5e6",
        "e7d8n",
        "e7d8b",
        "e7d8q",
        "e7d8r",
        "e7e8n",
        "e7e8b",
        "e7e8q",
        "e7e8r",
        "e7f8n",
        "e7f8b",
        "e7f8q",
        "e7f8r"
      ),
    "r1bqk2r/ppppbppp/2n1pn2/1B6/3PP3/2N5/PPP2PPP/R1BQK1NR b KQkq - 0 1 brk"
      .titled("Casting and bishop moves with three dice")
      .describedAs(
        "In this position, the black king on e8 can castle kingside to g8, or move to f8. The black bishop on e7 can move to f8, d6, c5, b4, or a3. The black rook on a8 can move to b8"
      )
      .shouldYield(
        "e8f8",
        "e8g8",
        "h8g8",
        "h8f8",
        "e7f8",
        "e7d6",
        "e7c5",
        "e7b4",
        "e7a3",
        "a8b8"
      ),
    "8/4P3/5K2/8/2k5/8/1P6/8 w - - 0 1 PNB"
      .titled("Force promotion to knight or bishop")
      .describedAs(
        "Forcing a pawn to promote to a knight or bishop. Maximum two micro moves in this position."
      )
      .shouldYield(
        "e7e8n",
        "e7e8b"
      ),
    "8/4P3/5K2/8/2k5/8/1P6/8 w - - 0 1 PNR"
      .titled("Force promotion to knight or rook")
      .describedAs(
        "Forcing a pawn to promote to a knight or rook. Maximum two micro moves in this position."
      )
      .shouldYield(
        "e7e8n",
        "e7e8r"
      ),
    "8/4P3/5K2/8/2k5/8/1P6/8 w - - 0 1 PNQ"
      .titled("Force promotion to knight or queen")
      .describedAs(
        "Forcing a pawn to promote to a knight or queen. Maximum two micro moves in this position."
      )
      .shouldYield(
        "e7e8n",
        "e7e8q"
      ),
    "8/4P3/5K2/8/2k5/8/1P6/8 w - - 0 1 PNK"
      .titled("Force promotion to knight only")
      .describedAs(
        "Forcing a pawn to promote to a knight only."
      )
      .shouldYield(
        "e7e8n",
        "f6f7",
        "f6f5",
        "f6e5",
        "f6g5",
        "f6e6",
        "f6g6",
        "f6g7"
      ),
    "8/4P3/5K2/8/2k5/8/1P6/8 w - - 0 1 PPN"
      .titled("Beat the king or promote to a knight")
      .describedAs(
        "We can beat the king or promote to a knight. Two or three micro moves in this position."
      )
      .shouldYield(
        "e7e8n",
        "b2b3",
        "b2b4"
      ),
    "rnq1k2r/pppbpp1p/3p2pQ/8/2BPP3/2N2N2/PPPK1PPP/1R5R b kq - 0 1 BKK"
      .titled("Bishop and king moves with three dice")
      .describedAs(
        "In this position, the black king on e8 can move to f8 or d8. The black bishop on d7 can move to c6, b5, a4, e6, f5, g4, or h3."
      )
      .shouldYield(
        "e8f8",
        "e8d8",
        "d7c6",
        "d7b5",
        "d7a4",
        "d7e6",
        "d7f5",
        "d7g4",
        "d7h3"
      ),
    "8/P7/8/8/8/8/8/k6K w - - 0 1 PPP"
      .titled("One pawn promotion with three pawn's dice")
      .describedAs(
        "In this position, the white pawn on a7 can promote to a knight, bishop, queen, or rook on a8 using the three '1' dice."
      )
      .shouldYield(
        "a7a8n",
        "a7a8b",
        "a7a8q",
        "a7a8r"
      ),
    "8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1 PPN"
      .titled("King-capture continuation with three dice")
      .describedAs(
        "Multi-move King capture path is legal under the King-capture exemption even when quiet paths of length 3 exist (c2-c4 enables Nb3xc5 on micro-move 2)."
      )
      .shouldYield(
        "b3a5",
        "b3c1",
        "b3c5",
        "b3d2",
        "b3d4",
        "c2c3",
        "c2c4"
      )
  )

  val allSuites: List[(String, String, List[MoveGenTestCase])] = List(
    (
      "1-Die Scenarios",
      "Move generator tests with a single die rolled. These represent the fundamental building blocks of legal move filtering.",
      oneDieSuites
    ),
    (
      "2-Dice Scenarios",
      "Move generator tests with two dice rolled. These evaluate intermediate micro-move sequences.",
      twoDiceSuites
    ),
    (
      "3-Dice Scenarios",
      "Move generator tests with all three dice rolled. These verify full turn execution and complete path optimization.",
      threeDiceSuites
    )
  )
