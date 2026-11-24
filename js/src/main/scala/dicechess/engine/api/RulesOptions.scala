// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.api

import dicechess.engine.domain.RulesConfig
import scala.scalajs.js

private[api] object RulesOptions:

  def resolve(options: js.UndefOr[js.Any]): RulesConfig =
    if js.isUndefined(options) then RulesConfig.Standard
    else
      val value = options.get
      if js.typeOf(value) != "object" || Option(value).isEmpty then invalid("Rules options must be a plain object")
      val prototype = js.Object.getPrototypeOf(value.asInstanceOf[js.Object])
      if Option(prototype).nonEmpty && !js.special.strictEquals(prototype, js.Dynamic.global.Object.prototype) then
        invalid("Rules options must be a plain object")

      val obj        = value.asInstanceOf[js.Dynamic]
      val keys       = js.Dynamic.global.Reflect.ownKeys(obj).asInstanceOf[js.Array[js.Any]]
      var profile    = "dicechesscom"
      var layout     = "classical"
      var rollPolicy = "standard"
      keys.foreach: key =>
        if js.typeOf(key) != "string" then invalid("Unknown rules option")
        key.asInstanceOf[String] match
          case "profile"    => profile = stringOption(obj, "profile", profile)
          case "layout"     => layout = stringOption(obj, "layout", layout)
          case "rollPolicy" => rollPolicy = stringOption(obj, "rollPolicy", rollPolicy)
          case other        => invalid(s"Unknown rules option: $other")
      RulesConfig.parse(profile, layout, rollPolicy).fold(invalid, identity)

  private def stringOption(obj: js.Dynamic, key: String, default: String): String =
    val value = obj.selectDynamic(key)
    if js.isUndefined(value) then default
    else if js.typeOf(value) == "string" then value.asInstanceOf[String]
    else invalid(s"Rules option $key must be a string")

  private def invalid(message: String): Nothing = js.special.`throw`(new js.TypeError(message))
