// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

import dicechess.engine.domain.{Color, FenParser}
import dicechess.engine.search.{OnnxFeatures, RichFeatures}
import munit.FunSuite

/** The registry is only useful if an id really identifies a layout, so these tests check the registry against the
  * extractors themselves rather than against a copy of their metadata (#78).
  */
class FeatureSchemaSpec extends FunSuite:

  private val position = FenParser.parse(FenParser.InitialPosition).toOption.get

  test("every registered schema's declared width is the width its extractor actually produces"):
    FeatureSchema.all.foreach: schema =>
      val row = schema.extract(position, Color.White)
      assertEquals(row.length, schema.featureCount, s"schema ${schema.id} extracted ${row.length} features")
      assertEquals(schema.columnNames.length, schema.featureCount, s"schema ${schema.id} column count")

  test("schema ids are unique and resolvable, and an unknown id lists the known ones"):
    val ids = FeatureSchema.all.map(_.id)
    assertEquals(ids.distinct.length, ids.length, s"duplicate schema ids in $ids")
    ids.foreach(id => assertEquals(FeatureSchema.find(id).map(_.id), Right(id)))
    val failure = FeatureSchema.find("kcp-14").swap.getOrElse(fail("unknown schema must be rejected"))
    assert(failure.contains("unknown featureSchema 'kcp-14'"), failure)
    assert(failure.contains("kcp-13"), failure)

  test("the material block is the prefix every set built on it documents"):
    assertEquals(RichFeatures.columnNames.take(OnnxFeatures.columnNames.length), OnnxFeatures.columnNames)
    assertEquals(FeatureSchema.Kcp13.columnNames.take(RichFeatures.columnNames.length), RichFeatures.columnNames)

  test("the deployed kcp-13 contract keeps its unversioned id and 13 columns"):
    assertEquals(FeatureSchema.Kcp13.id, "kcp-13")
    assertEquals(FeatureSchema.Kcp13.featureCount, 13)
