package dicechess.engine.bench

/** Which search an arena runner puts over an ONNX model.
  *
  * A type rather than a `String` so the invalid case cannot be constructed: the runners resolve one of these once, at
  * argument-parsing time, and everything downstream matches exhaustively instead of carrying a defensive "unknown
  * search" branch that no caller can reach. Shaped after `TimePolicies` in the engine (`id`, `available`, `get`), which
  * the sibling `--challenger-time-policy` option already resolves the same way.
  */
enum SearchKind(val id: String) derives CanEqual:

  /** One ply over the model — the only viable depth for evaluators whose per-position cost is high. */
  case OnePly extends SearchKind("oneply")

  /** Two-ply expectimax; the only kind that reads [[dicechess.engine.search.ExpectimaxConfig]]. */
  case Expectimax extends SearchKind("expectimax")

object SearchKind:

  /** In help-text order, with the default first. */
  val available: List[SearchKind] = List(Expectimax, OnePly)

  val default: SearchKind = Expectimax

  /** Resolves an id case-insensitively. */
  def get(id: String): Option[SearchKind] = available.find(_.id.equalsIgnoreCase(id))
