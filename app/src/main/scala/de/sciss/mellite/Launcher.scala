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
import java.io.{File, FileInputStream}
import java.util.{Properties => JProperties}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

object Launcher {
  // eventually these could become settings of a generic launcher
  final val groupId         = "de.sciss"
  final val artifactId      = "mellite-app_2.13"
  final val mainClass       = "de.sciss.mellite.Mellite"
  final val classPathFilter = "/org.openjfx" :: Nil

  /** Minimum Mellite version that understands the launcher port argument */
  final val Min_Mellite_LauncherArg = "3.5.0"

  final case class Config(headless: Boolean, verbose: Boolean, offline: Boolean, force: Boolean,
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

      def getJars(key: String) =
        p.getProperty(key, "").split(File.pathSeparatorChar).iterator.map(new File(_)).toSeq

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

    private final val KeyAppVersion = "app-version"
    private final val KeyLastUpdate = "last-update"
    private final val KeyNextUpdate = "next-update"
    private final val KeyJars       = "jars"
    private final val KeyOldJars    = "old-jars"
  }

  /**
    *   @param  currentVersion    empty if undefined (then `lastUpdateTime` would be `LongMinValue` as well)
    *   @param  lastUpdateTime   `Long.MinValue` if fresh installation
    *   @param  nextUpdateTime   `Long.MaxValue` if disabled
    *   @param  oldJars           if non-empty, they should be deleted after the app process has started
    */
  final case class Installation(currentVersion: String, lastUpdateTime: Long, nextUpdateTime: Long,
                                jars: Seq[File], oldJars: Seq[File]) {
//    def write(): Unit

    def isInstalled: Boolean = lastUpdateTime > Long.MinValue
  }

  private val removeAppArgs = Seq("--verbose", "-V", "--offline", "--check-update")

  def main(args: Array[String]): Unit = {
    val headless  = args.exists(p => p == "--headless" || p == "-h")
    val verbose   = args.exists(p => p == "--verbose"  || p == "-V")
    val force     = args.contains("--check-update")
    val offline   = args.contains("--offline")
    val appDirs   = AppDirsFactory.getInstance
    val cacheBase = appDirs.getUserCacheDir ("mellite", /* version */ null, /* author */ "de.sciss")
    val dataBase  = appDirs.getUserDataDir  ("mellite", /* version */ null, /* author */ "de.sciss")
    val appArgs   = args.diff(removeAppArgs)
    implicit val cfg: Config = Config(headless = headless, verbose = verbose, offline = offline, force = force,
      cacheBase = cacheBase, dataBase = dataBase, appArgs = appArgs)
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
                            (implicit cacheResolve: FileCache[Task]): Future[core.Versions] = {
    val versions = Versions(cacheResolve).withModule(appMod).withRepositories(repos)
    versions.result().future().map(_.versions)
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
      inst0.copy(
        currentVersion  = version,
        lastUpdateTime  = System.currentTimeMillis(),
        nextUpdateTime  = if (inst0.nextUpdateTime == Long.MaxValue) Long.MaxValue else {
          inst0.nextUpdateTime + 604800000L // (7 * 24 * 60 * 60 * 1000L) // roughly one week
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

    val shouldCheck = !inst0.isInstalled || cfg.force || now >= inst0.nextUpdateTime
    val futInst: Future[Installation] = if (!offline && shouldCheck) {
      status = "Checking version..."

      val futLatest: Future[String] = obtainVersions().map { vs =>
        if (verbose) {
          println("------ Available Versions ------")
          val av = vs.available
          av.foreach(println)
          if (av.nonEmpty) println(s"Latest: ${vs.latest} - updated: ${vs.lastUpdated}")
        }
        vs.latest
      }

      futLatest.transformWith {
        case Success(latest) =>
          if (!inst0.isInstalled) install(inst0, version = latest)
          else if (Version(inst0.currentVersion) >= Version(latest)) {
            val info = if (cfg.force) messageNoNewVersion(inst0) else Future.unit
            info.map(_ => inst0)
          }
          else dialogNewerVersion(inst0, latest)

        case Failure(ex) =>
          if (inst0.isInstalled) Future.successful(inst0)
          else Future.failed(ex)
      }

    } else {
      if (inst0.isInstalled) Future.successful(inst0)
      else Future.failed(new Exception(s"No version installed. Re-run with network enabled!"))
    }

    val futProcess: Future[Process] = futInst.map { inst1 =>
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
      p
    }

    futProcess.onComplete {
      case Failure(ex) =>
        val m = ex.getMessage
        status = if (m == null) "Failed!" else s"Error: $m"
        ex.printStackTrace()

      case Success(p) =>
        val alive = new Thread(() => {
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
