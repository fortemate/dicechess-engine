// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.api

import dicechess.engine.domain.*
import dicechess.engine.search.{PlayableDice, TurnGenerator}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** The legal turn tree of a rolled position, and the dice its turns can still spend, both from
  * [[dicechess.engine.search.TurnGenerator]]: the dice through [[dicechess.engine.search.PlayableDice]].
  *
  * It is kept out of [[dicechess.engine.api.RulesOps]] on purpose. The linker places whole classes in modules, and
  * `RulesOps` sits in the chunk both export roots load, so a method there that reached `TurnGenerator` would move
  * `TurnGenerator` into the `./rules` closure even with no export on that root. The tree and the playable dice stay on
  * the full entry (#279, #293), so only [[dicechess.engine.api.JsApi]] calls this object.
  *
  * The tree is built from plain JavaScript objects and arrays rather than Scala collections, which keeps its cost on
  * the full entry to a few kilobytes.
  */
private[engine] object TurnTreeOps:

  /** Not inlined into the export, which would link a second copy of it. Like the tree, it avoids generic collection
    * calls, each of which links in code of its own.
    *
    * @see
    *   [[dicechess.engine.api.JsApi.getPlayableDice]]
    */
  @noinline
  def getPlayableDice(dfen: String, moves: js.UndefOr[js.Array[String]]): js.UndefOr[String] =
    val playable =
      for
        text   <- Option(dfen)
        state  <- FenParser.parse(text).toOption
        played <- movesNamed(moves)
        dice   <- PlayableDice.of(state, played)
      yield diceField(dice, state.activeColor)
    playable.orUndefined

  /** The moves the UCI strings of `moves` name, in order: none when it is omitted or `null`, and `None` when it is not
    * an array or as soon as one of its elements is not a UCI string.
    *
    * Whether they begin a legal turn is for [[dicechess.engine.search.PlayableDice.of]] to decide. It compares moves as
    * UCI names them, so a move built here from its squares needs no position.
    */
  private def movesNamed(moves: js.UndefOr[js.Array[String]]): Option[List[Move]] =
    moves.toOption.flatMap(Option(_)) match
      case None                                    => Some(Nil)
      case Some(array) if !js.Array.isArray(array) => None
      case Some(array)                             =>
        // JavaScript can put any value in the array, so each element is read as it is and checked to be a string.
        val elements = array.asInstanceOf[js.Array[Any]]
        var named    = Option(List.empty[Move])
        var i        = elements.length - 1
        while i >= 0 do
          named = for later <- named; move <- moveNamed(elements(i)) yield move :: later
          i -= 1
        named

  /** The move `element` names when it is a UCI string, such as `e2e4` or `e7e8q`, and `None` otherwise. */
  private def moveNamed(element: Any): Option[Move] =
    // `js.typeOf` rather than a `String` pattern, which links an instance test into the module shared with `./rules`.
    if js.typeOf(element) != "string" then None
    else
      val uci   = element.asInstanceOf[String]
      val flags = uci.length match
        case 4 => Move.QuietMove
        case 5 => promotionFlags(uci.charAt(4))
        case _ => -1
      if flags < 0 then None
      else
        for
          from <- Square.fromNotation(uci.substring(0, 2))
          to   <- Square.fromNotation(uci.substring(2, 4))
        yield Move(from, to, flags)

  /** The flags of a promotion to the piece UCI writes as `piece`, or -1 for any other letter. */
  private def promotionFlags(piece: Char): Int = piece match
    case 'q' => Move.QueenPromotion
    case 'r' => Move.RookPromotion
    case 'b' => Move.BishopPromotion
    case 'n' => Move.KnightPromotion
    case _   => -1

  /** The dice as the DFEN dice field writes them, in the case of the side to move. `dice` are ascending already. */
  private def diceField(dice: List[Int], side: Color): String =
    val field = new StringBuilder
    dice.foreach(face => PieceType.fromDice(face).foreach(piece => field.append(piece.asNotation)))
    if side.isWhite then field.toString.toUpperCase else field.toString

  /** @see [[dicechess.engine.api.JsApi.getLegalTurnTree]] */
  def getLegalTurnTree(dfen: String): js.Dictionary[js.Any] =
    val tree = js.Dictionary.empty[js.Any]
    if Option(dfen).isDefined then
      FenParser.parse(dfen).foreach { state =>
        TurnGenerator.forEachLegalTurnPath(state) { (moves, len) =>
          var node = tree
          var i    = 0
          while i < len do
            node = child(node, moves(i).toUci)
            i += 1
        }
      }
    inUciOrder(tree)

  private def child(node: js.Dictionary[js.Any], uci: String): js.Dictionary[js.Any] =
    val existing = node.asInstanceOf[js.Dynamic].selectDynamic(uci)
    if js.isUndefined(existing) then
      val created = js.Dictionary.empty[js.Any]
      node(uci) = created
      created
    else existing.asInstanceOf[js.Dictionary[js.Any]]

  /** A copy of `node` whose children, at every level, are inserted in UCI order.
    *
    * play-api's encoder sorts a node's children by UCI, and a JavaScript object keeps the insertion order of such keys,
    * so `JSON.stringify` of this copy and play-api's wire JSON for the same roll are the same string. The default
    * `Array.prototype.sort` compares UTF-16 code units, as `String.compareTo` does on the JVM.
    */
  private def inUciOrder(node: js.Dictionary[js.Any]): js.Dictionary[js.Any] =
    val sorted = js.Dictionary.empty[js.Any]
    val keys   = js.Object.keys(node.asInstanceOf[js.Object]).sort()
    var i      = 0
    while i < keys.length do
      val uci = keys(i)
      sorted(uci) = inUciOrder(node.asInstanceOf[js.Dynamic].selectDynamic(uci).asInstanceOf[js.Dictionary[js.Any]])
      i += 1
    sorted
