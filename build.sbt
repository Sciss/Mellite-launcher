import com.typesafe.sbt.packager.linux.LinuxPackageMapping

lazy val baseName           = "Mellite-launcher"
lazy val baseNameL          = baseName.toLowerCase()
lazy val projectVersion     = "0.1.0-SNAPSHOT"
lazy val launcherMainClass  = "de.sciss.mellite.Launcher"
lazy val appDescription     = "An environment for creating experimental computer-based music and sound art"
lazy val authorName         = "Hanns Holger Rutz"
lazy val authorEMail        = "contact@sciss.de"

def appName     = baseName
def appNameL    = baseNameL
def appVersion  = projectVersion

lazy val deps = new {
  val main = new {
    val appDirs   = "1.2.1"
    val coursier  = "2.0.16"
//    val scallop   = "4.0.2"
    val scalaOSC  = "1.3.1"
    val slf4j     = "1.7.30"
  }
}

lazy val commonSettings = Seq(
  version       := projectVersion,
  scalaVersion  := "2.13.5",
  organization  := "de.sciss",
  homepage      := Some(url(s"https://git.iem.at/$baseName")),
  description   := "Application launcher and updater for Mellite",
  licenses      := Seq("AGPL v3+" -> url("http://www.gnu.org/licenses/agpl-3.0.txt")),
  scalacOptions ++= Seq(
    "-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xlint:-stars-align,_", "-Xsource:2.13"
  ),
  scalacOptions /* in (Compile, compile) */ ++= {
    val sq0 = if (scala.util.Properties.isJavaAtLeast("9")) List("-release", "9") else Nil // we use java.lang.ProcessHandle
    val sq1 = if (VersionNumber(scalaVersion.value).matchesSemVer(SemanticSelector(">=2.13"))) "-Wconf:cat=deprecation&msg=Widening conversion:s" :: sq0 else sq0 // nanny state defaults :-E
    sq1
  },
  javacOptions ++= Seq("-source", "1.9", "-target", "1.9"),
  updateOptions := updateOptions.value.withLatestSnapshots(false),
//  aggregate in assembly := false,
)

lazy val root = project.in(file("."))
  .aggregate(app, full)
  .dependsOn(app)
  .settings(commonSettings)
  .settings(
    name    := baseName,
    version := appVersion,
    publishArtifact in(Compile, packageBin) := false, // there are no binaries
    publishArtifact in(Compile, packageDoc) := false, // there are no javadocs
    publishArtifact in(Compile, packageSrc) := false, // there are no sources
    // packagedArtifacts := Map.empty
    autoScalaLibrary := false
  )

lazy val appSettings = Seq(
  description := appDescription,
  mainClass in Compile := Some(launcherMainClass),
)

lazy val app = project.in(file("app"))
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging, DebianPlugin)
  .settings(commonSettings)
  .settings(pkgUniversalSettings)
  .settings(pkgDebianSettings)
  .settings(useNativeZip) // cf. https://github.com/sbt/sbt-native-packager/issues/334
//  .settings(assemblySettings)
  .settings(appSettings)
  .settings(
    name          := s"$baseName-app",  // must be different from baseName, otherwise root project overwrites jar
    libraryDependencies ++= Seq(
      "de.sciss"        %% "scalaosc"     % deps.main.scalaOSC,   // open sound control
      "io.get-coursier" %% "coursier"     % deps.main.coursier,   // retrieving dependencies
      "net.harawata"    %  "appdirs"      % deps.main.appDirs,    // finding cache directory
//      "org.rogach"      %% "scallop"      % deps.main.scallop,    // command line option parsing
      "org.slf4j"       %  "slf4j-api"    % deps.main.slf4j,      // logging (used by coursier)
      "org.slf4j"       %  "slf4j-simple" % deps.main.slf4j,      // logging (used by coursier)
    ),
    // ---- build-info ----
    buildInfoKeys     := Seq("name" -> baseName /* name */, version, scalaVersion),
    buildInfoPackage  := "de.sciss.mellite",
    buildInfoObject   := "LauncherInfo",
    // ---- packaging ----
    packageName in Universal := s"${appNameL}_${version.value}_all",
    name                      in Debian := appNameL,  // this is used for .deb file-name; NOT appName,
    debianPackageDependencies in Debian ++= Seq("java11-runtime"),
    debianPackageRecommends   in Debian ++= Seq("openjfx"), // you could run without, just the API browser won't work
  )

// Determine OS version of JavaFX binaries
lazy val jfxClassifier = sys.props("os.name") match {
  case n if n.startsWith("Linux")   => "linux"
  case n if n.startsWith("Mac")     => "mac"
  case n if n.startsWith("Windows") => "win"
  case _ => throw new Exception("Unknown platform!")
}

def jfxDep(name: String): ModuleID =
  "org.openjfx" % s"javafx-$name" % "11.0.2" classifier jfxClassifier

def archSuffix: String =
  sys.props("os.arch") match {
    case "i386"  | "x86_32" => "x32"
    case "amd64" | "x86_64" => "x64"
    case other              => other
  }

lazy val full = project.in(file("full"))
  .dependsOn(app)
  .enablePlugins(JavaAppPackaging, DebianPlugin, JlinkPlugin)
  .settings(commonSettings)
  .settings(pkgUniversalSettings)
  .settings(pkgDebianSettings)
//  .settings(assemblySettings) // do we need this here?
  .settings(appSettings)
  .settings(
    name    := baseName,
    version := appVersion,
    jlinkIgnoreMissingDependency := JlinkIgnore.everything, // temporary for testing
    jlinkModules ++= Seq(
      "jdk.unsupported", // needed for Akka
      "java.management",
    ),
    libraryDependencies ++= Seq("base", "swing", "controls", "graphics", "media", "web").map(jfxDep),
    packageName in Universal := s"${appNameL}_${version.value}_${jfxClassifier}_$archSuffix",
    name                in Debian := s"$appNameL",  // this is used for .deb file-name; NOT appName,
    packageArchitecture in Debian := sys.props("os.arch"), // archSuffix,
  )

// ---- packaging ----

////////////////// fat-jar assembly
//lazy val assemblySettings = Seq(
//  mainClass             in assembly := Some(launcherMainClass),
//  target                in assembly := baseDirectory.value,
//  assemblyJarName       in assembly := s"$baseName.jar",
//  assemblyMergeStrategy in assembly := {
//    case PathList("org", "xmlpull", _ @ _*) => MergeStrategy.first
//    case PathList("org", "w3c", "dom", "events", _ @ _*) => MergeStrategy.first // bloody Apache Batik
//    case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
//    case x =>
//      val oldStrategy = (assemblyMergeStrategy in assembly).value
//      oldStrategy(x)
//  }
//)

//////////////// universal (directory) installer
lazy val pkgUniversalSettings = Seq(
  executableScriptName /* in Universal */ := appNameL,
  // note: do not use wildcard script-classpath, as
  // we need to be able to filter for openjfx jars
//  scriptClasspath /* in Universal */ := Seq("*"),
  name                      in Linux     := appName,
  packageName               in Linux     := appNameL, // XXX TODO -- what was this for?
//  mainClass                 in Universal := Some(launcherMainClass),
  maintainer                in Universal := s"$authorName <$authorEMail>",
  target      in Universal := (target in Compile).value,
)

//////////////// debian installer
lazy val pkgDebianSettings = Seq(
  packageName               in Debian := appNameL,  // this is the installed package (e.g. in `apt remove <name>`).
  packageSummary            in Debian := appDescription,
//  mainClass                 in Debian := Some(launcherMainClass),
  maintainer                in Debian := s"$authorName <$authorEMail>",
  packageDescription        in Debian :=
    """Mellite is a computer music environment,
      | a desktop application based on SoundProcesses.
      | It manages workspaces of musical objects, including
      | sound processes, timelines, code fragments, or
      | live improvisation sets.
      |""".stripMargin,
  // include all files in src/debian in the installed base directory
  linuxPackageMappings      in Debian ++= {
    val n     = appNameL // (name in Debian).value.toLowerCase
    val dir   = (sourceDirectory in Debian).value / "debian"
    val f1    = (dir * "*").filter(_.isFile).get  // direct child files inside `debian` folder
    val f2    = ((dir / "doc") * "*").get
    //
    def readOnly(in: LinuxPackageMapping) =
      in.withUser ("root")
        .withGroup("root")
        .withPerms("0644")  // http://help.unc.edu/help/how-to-use-unix-and-linux-file-permissions/
    //
    val aux   = f1.map { fIn => packageMapping(fIn -> s"/usr/share/$n/${fIn.name}") }
    val doc   = f2.map { fIn => packageMapping(fIn -> s"/usr/share/doc/$n/${fIn.name}") }
    (aux ++ doc).map(readOnly)
  }
)
