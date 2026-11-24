// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

/** The immutable move and roll rules selected at game creation. */
final class RulesConfig private (
    val profileId: String,
    val layoutId: String,
    private val rollPolicy: RulesConfig.RollPolicy,
    val revision: Int
) derives CanEqual:
  import RulesConfig.RollPolicy

  val rollPolicyId: String = rollPolicy.id
  val isChess960: Boolean  = layoutId == RulesConfig.Chess960Layout

  def requiredRandomDice(firstTurnForPlayer: Boolean): Int =
    rollPolicy match
      case RollPolicy.Standard                          => 3
      case RollPolicy.OpeningPawn if firstTurnForPlayer => 2
      case RollPolicy.OpeningPawn                       => 3
      case RollPolicy.Choice                            => 4

  /** The caller supplies the random faces in order and tracks whether this is the active player's first turn. */
  def prepareRoll(randomFaces: List[Int], firstTurnForPlayer: Boolean): Either[String, PreparedRoll] =
    val requiredFaces = requiredRandomDice(firstTurnForPlayer)
    if randomFaces.lengthCompare(requiredFaces) != 0 then
      Left(s"$rollPolicyId requires $requiredFaces random dice for this turn")
    else if !randomFaces.forall(face => face >= 1 && face <= 6) then Left("Die faces must be in 1..6")
    else
      rollPolicy match
        case RollPolicy.OpeningPawn if firstTurnForPlayer =>
          Right(new PreparedRoll.Pool(PieceType.Pawn.diceValue :: randomFaces))
        case RollPolicy.Choice =>
          val (first, second) = randomFaces.splitAt(2)
          Right(new PreparedRoll.Offers(first, second))
        case _ => Right(new PreparedRoll.Pool(randomFaces))

object RulesConfig:
  private enum RollPolicy(val id: String):
    case Standard    extends RollPolicy("standard")
    case OpeningPawn extends RollPolicy("opening-pawn")
    case Choice      extends RollPolicy("choice-2x2d6")

  private val CanonicalProfile = "dicechesscom"
  private val ClassicalLayout  = "classical"
  private val Chess960Layout   = "chess960"

  val Standard: RulesConfig            = new RulesConfig(CanonicalProfile, ClassicalLayout, RollPolicy.Standard, 1)
  val Chess960: RulesConfig            = new RulesConfig(CanonicalProfile, Chess960Layout, RollPolicy.Standard, 1)
  val OpeningPawn: RulesConfig         = new RulesConfig(CanonicalProfile, ClassicalLayout, RollPolicy.OpeningPawn, 1)
  val Chess960OpeningPawn: RulesConfig = new RulesConfig(CanonicalProfile, Chess960Layout, RollPolicy.OpeningPawn, 1)
  val FortemateChoice: RulesConfig     = new RulesConfig(CanonicalProfile, ClassicalLayout, RollPolicy.Choice, 1)

  private val presets = List(Standard, Chess960, OpeningPawn, Chess960OpeningPawn, FortemateChoice)

  def parse(profile: String, layout: String, rollPolicy: String): Either[String, RulesConfig] =
    presets
      .find(config => config.profileId == profile && config.layoutId == layout && config.rollPolicyId == rollPolicy)
      .toRight(s"Unsupported rules configuration (profile=$profile, layout=$layout, rollPolicy=$rollPolicy)")

  /** Java-callable factory that rejects unsupported configurations with IllegalArgumentException. */
  def of(profile: String, layout: String, rollPolicy: String): RulesConfig =
    parse(profile, layout, rollPolicy)
      .fold(msg => throw IllegalArgumentException(msg), identity) // scalafix:ok(DisableSyntax.throw)
