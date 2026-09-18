// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

/** What a trained model *is used for*, declared by its manifest and checked before the model is wired into a search.
  *
  * The role is not cosmetic metadata: the three roles below answer different questions about different objects, and
  * feeding one where another is expected produces a bot that runs, reports no error, and plays badly. Tensor width
  * cannot tell them apart — two roles may share a feature schema and therefore an identical `[batch, F]` input — so the
  * role is the only thing that can.
  *
  *   - [[ModelRole.PositionValue]] — P(the evaluated color wins) for a position whose next roll is unknown. The leaf
  *     evaluator of every ONNX bot here, and the root rescorer, are this role.
  *   - [[ModelRole.ChanceCollapse]] — the *expectation* a chance node would compute for the position reached by a turn,
  *     i.e. a learned replacement for expanding all 56 weighted dice outcomes. Trained against a deeper search's value,
  *     not against game outcomes, so its scale is an expectation and not a win probability.
  *   - [[ModelRole.MovePreRank]] — a score per candidate turn, used only to choose which candidates a search expands.
  *     Ordering is all that is consumed; the absolute value is never compared against a value-model score.
  *
  * The ids are the strings a manifest carries and match the role inventory of the training repository's train/serve
  * contract, so one artifact describes itself the same way on both sides.
  */
enum ModelRole(val id: String) derives CanEqual:
  case PositionValue  extends ModelRole("position-value")
  case ChanceCollapse extends ModelRole("chance-collapse")
  case MovePreRank    extends ModelRole("move-prerank")

object ModelRole:

  /** Resolves a manifest's `modelRole` string, listing the known ids on failure — an unknown role is a fail-closed
    * error rather than a default, because "assume position value" is exactly the silent mis-wiring the field exists to
    * prevent.
    */
  def find(id: String): Either[String, ModelRole] =
    values
      .find(_.id == id)
      .toRight(s"unknown modelRole '$id'; known roles: ${values.map(_.id).sorted.mkString(", ")}")
