/*
 *  Launcher.scala
 *  (Mellite-launcher)
 *
 *  Copyright (c) 2020-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite

import coursier._
import coursier.cache.{CacheLogger, FileCache}
import coursier.core.Version
import coursier.util.Task
import net.harawata.appdirs.AppDirsFactory

import java.awt.EventQueue
import java.io.{File, FileInputStream, FileOutputStream}
import java.util.{Properties => JProperties}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

object Launcher {
  def name    : String = buildInfString("name" )
  def version : String = buildInfString("version" )
  def fullName: String = s"$name v$version"

  private def buildInfString(key: String): String = try {
    val clazz = Class.forName("de.sciss.mellite.LauncherInfo")
    val m     = clazz.getMethod(key)
    m.invoke(null).toString
  } catch {
    case NonFatal(_) => "?"
  }

  // eventually these could become settings of a generic launcher
  final val groupId         = "de.sciss"
  final val artifactId      = "mellite-app_2.13"
  final val mainClass       = "de.sciss.mellite.Mellite"
  final val classPathFilter = "/org.openjfx" :: Nil

  /** Minimum Mellite version that understands the launcher port argument */
  final val Min_Mellite_LauncherArg = "3.5.0"

  final case class Config(headless: Boolean, verbose: Boolean, offline: Boolean, checkNow: Boolean,
                          selectVersion: Boolean,
                          cacheBase: String, dataBase: String, appArgs: Seq[String]) {
    if (verbose) {
      println(s"Cache path: $cacheBase")
      println(s"Data path : $dataBase" )
    }
    val cacheDir  = new java.io.File(cacheBase, "coursier")
    val artDir    = new java.io.File(dataBase , "coursier")
    val propF     = new java.io.File(dataBase , "launcher.properties")
  }

  object Installation {
    def read()(implicit cfg: Config): Installation = {
      val p = new JProperties()
      try {
        val fi = new FileInputStream(cfg.propF)
        try {
          p.load(fi)
        } finally {
          fi.close()
        }
      } catch {
        case NonFatal(_) => ()
      }
      val currentVersion  = p.getProperty(KeyAppVersion, "")
      val nextUpdateTime  = Option(p.getProperty(KeyNextUpdate)).flatMap(_.toLongOption)
        .getOrElse(System.currentTimeMillis())
      val lastUpdateTime  = if (currentVersion.isEmpty) Long.MinValue else
        Option(p.getProperty(KeyLastUpdate)).flatMap(_.toLongOption).getOrElse(Long.MinValue)

      def getJars(key: String) = {
        val v = p.getProperty(key)
        if (v == null) Nil else v.split(File.pathSeparatorChar).iterator.map(new File(_)).toSeq
      }

      val jars    = getJars(KeyJars)
      val oldJars = getJars(KeyOldJars)
      Installation(
        currentVersion  = currentVersion,
        lastUpdateTime  = lastUpdateTime,
        nextUpdateTime  = nextUpdateTime,
        jars            = jars,
        oldJars         = oldJars,
      )
    }

    def write(i: Installation)(implicit cfg: Config): Unit = {
      val p = new JProperties()

      def putJars(key: String, sq: Seq[File]): Unit =
        if (sq.isEmpty) p.remove(key) else p.put(key, sq.mkString(File.pathSeparator))

      try {
        p.put(KeyLauncherVersion, Launcher.version)
        p.put(KeyAppVersion, i.currentVersion)
        p.put(KeyLastUpdate, i.lastUpdateTime.toString)
        p.put(KeyNextUpdate, i.nextUpdateTime.toString)
        putJars(KeyJars   , i.jars    )
        putJars(KeyOldJars, i.oldJars )
        val fo = new FileOutputStream(cfg.propF)
        try {
          p.store(fo, "Mellite launcher")
        } finally {
          fo.close()
        }
      } catch {
        case NonFatal(_) => ()
      }
    }

    private final val KeyAppVersion       = "app-version"
    private final val KeyLastUpdate       = "last-update"
    private final val KeyNextUpdate       = "next-update"
    private final val KeyJars             = "jars"
    private final val KeyOldJars          = "old-jars"
    private final val KeyLauncherVersion  = "launcher-version"
  }

  /**
    *   @param  currentVersion    empty if undefined (then `lastUpdateTime` would be `LongMinValue` as well)
    *   @param  lastUpdateTime   `Long.MinValue` if fresh installation
    *   @param  nextUpdateTime   `Long.MaxValue` if disabled
    *   @param  oldJars           if non-empty, they should be deleted after the app process has started
    */
  final case class Installation(currentVersion: String, lastUpdateTime: Long, nextUpdateTime: Long,
                                jars: Seq[File], oldJars: Seq[File]) {
    def isInstalled: Boolean = lastUpdateTime > Long.MinValue

    def write()(implicit cfg: Config): Unit = Installation.write(this)
  }

  private val Switch_Verbose      = "--verbose"
  private val Switch_VerboseS     = "-V"
  private val Switch_Offline      = "--offline"
  private val Switch_CheckUpdate  = "--check-update"
  private val Switch_SelectVersion= "--select-version"
  private val Switch_Help         = "--help"

  private val Switch_Headless     = "--headless"
  private val Switch_HeadlessS    = "-h"

  private val removeAppArgs = Seq(Switch_Verbose, Switch_VerboseS, Switch_Offline, Switch_CheckUpdate,
    Switch_SelectVersion, Switch_Help)

  private def printHelp(): Unit = {
    println(
      s"""$fullName
        |
        |  $Switch_Verbose, $Switch_VerboseS    print verbose information during update.
        |  $Switch_Offline        do not check online for updates.
        |  $Switch_CheckUpdate   force update check.
        |  $Switch_SelectVersion force version selection (up- or downgrade).
        |  $Switch_Headless, $Switch_HeadlessS   headless mode (no GUI windows).
        |  $Switch_Help           print this information. Use twice to get Mellite application help.
        |
        |Any other arguments are passed on to the Mellite application.
        |""".stripMargin
    )
  }

  def main(args: Array[String]): Unit = {
    val help = args.contains(Switch_Help)
    if (help) {
      printHelp()
      if (args.count(_ == Switch_Help) == 1) {
        sys.exit(1)
      }
    }

    val headless  = args.exists(p => p == Switch_Headless || p == Switch_HeadlessS)
    val verbose   = args.exists(p => p == Switch_Verbose  || p == Switch_VerboseS)
    val force     = args.contains(Switch_CheckUpdate)
    val offline   = args.contains(Switch_Offline)
    val selVersion= args.contains(Switch_SelectVersion)
    val appDirs   = AppDirsFactory.getInstance
    val cacheBase = appDirs.getUserCacheDir ("mellite", /* version */ null, /* author */ "de.sciss")
    val dataBase  = appDirs.getUserDataDir  ("mellite", /* version */ null, /* author */ "de.sciss")
    val appArgs   = args.diff(removeAppArgs)
    implicit val cfg: Config = Config(headless = headless, verbose = verbose, offline = offline, checkNow = force,
      selectVersion = selVersion, cacheBase = cacheBase, dataBase = dataBase, appArgs = appArgs)
    val inst0     = Installation.read()

    runWith(inst0)
  }

  private def runWith(inst0: Installation)(implicit cfg: Config): Unit =
    if (cfg.headless) {
      implicit val r: Reporter = new ConsoleReporter
      run(inst0)
    } else {
      EventQueue.invokeLater { () =>
        implicit val r: Reporter = new Splash
        run(inst0)
      }
    }

  private val appMod = Module(Organization(groupId), ModuleName(artifactId))
  private val repos  = {
  //      if (cfg.offline) {
  //        // XXX TODO: is there a proper way to construct the cache directory for a given repository?
  //        val centralF    = new File(new File(new File(cacheDir, "https"), "repo1.maven.org"), "maven2")
  //        val centralURI  = centralF.toURI.toString
  ////      println(centralURI)
  ////      assert (centralF.isDirectory, centralF.toString)
  //        val centralCache = MavenRepository(centralURI)
  //        LocalRepositories.ivy2Local :: centralCache :: Nil
  //      } else {
    Resolve.defaultRepositories
    //      }
  }

  private def obtainVersions()
                            (implicit cfg: Config, cacheResolve: FileCache[Task]): Future[core.Versions] = {
    val versions = Versions(cacheResolve).withModule(appMod).withRepositories(repos)
    versions.result().future().map { res =>
      val vs = res.versions
      if (cfg.verbose) {
        println("------ Available Versions ------")
        val av = vs.available
        av.foreach(println)
        if (av.nonEmpty) println(s"Latest: ${vs.latest} - updated: ${vs.lastUpdated}")
      }
      vs
    }
  }

  private def deleteDirectory(d: File): Unit = {
    var done = Set.empty[File]
    def loop(child: File): Unit = if (!done.contains(child)) {
      done += child
      if (child.isDirectory) {
        val cc = child.listFiles()
        if (cc != null) cc.foreach(loop)
      }
      child.delete()
    }
    loop(d)
  }

  private def install(inst0: Installation, version: String)
                     (implicit r: Reporter, cfg: Config, cacheResolve: FileCache[Task]): Future[Installation] = {
    val futFetch: Future[Fetch.Result] = {
      r.version = s"version $version"
      r.status = "Resolving dependencies..."
      val appDep    = Dependency(appMod, version)
      val resolve   = Resolve(cacheResolve)
        .addDependencies(appDep).withRepositories(repos)
      val cacheArt  = cache.FileCache[Task](cfg.artDir)
      resolve.future().flatMap { resolution =>
        r.status = "Fetching libraries..."
        //        status = "Resolving artifacts..."
        val artifacts = Artifacts(cacheArt).withResolution(resolution)
        val dlLog = new CacheLogger {
          private val remain  = mutable.Set(resolution.artifacts().map(_.url): _*)
          private val size    = remain.size

          private def add(url: String): Unit = if (remain.remove(url)) {
            val done = size - remain.size
            val p = done.toFloat / size
            r.progress = p
          }

          override def foundLocally       (url: String)                   : Unit = add(url)
          override def downloadedArtifact (url: String, success: Boolean) : Unit = add(url)
        }
        val cacheArtL = cacheArt.withLogger(dlLog)
        val fetch = new Fetch(resolve, artifacts, None).withCache(cacheArtL)
        fetch.futureResult()
      }
    }

    val futFiles: Future[Seq[File]] = futFetch.map { fetched =>
      r.status = "Fetched libraries."
      if (cfg.verbose) {
        println("------ Artifacts ------")
        fetched.detailedArtifacts.foreach { case (dep, pub, art, f) =>
          println(s"DEP $dep | PUB $pub | ART $art | FILE $f")
        }
      }

      fetched.files
    }

    futFiles.map { jars =>
      val now = System.currentTimeMillis()
      inst0.copy(
        currentVersion  = version,
        lastUpdateTime  = now,
        nextUpdateTime  = if (inst0.nextUpdateTime == Long.MaxValue) Long.MaxValue else {
          now + 604800000L // (7 * 24 * 60 * 60 * 1000L) // roughly one week
        },
        jars            = jars,
        oldJars         = inst0.jars,
      )
    }
  }

  private def dialogNewerVersion(inst0: Installation, latest: String)
                                (implicit r: Reporter, cfg: Config,
                                 cacheResolve: FileCache[Task]): Future[Installation] = {
    val futOk = r.showConfirm(s"A new version $latest is available.\nDownload and install?", isYesNo = true)
    futOk.flatMap { update =>
      if (!update) Future.successful(inst0)
      else install(inst0, version = latest)
    }
  }

  private def dialogSelectVersion(inst0: Installation, versions: core.Versions)
                                (implicit r: Reporter, cfg: Config,
                                 cacheResolve: FileCache[Task]): Future[Option[String]] = {
    val default = if (versions.available.isEmpty) None else Some(versions.latest)
    r.showOptions(s"Select a version to install", versions.available, default = default)
  }

  private def messageNoNewVersion(inst0: Installation)(implicit r: Reporter): Future[Unit] = {
    if (inst0.isInstalled) r.showMessage("No new version found.", isError = false)
    else r.showMessage("Could not find any online version!", isError = true)
  }

  private def run(inst0: Installation)(implicit r: Reporter, cfg: Config): Unit = {
    import cfg._
    import r.status
//    cacheDir.mkdirs()
//    artDir  .mkdirs()

    val now = System.currentTimeMillis()

    implicit lazy val cacheResolve: FileCache[Task] = FileCache[Task](cacheDir)
      .withTtl(1.hour)  // XXX TODO which value here

    val shouldCheck = !inst0.isInstalled || cfg.checkNow || cfg.selectVersion || now >= inst0.nextUpdateTime

    def stickToOld: Future[Installation] =
      if (inst0.isInstalled) {
        val inst1 = inst0.copy(nextUpdateTime = now + 604800000L)
        Future.successful(inst1)
      }
      else Future.failed(new Exception(s"No version installed. Re-run with network enabled!"))

    val futInst: Future[Installation] = if (!offline && shouldCheck) {
      status = "Checking version..."

      val futVersions: Future[core.Versions] = obtainVersions()
      val futVersion: Future[Option[String]] = if (!cfg.selectVersion) {
        futVersions.map(vs => if (vs.available.isEmpty) None else Some(vs.latest))
      } else {
        futVersions.flatMap { vs =>
          dialogSelectVersion(inst0, vs)
        }
      }

      futVersion.transformWith {
        case Success(Some(v)) =>
          if (!inst0.isInstalled || cfg.selectVersion) install(inst0, version = v)
          else if (Version(inst0.currentVersion) >= Version(v)) {
            val info = if (cfg.checkNow) messageNoNewVersion(inst0) else Future.unit
            info.map(_ => inst0)
          }
          else dialogNewerVersion(inst0, v)

        case Success(None) => stickToOld

        case Failure(ex) =>
          if (inst0.isInstalled) Future.successful(inst0)
          else Future.failed(ex)
      }

    } else {
      stickToOld
    }

    val futProcess: Future[(Installation, Process)] = futInst.map { inst1 =>
      val addCP   = inst1.jars.iterator.map(_.getPath)
      val ph      = ProcessHandle.current()
      val pi      = ph.info()
      val cmd     = pi.command().get()
      val argsIn  = pi.arguments().get().toSeq

      if (verbose) {
        println(s"CMD      = '$cmd''")
        println(s"ARGS IN  = ${argsIn.mkString("'", "', '", "'")}")
      }

      val idxCP   = {
        var i         = argsIn.indexOf("-classpath")
        if (i < 0) i  = argsIn.indexOf("-cp")
          require (i >= 0)
        i + 1
      }
      val oldCP   = argsIn(idxCP).split(File.pathSeparatorChar)
      val keepCP  = oldCP.filter(jar => classPathFilter.exists(jar.contains))
      val newCP   = keepCP ++ addCP
      val clzSelf = {
        val s = Launcher.getClass.getName
        s.substring(0, s.length - 1)
      }
//      println(clzSelf)
      val idxSelf = argsIn.indexOf(clzSelf)
      assert (idxSelf > idxCP)
      val argsOut: Seq[String] = argsIn.take(idxSelf).patch(idxCP, newCP.mkString(File.pathSeparator) :: Nil, 1) ++
        (mainClass +: cfg.appArgs)

      if (verbose) {
        println(s"ARGS OUT = ${argsOut.mkString("'", "', '", "'")}")
      }

      val pb  = new ProcessBuilder(cmd +: argsOut: _*)
      pb.inheritIO()
      val p   = pb.start()

      val instOut = if (inst1 == inst0) inst0 else {
        val inst2 = if (inst1.oldJars.isEmpty) inst1 else {
          // println(s"OLD JARS ${inst1.oldJars.size}")
          // println(inst1.oldJars.mkString("\n"))
          val oldDirs = inst1.oldJars.flatMap(f => Option(f.getParentFile)).toSet
          // println("OLD DIRS")
          // println(oldDirs.mkString("\n"))
          val newDirs = inst1.jars   .flatMap(f => Option(f.getParentFile)).toSet
          val delDirs = oldDirs -- newDirs
          delDirs.foreach { d =>
            if (verbose) println(s"DELETE $d")
            deleteDirectory(d)
          }
          inst1.copy(oldJars = Nil)
        }
        if (verbose) {
          println("WRITE PROPERTIES")
        }
        inst2.write()
        inst2
      }

      (instOut, p)
    }

    futProcess.onComplete {
      case Failure(ex) =>
        val m = ex.getMessage
        status = if (m == null) "Failed!" else s"Error: $m"
        ex.printStackTrace()

      case Success((_ /*instOut*/, p)) =>
        val alive = new Thread(() => {
//          Thread.sleep(2000)
          r.dispose()
          waitFor(p, verbose = verbose)
        })
        alive.setDaemon(false)
        alive.start()
    }
  }

  private def waitFor(p: Process, verbose: Boolean)(implicit cfg: Config): Unit = {
    val code = p.waitFor()  // join child process
    if (verbose) {
      println(s"EXIT CODE $code")
    }
    if (code == 82 /* 'R' */) {
      ??? // runWith()
    } else {
      sys.exit(code)
    }
  }
}
