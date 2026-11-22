// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.pilot

import dicechess.engine.domain.*
import dicechess.engine.movegen.LegalMovesFilter

import java.nio.charset.StandardCharsets

object DfenRoundTrip:
  def fuzzerTestOneInput(data: Array[Byte]): Unit =
    require(data.length <= 256)
    FenParser.parse(new String(data, StandardCharsets.UTF_8)).foreach { position =>
      val canonical = FenParser.serialize(position)
      assert(FenParser.parse(canonical) == Right(position))
    }

object GeneratedMove:
  private val starts = Vector(
    FenParser.InitialPosition,
    "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
    "4k3/8/8/2PpP3/8/8/8/4K3 w - d6 0 1",
    "4k3/P7/8/8/8/8/7p/4K3 w - - 0 1"
  ).map(fen => FenParser.parse(fen).toOption.get)

  def fuzzerTestOneInput(data: Array[Byte]): Unit =
    require(data.length <= 9)
    if data.nonEmpty then
      var position = starts((data(0) & 0xff) % starts.size)
      var index    = 1
      while index + 1 < data.length do
        val die   = (data(index) & 0xff) % 6 + 1
        val state = position.withDicePool(List(die))
        val moves = LegalMovesFilter.filterMaximalMoves(state)
        if moves.nonEmpty then
          val move = moves((data(index + 1) & 0xff) % moves.size)
          val next = state.makeMoveAndEndTurn(move)
          assert(next.mailbox(move.fromSquare) == Piece.Empty)
          assert(next.mailbox(move.toSquare).color == state.activeColor)
          assert(next.mailbox(move.toSquare) != Piece.Empty)
          position = next
        index += 2
