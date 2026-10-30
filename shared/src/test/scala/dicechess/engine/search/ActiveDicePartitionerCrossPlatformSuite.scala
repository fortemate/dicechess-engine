// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

/** Run the rules partition oracle through the engine matrix, including the Wasm linker (#387). A dependency on rulesJS
  * test classes alone does not discover their suites in the Wasm project.
  */
class ActiveDicePartitionerCrossPlatformSuite extends ActiveDicePartitionerSuite
