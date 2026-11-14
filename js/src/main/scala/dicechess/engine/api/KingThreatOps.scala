// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.api

import dicechess.engine.domain.*
import dicechess.engine.search.KingThreat
import scala.scalajs.js

/** Threat evaluation against a target king square, from [[dicechess.engine.search.KingThreat]].
  *
  * Kept out of [[dicechess.engine.api.RulesOps]] on purpose: `RulesOps` sits in the shared chunk loaded by both export
  * roots (`.` and `./rules`), so referencing `KingThreat` there would pull `KingThreat` and the `search` package into
  * `./rules` (ADR 009, #222). Only [[dicechess.engine.api.JsApi]] calls this object.
  */
private[engine] object KingThreatOps:

  @noinline
  def threatWeight(dfen: String, targetSquare: String, maxActions: js.UndefOr[js.Any]): Int =
    val actions = parseMaxActions(maxActions)
    if actions <= 0 || Option(dfen).isEmpty || Option(targetSquare).isEmpty then 0
    else computeThreatWeight(dfen, targetSquare, actions)

  private inline def computeThreatWeight(dfen: String, targetSquare: String, actions: Int): Int =
    val weight =
      for
        state  <- FenParser.parse(dfen).toOption
        target <- Square.fromNotation(targetSquare)
      yield KingThreat.threatWeight(state, target, actions)
    weight.getOrElse(0)

  private inline def parseMaxActions(maxActions: js.UndefOr[js.Any]): Int =
    if js.isUndefined(maxActions) then 3
    else parseActionCount(maxActions.asInstanceOf[js.Any])

  private inline def parseActionCount(raw: js.Any): Int =
    if js.typeOf(raw) != "number" then 0
    else
      val d = raw.asInstanceOf[Double]
      if isPositiveWholeNumber(d) then d.toInt else 0

  private inline def isPositiveWholeNumber(d: Double): Boolean =
    !d.isNaN && !d.isInfinite && d == math.floor(d) && d > 0.0
