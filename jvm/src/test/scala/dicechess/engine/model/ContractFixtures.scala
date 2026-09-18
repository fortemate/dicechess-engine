// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

import java.nio.file.{Path, Paths}

/** Paths of the model-contract fixtures under `jvm/src/test/resources`, all generated (and regenerable) by
  * `generate_model_contract_fixtures.py` — see that script for the graph, the weights, and where the probe vectors come
  * from.
  */
object ContractFixtures:

  /** The engine version the fixture manifests are compatible with, and therefore the one these tests pass as the host's
    * version. A release triple on purpose: the comparator grammar shared with the training and evaluation sides has no
    * pre-release syntax.
    */
  val EngineVersion = "0.12.0"

  def path(resource: String): Path =
    Paths.get(Option(getClass.getResource(resource)).getOrElse(sys.error(s"missing test resource: $resource")).toURI)

  /** Contract-conforming kcp-13 value model: FLOAT `input` `[batch,13]` → FLOAT `output` `[batch,1]`, sigmoid-bounded.
    */
  def valueModel: Path = path("/synthetic_kcp13_value_test_model.onnx")

  /** Manifest version 1.1.0, role `position-value`. */
  def valueManifest: Path = path("/synthetic_kcp13_value_manifest.json")

  /** Manifest version 1.0.0 — the shape the evaluation service and the kcp-13 serving contract emit. */
  def legacyManifest: Path = path("/synthetic_kcp13_value_legacy_manifest.json")

  /** Same bytes, declared for a different role: the fixture the role gate is tested with. */
  def collapseManifest: Path = path("/synthetic_kcp13_collapse_manifest.json")

  /** Probe vectors plus the outputs Python's ONNX Runtime produced for them. */
  def parityVectors: Path = path("/synthetic_kcp13_value_parity.json")

  /** A pre-contract fixture whose output tensor is named `variable` instead of `output` — the real mismatch the
    * contract check exists to catch.
    */
  def legacyNamedModel: Path = path("/synthetic_kcp_mobility_27_test_model.onnx")
