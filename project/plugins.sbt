addSbtPlugin("org.scoverage"      % "sbt-scoverage"  % "2.4.4")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"   % "2.6.2")
addSbtPlugin("ch.epfl.scala"      % "sbt-scalafix"   % "0.14.7")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"    % "1.22.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh"        % "0.4.8")
// sbt-ci-release bundles sbt-pgp (for signing) and sbt-sonatype (for Maven Central staging).
// The central.sonatype.com credential host (set in build.sbt) selects the new Central Portal.
addSbtPlugin("com.github.sbt"     % "sbt-ci-release" % "1.11.1")
// Note: sbt-projectmatrix is built into sbt 2 core (see build.sbt).
