lazy val baseName = "Mellite-launcher"

lazy val deps = new {
  val main = new {
    val appDirs   = "1.2.0"
    val coursier  = "2.0.7"
    val slf4j     = "1.7.30"
  }
}

lazy val root = project.in(file("."))
  .settings(
    name          := baseName,
    version       := "0.1.0-SNAPSHOT",
    scalaVersion  := "2.13.4",
    organization  := "de.sciss",
    homepage      := Some(url(s"https://git.iem.at/$baseName")),
    description   := "Application launcher and updater for Mellite",
    licenses      := Seq("AGPL v3+" -> url("http://www.gnu.org/licenses/agpl-3.0.txt")),
    libraryDependencies ++= Seq(
      "io.get-coursier" %% "coursier"     % deps.main.coursier,   // retrieving dependencies
      "net.harawata"    %  "appdirs"      % deps.main.appDirs,    // finding cache directory
      "org.slf4j"       %  "slf4j-api"    % deps.main.slf4j,      // logging (used by coursier)
      "org.slf4j"       %  "slf4j-simple" % deps.main.slf4j,      // logging (used by coursier)
    )
  )
