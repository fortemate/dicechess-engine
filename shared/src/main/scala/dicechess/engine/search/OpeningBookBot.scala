package dicechess.engine.search

import dicechess.engine.domain.*
import scala.util.Random

/** A decorator that consults an opening book before falling back to the wrapped algorithm.
  *
  * The `book` maps a canonical key (see [[OpeningBook.key]]) to the chosen continuation, a comma-separated list of
  * long-algebraic micro-moves (e.g. `"e2e4,f1c4"`). On a book-key hit, if any legal turn path captures the enemy king,
  * the decorator plays the shortest such immediate win path instead of the booked continuation. Otherwise, when the key
  * is present, it plays the booked turn instantly — without spending the underlying bot's search budget — which is
  * exactly where a heuristic or rollout-based engine is weakest and where empirical data is richest. In summary, the
  * precedence order is capture > book > underlying. When the position's key is absent from `book`, it delegates to
  * `underlying`.
  *
  * The booked moves are matched against the position's legal turn paths by move **multiset**, so the order stored in
  * the book does not matter and an entry that cannot be realised legally (a stale or corrupt book) is silently ignored
  * in favour of the underlying bot rather than played illegally.
  *
  * Construct via [[OpeningBookBot.decorate]], which preserves the underlying bot's time-budget capability: wrapping a
  * [[TimeBudgetedSearch]] yields a [[TimeBudgetedOpeningBookBot]] (the deadline reaches the underlying), while wrapping
  * a plain bot yields a plain decorator — so a host's `getBestMove` does not advertise a time budget for a bot that
  * ignores it.
  *
  * The decorator delegates all doubling and draw policy queries ([[shouldOfferDouble]], [[shouldAcceptDouble]],
  * [[shouldOfferDraw]], [[shouldAcceptDraw]]) unconditionally to `underlying`, preserving the wrapped bot's custom
  * policies, `DrawOfferLogic` mix-ins, and default `SearchAlgorithm` heuristics.
  *
  * @param underlying
  *   the bot to consult when the position is not booked
  * @param book
  *   the opening book, keyed per [[OpeningBook.key]]
  */
class OpeningBookBot(val underlying: SearchAlgorithm, val book: Map[String, String]) extends SearchAlgorithm:

  override def findBestMove(state: GameState): Option[ScoredSequence] =
    lookupMove(state).orElse(underlying.findBestMove(state))

  override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
    lookupMove(state).orElse(underlying.findBestMove(state, random))

  override def shouldOfferDouble(state: GameState, currentStake: Int): Boolean =
    underlying.shouldOfferDouble(state, currentStake)

  override def shouldAcceptDouble(state: GameState, currentStake: Int, responder: Color): Boolean =
    underlying.shouldAcceptDouble(state, currentStake, responder)

  override def shouldAcceptDouble(state: GameState, currentStake: Int): Boolean =
    underlying.shouldAcceptDouble(state, currentStake)

  override def shouldOfferDraw(state: GameState): Boolean =
    underlying.shouldOfferDraw(state)

  override def shouldAcceptDraw(state: GameState): Boolean =
    underlying.shouldAcceptDraw(state)

  /** Long-algebraic notation of a micro-move including any promotion suffix (e.g. `"e2e4"`, `"e7e8q"`). */
  private def uci(move: Move): String = move.toUci

  /** Multiset signature of a move sequence: its UCI tokens sorted and joined, so two orderings of the same turn compare
    * equal.
    */
  private def signature(moves: List[String]): String =
    moves.sorted.mkString(",")

  /** The booked turn for `state`, if the position is booked and the entry is a legal turn here. */
  protected def lookupMove(state: GameState): Option[ScoredSequence] =
    for
      key       <- OpeningBook.key(state)
      bookMoves <- book.get(key)
      allPaths     = TurnGenerator.generateAllLegalTurnPaths(state)
      capturePaths = allPaths.filter(p =>
        SearchScoring.scorePath(state, p, (_, _) => 0).score == SearchScoring.TerminalWinScore
      )
      move <-
        if capturePaths.nonEmpty then
          val shortestCapture = capturePaths.minBy(_.size)
          Some(ScoredSequence(shortestCapture, SearchScoring.TerminalWinScore))
        else
          val target = signature(bookMoves.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList)
          allPaths.find(p => signature(p.map(uci)) == target).map(p => SearchScoring.scorePath(state, p))
    yield move

/** The time-budgeted [[OpeningBookBot]], used when the wrapped bot is itself a [[TimeBudgetedSearch]] so the deadline
  * is forwarded on a book miss. Built by [[OpeningBookBot.decorate]].
  */
final class TimeBudgetedOpeningBookBot(
    tbUnderlying: SearchAlgorithm & TimeBudgetedSearch,
    book: Map[String, String]
) extends OpeningBookBot(tbUnderlying, book)
    with TimeBudgetedSearch:

  override def findBestMove(state: GameState, deadlineNanos: Long, random: Random): Option[ScoredSequence] =
    lookupMove(state).orElse(tbUnderlying.findBestMove(state, deadlineNanos, random))

object OpeningBookBot:

  /** Wraps `underlying` with `book`, preserving its time-budget capability: a [[TimeBudgetedSearch]] stays
    * time-budgeted, any other algorithm stays plain.
    */
  def decorate(underlying: SearchAlgorithm, book: Map[String, String]): SearchAlgorithm =
    underlying match
      case tb: TimeBudgetedSearch => new TimeBudgetedOpeningBookBot(tb, book)
      case _                      => new OpeningBookBot(underlying, book)
