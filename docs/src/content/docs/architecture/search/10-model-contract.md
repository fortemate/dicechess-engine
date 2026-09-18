---
title: Model Serving Contract
description: The manifest, roles, and feature schemas that make the engine refuse an unsuitable ONNX model instead of silently mis-serving it.
sidebar:
  order: 10
---

A trained model is not self-describing. The same `[batch, 13]` float tensor can be a position
evaluator, a learned replacement for a chance node, or a move pre-ranker; it can expect the
13 columns of `kcp-13` or any other 13 numbers; and it can have been trained against features an
older engine extracted differently. ONNX Runtime will happily load and score all of those.

That is the problem this contract solves. Every mis-wiring listed above produces a bot that starts,
logs nothing, and plays worse than it measured — the most expensive kind of defect this project can
ship, because it is invisible until a ladder result contradicts an offline metric. The engine
therefore refuses to serve a model that does not describe itself, and the manifest is checked before
any position reaches it.

The contract is shared with `dicechess-training` (which writes the manifests) and with the private
evaluation service (which serves the `kcp-13` position model today), so one artifact describes
itself identically wherever it is loaded.

---

## A model package

Two files, the layout a trained candidate is published in:

```text
<package>/
  model.onnx      # the graph
  manifest.json   # what it is, what it eats, which engines may serve it
```

`ModelPackage.load(directory, engineVersion)` reads both; `ModelPackage.loadFiles(model, manifest,
engineVersion)` takes two arbitrary paths for an arena run or a test fixture.

---

## Manifest

| Field | Required | Meaning |
| --- | --- | --- |
| `manifestVersion` | yes | `1.0.0` or `1.1.0` — see below |
| `modelId` | yes | non-blank identifier of the artifact, for logs and provenance |
| `modelSha256` | yes | 64 hex characters over `model.onnx`'s bytes |
| `modelRole` | 1.1.0 | one of the roles below; a `1.0.0` manifest is read as `position-value` |
| `featureSchema` | yes | id of one of the feature schemas below |
| `featureCount` | yes | input width, which must equal that schema's column count |
| `inputName` / `outputName` | no | tensor names, default `input` / `output` |
| `perspective` | 1.1.0 | `side-to-move`; a `1.0.0` manifest is read as `side-to-move` |
| `engineCompatibility` | yes | comparator set that must include the serving engine's version |
| `evaluationProfile` | no | the evaluation service's profile id; recorded, never interpreted here |
| `calibration` | no | `temperature`, `brierScore`, `logLoss`, `calibratedOn` — advisory |
| `provenance` | no | free-form producer metadata (training run, dataset digest, commit) |

Unknown fields are ignored, so a producer can add metadata without breaking older engines. A field the *declared* version does not
define is the opposite case and is refused: a `1.0.0` manifest carrying `modelRole` would mean one thing here
and be ignored by every reader that only knows `1.0.0` — one file, two meanings.

### Versions

**`1.0.0`** is what the evaluation service and the `kcp-13` serving contract already emit. It has no
role, perspective or tensor-name fields, and the engine reads such a manifest as exactly what those
artifacts are: a `position-value` model on the mover perspective with `input`/`output` tensors.

**`1.1.0`** adds `modelRole` and `perspective` as required fields and `inputName`/`outputName` as
optional ones. A model for the search's chance-collapse or pre-ranking hooks can only be described by
`1.1.0`, because `1.0.0` cannot say which of them it is. A `1.1.0` manifest that omits a field it
requires is rejected rather than defaulted — the defaults exist to read legacy artifacts, not to
guess at new ones.

### Engine compatibility

`engineCompatibility` is a space-separated set of `MAJOR.MINOR.PATCH` comparators (`=`, `>`, `>=`,
`<`, `<=`) with AND semantics; a bare version means equality:

```text
">=0.12.0 <1.0.0"   # any 0.12+ engine below 1.0.0
"0.12.0"            # exactly this engine
```

The grammar is the evaluation service's, mirrored in the training repository's Python contract and
here — three implementations that must agree on every string, which is why pre-release suffixes are
rejected rather than tolerated in any of them.

The engine version is supplied **by the host**, not discovered: a library cannot read its own version
reliably (there is no jar manifest on an sbt or test classpath), and a guess would turn a
compatibility gate into a coin flip. A host running a `-SNAPSHOT` build passes the release it derives
from.

---

## Roles

| Role id | Question the model answers | Input object |
| --- | --- | --- |
| `position-value` | P(the evaluated color wins) | a position whose next roll is unknown |
| `chance-collapse` | the expectation a chance node would compute | the position a turn reaches, before the next roll |
| `move-prerank` | a score per candidate turn, used for ordering only | a candidate turn's resulting position |

A `chance-collapse` model is consumed by the search's collapse hook and a `position-value` model by its leaf
evaluator or root rescorer — see
[Production Hooks](/dicechess-engine/architecture/search/07-onnx-integration/#production-hooks).

The roles are not interchangeable even at equal tensor width: `position-value` is a probability,
`chance-collapse` is an expectation trained against a deeper search rather than against game
outcomes, and `move-prerank` is consumed as an ordering and never compared against a value model's
score. A caller states the role it is about to wire, and an artifact trained for a different question
is refused.

---

## Feature schemas

Every schema id resolves to the engine's own extractor — the same code the training enrichment calls,
so `train == serve` holds by construction instead of by a Python reimplementation.

| Schema id | Columns | Extractor |
| --- | --- | --- |
| `material-7-v1` | 7 | `OnnxFeatures` — piece-count and material differences |
| `rich-9-v1` | 9 | `RichFeatures` — material plus `mobility_diff`, `king_safety_diff` |
| `rich-pdi-11-v1` | 11 | `RichPdiFeatures` — rich plus own/opponent piece-diversity indices |
| `kcp-13` | 13 | `KcpFeatures` — rich plus king/queen capture probabilities |
| `kcp-mobility-27-v1` | 27 | `KcpMobilityFeatures` — kcp plus per-piece-type move counts and PDI |
| `kcp-mobility-pawns-31-v1` | 31 | `KcpMobilityPawnsFeatures` — above plus passed-pawn columns |
| `raw-board-768-v1` | 768 | `RawBoardFeatures` — 12 piece planes × 64 squares |

All of them are **mover-perspective and dice-free**: `extract(state, color)` is a function of the
position and the color being evaluated, independent of which side is to move and of the current dice
pool. The engine always passes the color it wants a score for, which is why it never performs the
`1 − p` flip the evaluation service's turn analysis needs (that service extracts for the position's
active color instead). Same vectors, same convention, one fewer transformation.

The capture-probability columns are the expensive ones: each is a 216-outcome search over the next
roll, which is why `kcp-13` and its supersets belong in one-ply and root-level roles rather than
under a chance node.

`kcp-13` keeps its unversioned id because that is the id the deployed service and the training
contract already use; re-spelling it here would fork the contract instead of sharing it.

---

## Tensor contract

A conforming graph exposes exactly one input and one output:

| | Name | Type | Shape |
| --- | --- | --- | --- |
| input | `inputName` (default `input`) | FLOAT | `[batch, featureCount]`, dynamic batch |
| output | `outputName` (default `output`) | FLOAT | `[batch, 1]`, dynamic batch |

A fixed batch dimension is rejected even when it is 1: the search scores a whole chance node in one
call, so a graph that accepts one row per run would fail at the first batch — at run time, under a
deadline, instead of at load time.

Names matter for a second reason: the engine feeds a session **by the name the graph declares** and
reads the first output **by position**. A graph whose output is named something the manifest never
mentions is therefore still scored, which is exactly how a Python exporter and this JVM loader can
disagree without either one failing. The repository's own older fixtures are that case: they name
their output `variable` while their manifests leave `outputName` at the default, and the contract
check rejects the mismatch by name instead of scoring whatever tensor comes first. A graph that
declares `outputName: "variable"` is a different matter and is served — the rule is agreement between
manifest and graph, not a list of blessed names.

---

## Failure behaviour

Everything is fail-closed and reported as a message, never as an exception, because a host wiring a
model has exactly one decision to make: serve it or refuse it.

Checked at load, before a session exists:

1. the model file is readable;
2. the manifest parses and its version is supported;
3. required fields are present, non-blank, and of the right type;
4. `modelRole` and `featureSchema` resolve, and `featureCount` matches the schema;
5. `perspective` is supported;
6. `engineCompatibility` includes the host's engine version;
7. the requested role matches the declared role;
8. the model file's SHA-256 matches `modelSha256`.

The order is deliberate — the cheapest checks and the ones with the clearest messages run first, and
the digest is verified before a graph is opened, so a model that is simply not the model the manifest
describes never gets loaded at all.

Checked once a session exists: the graph's input and output names, dtypes and shapes
(`ModelPackage.validateSession`). **That check is not yet on the serving path**, and the asymmetry is
worth stating plainly rather than leaving a reader to assume it. `ModelPackage.load` never opens a
graph, and every bot here — `OnnxEvalSearch` included — creates its session privately from a path. So
a host that loads a package and wires it into a bot today gets checks 1–8 and nothing more: a graph
that contradicts its own manifest is accepted at load and fails inside the first `session.run`, under
a move deadline, with the runtime's message instead of the contract's. Running the graph check is
currently the host's call, on a session the host owns;
[#258](https://github.com/fortemate/dicechess-engine/issues/258) moves it into session construction so
that no serving path can skip it.

---

## Loading a package

```scala
import dicechess.engine.model.{ModelPackage, ModelRole}
import dicechess.engine.search.OnnxEvalSearch
import java.nio.file.Path
import scala.util.Using

val loaded = ModelPackage.load(
  directory = Path.of("/models/kcp13-candidate"),
  engineVersion = "0.12.0",
  expectedRole = Some(ModelRole.PositionValue)
)

loaded match
  case Left(error)    => println(s"refusing to serve: $error")
  case Right(pkg) =>
    // The extractor comes from the manifest's schema, not from the call site. Note what this does
    // NOT do: OnnxEvalSearch opens its own private session and never runs the graph check, so the
    // tensor contract is unverified here — see Failure behaviour above, and #258.
    Using.resource(new OnnxEvalSearch(pkg.modelPath.toString, pkg.extract)) { bot =>
      // ...
    }
```

---

## Keeping the three sides in agreement

- The JVM fixtures under `jvm/src/test/resources/` are generated by
  `generate_model_contract_fixtures.py`, which writes a conforming model, its manifests, and the
  outputs Python's ONNX Runtime produces for a set of probe vectors.
- `Kcp13ParitySpec` then asserts both halves from the engine side: that `KcpFeatures` still extracts
  the exact vectors the shared golden corpus recorded, and that ONNX Runtime on the JVM reproduces
  Python's outputs for them.
- Regenerate the fixtures when the feature contract changes on purpose — never to make a red suite
  green. A drifted vector is the signal that a trained model's inputs no longer mean what they meant
  when it was fitted.

---

## See Also

- [ONNX Model Integration](/dicechess-engine/architecture/search/07-onnx-integration/) — the bots that consume a model
- [Expectimax Search Engine](/dicechess-engine/architecture/search/06-expectimax-search/) — where a leaf evaluator is called
- [Mobility & Passed Pawns](/dicechess-engine/architecture/search/09-mobility-and-passed-pawns/) — the newest feature schemas
