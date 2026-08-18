package dicechess.engine.search

/** The variable part of [[TimeManager]]'s clock allocation.
  *
  * A policy estimates how many turns the moving side still has to budget for. [[TimeManager]] deliberately owns the
  * safety envelope around that estimate — reserve, hard cap, panic clamp, and caller overhead — so every policy gets
  * the same anti-flag guarantees.
  *
  * Policies are pure and platform-independent. Consumers choose one when they construct a [[TimeManager]]; deployment
  * configuration (environment variables, Worker bindings, command-line flags) belongs to the consumer rather than this
  * shared engine module.
  */
trait TimePolicy:

  /** Stable configuration/reporting identifier. Version policy ids when their behaviour changes materially. */
  def id: String

  /** Estimates the number of further turns to spread the spendable clock over.
    *
    * The estimate is fractional because empirical expected remaining turns and interpolation between observations are
    * not restricted to whole numbers.
    *
    * [[ClockState.movesToGo]] is an explicit time-control override and is applied by [[TimeManager]] before this
    * estimate is used. The manager also clamps the final value to at least one.
    */
  def estimateMovesToGo(clock: ClockState): Double

/** Built-in [[TimePolicy]] implementations and id-based lookup for external configuration. */
object TimePolicies:

  /** The original chess-inspired linear taper, retained as the compatibility baseline. */
  case object LegacyLinear extends TimePolicy:
    override val id: String = "legacy-linear-v1"

    override def estimateMovesToGo(clock: ClockState): Double =
      math.max(TimeManager.MinMovesToGo, TimeManager.BaseMovesToGo - clock.moveNumber).toDouble

  /** Dice Chess-specific conditional expectation of the moving player's remaining clock decisions.
    *
    * Derived on 2026-08-13 from the production analytics corpus: 1,459,949 king-captured games and 21,947,753 player
    * turns spanning 2024-01-03 through 2026-08-13. Analytics `turn_number` counts alternating player turns, while
    * [[ClockState.moveNumber]] is the DFEN full-move number shared by White and Black. The source aggregation therefore
    * converts every recorded turn to its player's ordinal and counts that player's current turn plus their later turns.
    *
    * The curve is intentionally non-monotone. Games surviving beyond roughly the tenth player turn are selected for
    * longer endgames, so expected remaining turns rise again. Values between measured knots are linearly interpolated;
    * values outside the measured range use the nearest endpoint. [[TimeManager]] applies its shared reserve, hard cap,
    * and panic clamp after this estimate.
    */
  case object EmpiricalV1 extends TimePolicy:
    override val id: String = "empirical-v1"

    private val Curve: Vector[(Int, Double)] = Vector(
      1  -> 7.517,
      3  -> 6.017,
      5  -> 5.471,
      8  -> 5.176,
      10 -> 5.136,
      15 -> 5.431,
      20 -> 6.185,
      30 -> 8.830,
      40 -> 12.050
    )

    override def estimateMovesToGo(clock: ClockState): Double =
      val turn = math.max(1, clock.moveNumber)
      if turn <= Curve.head._1 then Curve.head._2
      else if turn >= Curve.last._1 then Curve.last._2
      else
        val rightIndex                 = Curve.indexWhere((measuredTurn, _) => measuredTurn >= turn)
        val (leftTurn, leftEstimate)   = Curve(rightIndex - 1)
        val (rightTurn, rightEstimate) = Curve(rightIndex)
        val interpolationFraction      = (turn - leftTurn).toDouble / (rightTurn - leftTurn)
        leftEstimate + interpolationFraction * (rightEstimate - leftEstimate)

  /** Policy used by compatibility calls on the [[TimeManager]] companion. */
  val default: TimePolicy = EmpiricalV1

  /** All built-in policies, in presentation order. */
  val available: List[TimePolicy] = List(EmpiricalV1, LegacyLinear)

  /** Resolves a policy id case-insensitively. */
  def get(id: String): Option[TimePolicy] = available.find(_.id.equalsIgnoreCase(id))
