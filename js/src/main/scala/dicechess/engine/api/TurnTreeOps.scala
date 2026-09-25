// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.api

import dicechess.engine.domain.*
import dicechess.engine.search.TurnGenerator
import scala.scalajs.js

/** The legal turn tree of a rolled position, built from [[dicechess.engine.search.TurnGenerator]].
  *
  * It is kept out of [[dicechess.engine.api.RulesOps]] on purpose. The linker places whole classes in modules, and
  * `RulesOps` sits in the chunk both export roots load, so a method there that reached `TurnGenerator` would move
  * `TurnGenerator` into the `./rules` closure even with no export on that root. The tree stays on the full entry
  * (#279), so only [[dicechess.engine.api.JsApi]] calls this object.
  *
  * The tree is built from plain JavaScript objects and arrays rather than Scala collections, which keeps its cost on
  * the full entry to a few kilobytes.
  */
private[engine] object TurnTreeOps:

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
