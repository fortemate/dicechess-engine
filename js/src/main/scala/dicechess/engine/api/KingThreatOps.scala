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
    val parsedActions: Option[Int] =
      if js.isUndefined(maxActions) then Some(3)
      else
        val raw = maxActions.asInstanceOf[js.Any]
        if Option(raw.asInstanceOf[AnyRef]).isEmpty || js.typeOf(raw) != "number" then None
        else
          val d = raw.asInstanceOf[Double]
          if d.isNaN || d.isInfinite || d != math.floor(d) || d <= 0.0 then None
          else Some(d.toInt)

    parsedActions match
      case None          => 0
      case Some(actions) =>
        if Option(dfen).isEmpty || Option(targetSquare).isEmpty then 0
        else
          val weight =
            for
              state  <- FenParser.parse(dfen).toOption
              target <- Square.fromNotation(targetSquare)
            yield KingThreat.threatWeight(state, target, actions)
          weight.getOrElse(0)
