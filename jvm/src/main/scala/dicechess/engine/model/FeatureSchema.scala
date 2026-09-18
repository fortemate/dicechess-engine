// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

import dicechess.engine.domain.{Color, GameState}
import dicechess.engine.search.{
  KcpFeatures,
  KcpMobilityFeatures,
  KcpMobilityPawnsFeatures,
  OnnxFeatures,
  RawBoardFeatures,
  RichFeatures,
  RichPdiFeatures
}

/** One feature-extraction contract of this engine: the id a model manifest names, the column layout that id promises,
  * and the extractor that produces a row.
  *
  * This is what makes a manifest's `featureSchema` field actionable rather than documentation. A host that loads a
  * model no longer hardcodes which extractor to pair with it — the artifact declares its schema, this registry resolves
  * it, and a model trained on a different layout fails to load instead of silently receiving a vector in which every
  * column means something else. That failure mode is the reason the field exists: two schemas of equal width (there is
  * no such pair today, but nothing prevents one) would otherwise be indistinguishable at run time.
  *
  * The engine is the single source of truth for the values themselves — every extractor here is the same code the
  * training enrichment calls — so `train == serve` holds by construction rather than by a Python reimplementation.
  *
  * @param id
  *   the extractor's own `schemaId`, never a string repeated here
  * @param columnNames
  *   the layout in `extract`'s order; its length is the input width a conforming model must declare
  * @param extract
  *   `(state, color) => row`, from `color`'s perspective and independent of `state`'s active color and dice pool
  */
final class FeatureSchema private[model] (
    val id: String,
    val columnNames: List[String],
    val extract: (GameState, Color) => Array[Float]
):

  /** Input width a model on this schema must declare — `[batch, featureCount]`. */
  val featureCount: Int = columnNames.length

  override def toString: String = s"$id ($featureCount columns)"

object FeatureSchema:

  val Material7: FeatureSchema =
    new FeatureSchema(OnnxFeatures.schemaId, OnnxFeatures.columnNames, OnnxFeatures.extract)

  val Rich9: FeatureSchema =
    new FeatureSchema(RichFeatures.schemaId, RichFeatures.columnNames, RichFeatures.extract)

  val RichPdi11: FeatureSchema =
    new FeatureSchema(RichPdiFeatures.schemaId, RichPdiFeatures.columnNames, RichPdiFeatures.extract)

  val Kcp13: FeatureSchema =
    new FeatureSchema(KcpFeatures.schemaId, KcpFeatures.columnNames, KcpFeatures.extract)

  val KcpMobility27: FeatureSchema =
    new FeatureSchema(KcpMobilityFeatures.schemaId, KcpMobilityFeatures.columnNames, KcpMobilityFeatures.extract)

  val KcpMobilityPawns31: FeatureSchema =
    new FeatureSchema(
      KcpMobilityPawnsFeatures.schemaId,
      KcpMobilityPawnsFeatures.columnNames,
      KcpMobilityPawnsFeatures.extract
    )

  val RawBoard768: FeatureSchema =
    new FeatureSchema(RawBoardFeatures.schemaId, RawBoardFeatures.columnNames, RawBoardFeatures.extract)

  /** Every schema a manifest may name, cheapest first — the order in which their extraction cost grows, which is also
    * the order a reader of an error message finds useful.
    */
  val all: List[FeatureSchema] =
    List(Material7, Rich9, RichPdi11, Kcp13, KcpMobility27, KcpMobilityPawns31, RawBoard768)

  private val byId: Map[String, FeatureSchema] = all.map(schema => schema.id -> schema).toMap

  /** Resolves a manifest's `featureSchema` id, listing the known schemas on failure. */
  def find(id: String): Either[String, FeatureSchema] =
    byId
      .get(id)
      .toRight(s"unknown featureSchema '$id'; known schemas: ${all.map(_.id).sorted.mkString(", ")}")
