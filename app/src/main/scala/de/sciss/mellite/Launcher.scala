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
import de.sciss.osc
import net.harawata.appdirs.AppDirsFactory

import java.awt.EventQueue
import java.io.{File, FileInputStream, FileOutputStream}
import java.util.{Date, Properties => JProperties}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}
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
  private final val groupId         = "de.sciss"
  private final val appId           = "mellite"
  private final val artifactId      = "mellite-app_2.13"
  private final val mainClass       = "de.sciss.mellite.Mellite"
  private final val classPathFilter = "/org.openjfx" :: Nil

  private final val coursierDir     = "cs"
  private final val DefaultPrefix   = "default"
  private final val propFileName    = "launcher.properties"

  /** Minimum Mellite version that understands the arguments `--launcher` and `--prefix` */
  private final val Mellite_API1    = "3.4.99"

  final case class Config(headless: Boolean, verbose: Boolean, offline: Boolean, checkNow: Boolean,
                          selectVersion: Boolean, /*oscServer: Boolean,*/
                          cacheBase: File, dataBase: File, configBase: File,
                          prefix: String, appArgs: List[String]) {
    if (verbose) {
      println(s"Cache path : $cacheBase"  )
      println(s"Data path  : $dataBase"   )
      println(s"Config path: $configBase" )
    }
    val cacheDir  = new File(cacheBase  , coursierDir)  // always use the same cache
    val artDir    = new File(dataBase   , coursierDir)
    val propFile  = new File(configBase , propFileName)
  }

  object Installation {
    def read()(implicit cfg: Config): Installation =
      readProperties(cfg.propFile)

    def readProperties(f: File): Installation = {
      val p = new JProperties()
      try {
        val fi = new FileInputStream(f)
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
        cfg.propFile.getParentFile.mkdirs()
        val fo = new FileOutputStream(cfg.propFile)
        try {
          p.store(fo, "Mellite launcher")
        } finally {
          fo.close()
        }
      } catch {
        case NonFatal(ex) =>
          ex.printStackTrace()
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

    override def toString: String = {
      s"""$productPrefix(
         |  currentVersion = $currentVersion",
         |  lastUpdateTime = ${new Date(lastUpdateTime)}
         |  nextUpdateTime = ${new Date(nextUpdateTime)}
         |  #jars = ${jars.size},
         |  #oldJars = ${oldJars.size}
         |)
         |""".stripMargin
    }
  }

  private val Switch_Verbose      = "--verbose"
  private val Switch_VerboseS     = "-V"
  private val Switch_Offline      = "--offline"
  private val Switch_CheckUpdate  = "--check-update"
  private val Switch_SelectVersion= "--select-version"
//  private val Switch_NoServer     = "--no-server"
  private val Switch_Prefix       = "--prefix"
  private val Switch_Help         = "--help"
  private val Switch_List         = "--list"
  private val Switch_Remove       = "--remove"

  private val Switch_Headless     = "--headless"
  private val Switch_HeadlessS    = "-h"

  def printHelp(): Unit = {
    val nameArg = "<name>"
    println(
      //                    description
      // $Switch_NoServer      no OSC server, launcher quits upon application start.
      s"""$fullName
        |
        |  $Switch_Verbose, $Switch_VerboseS    print verbose information during update.
        |  $Switch_Offline        do not check online for updates.
        |  $Switch_CheckUpdate   force update check.
        |  $Switch_SelectVersion force version selection (up- or downgrade).
        |  $Switch_Headless, $Switch_HeadlessS   headless mode (no GUI windows). Passed on to the application.
        |  $Switch_Prefix $nameArg  installation prefix (default: '$DefaultPrefix'). Allows to install multiple versions.
        |  $Switch_List           list installed prefixes and quit.
        |  $Switch_Remove         remove the installation data for given prefix, and quit.
        |  $Switch_Help           print this information. Use twice to get Mellite application help.
        |
        |Any other arguments are passed on to the Mellite application.
        |""".stripMargin
    )
  }

  def main(args: Array[String]): Unit = {
    var ai = 0

    var headless    = false
    var verbose     = false
    var checkNow    = false
    var offline     = false
    var selVersion  = false
    var listPrefixes= false
    var removePrefix= false
//    var oscServer   = true
    var prefix      = DefaultPrefix
    var helpCount   = 0

    val appArgsB    = List.newBuilder[String]

    while (ai < args.length) {
      args(ai) match {
        case Switch_Headless | Switch_HeadlessS => headless   = true
        case Switch_Verbose  | Switch_VerboseS  => verbose    = true
        case Switch_CheckUpdate                 => checkNow   = true
        case Switch_Offline                     => offline    = true
        case Switch_SelectVersion               => selVersion = true
//        case Switch_NoServer                    => oscServer  = false
        case Switch_Prefix                      => ai += 1; prefix = args(ai)
        case Switch_List                        => listPrefixes = true
        case Switch_Remove                      => removePrefix = true
        case Switch_Help =>
          helpCount += 1
          if (helpCount == 2) appArgsB += Switch_Help

        case arg =>
          // allow anything else to proliferate to Mellite app
          appArgsB += arg
      }
      ai += 1
    }

    if (helpCount > 0) {
      printHelp()
      if (helpCount == 1) {
        sys.exit(1)
      }
    }

    val appDirs     = AppDirsFactory.getInstance
    val cacheBase   = appDirs.getUserCacheDir (appId, /* version */ null, /* author */ groupId)
    val dataBase    = appDirs.getUserDataDir  (appId, /* version */ null, /* author */ groupId)
    val configBase  = appDirs.getUserConfigDir(appId, /* version */ null, /* author */ groupId)

    if (listPrefixes) {
      val found     = new File(configBase).listFiles((pre: File) => pre.isDirectory && new File(pre, propFileName).isFile)
      val versions  = found.map { pre =>
        val v = Try { Installation.readProperties(new File(pre, propFileName)).currentVersion } .getOrElse("?")
        s"  ${pre.getName} : $v"
      } .sorted
      println(s"The following ${versions.length} prefixes and versions are found:")
      println(versions.mkString("\n"))
      sys.exit()
    }

    val cacheBaseF  = new File(cacheBase  , prefix)
    val dataBaseF   = new File(dataBase   , prefix)
    val configBaseF = new File(configBase , prefix)

    if (removePrefix) {
      deleteDirectory(cacheBaseF  )
      deleteDirectory(dataBaseF   )
      deleteDirectory(configBaseF )
      sys.exit()
    }

    val appArgs     = appArgsB.result()
    implicit val cfg: Config = Config(headless = headless, verbose = verbose, offline = offline, checkNow = checkNow,
      selectVersion = selVersion, /*oscServer = oscServer,*/
      cacheBase = cacheBaseF, dataBase = dataBaseF, configBase = configBaseF,
      prefix = prefix, appArgs = appArgs)
    val inst0     = Installation.read()

    if (cfg.verbose) println(fullName)

    runWith(inst0)
  }

  def runWith(inst0: Installation)(implicit cfg: Config): Unit =
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
  private val repos  = Resolve.defaultRepositories

  private val QuitAction    : () => Unit = () => sys.exit()
  private var restartAction : () => Unit = QuitAction

  private def obtainVersion(select: Boolean)
                           (implicit r: Reporter, cfg: Config, cacheResolve: FileCache[Task]): Future[Option[String]] = {
    val futVersions: Future[core.Versions] = obtainVersions()
    if (!select) {
      futVersions.map(vs => if (vs.available.isEmpty) None else Some(vs.latest))
    } else {
      futVersions.flatMap { vs =>
        dialogSelectVersion(vs)
      }
    }
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
//    var failed  = List.empty[File]
    def loop(child: File): Unit = if (!done.contains(child)) {
      done += child
      if (child.isDirectory) {
        val cc = child.listFiles()
        if (cc != null) cc.foreach(loop)
      }
      if (!child.delete()) {
//        failed = child :: failed
        Console.err.println(s"Failed to delete $child")
      }
    }
    loop(d)
//    if (failed.isEmpty) Nil else failed.reverse
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

  private def dialogSelectVersion(versions: core.Versions)(implicit r: Reporter): Future[Option[String]] = {
    val default = if (versions.available.isEmpty) None else Some(versions.latest)
    r.showOptions(s"Select a version to install", versions.available, default = default)
  }

  private def messageNoNewVersion(inst0: Installation)(implicit r: Reporter): Future[Unit] = {
    if (inst0.isInstalled) r.showMessage("No new version found.", isError = false)
    else r.showMessage("Could not find any online version!", isError = true)
  }

  private def dialogCompareVersion(inst0: Installation, latest: String, explicit: Boolean)
                                  (implicit r: Reporter, cfg: Config, cacheResolve: FileCache[Task]): Future[Installation] = {
    if (Version(inst0.currentVersion) >= Version(latest)) {
      val info = if (explicit) messageNoNewVersion(inst0) else Future.unit
      info.map(_ => inst0)
    }
    else dialogNewerVersion(inst0, latest)
  }

  private lazy val oscClient = {
    val c = osc.UDP.Config()
    c.localIsLoopback = true
    val t = osc.UDP.Transmitter(c)
    t.connect()
    val r = osc.UDP.Receiver(t.channel, c)
    r.connect()
    (t, r)
  }

  private def restartSame(inst: Installation)(implicit cfg: Config, cacheResolve: FileCache[Task]) = () => {
    EventQueue.invokeLater { () =>
      implicit val r: Reporter = new Splash
      runProcess(inst, Future.successful(inst))
    }
  }

  private def setupOSC(inst0: Installation)(implicit cfg: Config, cacheResolve: FileCache[Task]): Int = {
    val (t, r) = oscClient
    if (cfg.verbose) {
      t.dump()
      r.dump()
    }
    r.action = {
      case (osc.Message("/check-update", flags: Int, _ @ _*), sender) =>
        EventQueue.invokeLater { () =>
          implicit val r: Reporter = new Splash
          val select = (flags & 0x01) != 0
          val futVersion: Future[Option[String]] = obtainVersion(select = select)
          val futInstall: Future[Installation] = futVersion.flatMap {
            case Some(v)  => dialogCompareVersion(inst0, latest = v, explicit = true)
            case None     => Future.successful(inst0)
          }
          futInstall.onComplete {
            case Success(inst1) =>
              if (inst0 != inst1) {
                restartAction = { () =>
                  restartAction = QuitAction
                  runProcess(inst0, Future.successful(inst1))
                }
                t.send(osc.Message("/reboot"), sender)
              } else {
                r.dispose()
              }

            case Failure(ex) =>
              val futM = r.showMessage(s"Could not update: ${ex.getMessage}", isError = true)
              futM.andThen { case _ => r.dispose() }
              ex.printStackTrace()
          }
        }

      case (other, _) =>
        Console.err.println(s"Unsupported OSC message: $other")
    }
    t.localPort
  }

  def run(inst0: Installation)(implicit r: Reporter, cfg: Config): Unit = {
    import cfg._
    import r.status
//    cacheDir.mkdirs()
//    artDir  .mkdirs()

    val now = System.currentTimeMillis()

    implicit lazy val cacheResolve: FileCache[Task] = FileCache[Task](cacheDir)
      .withTtl(1.hour)  // XXX TODO which value here

    val autoCheck   = now >= inst0.nextUpdateTime
    val shouldCheck = !inst0.isInstalled || cfg.checkNow || cfg.selectVersion || autoCheck

    def stickToOld: Future[Installation] =
      if (inst0.isInstalled) {
        val inst1 = if (!autoCheck) inst0 else inst0.copy(nextUpdateTime = now + 604800000L)
        Future.successful(inst1)
      }
      else Future.failed(new Exception(s"No version installed. Re-run with network enabled!"))

    val futInst: Future[Installation] = if (!offline && shouldCheck) {
      status = "Checking version..."

      val futVersion: Future[Option[String]] = obtainVersion(select = cfg.selectVersion)

      futVersion.transformWith {
        case Success(Some(v)) =>
          if (!inst0.isInstalled || cfg.selectVersion) install(inst0, version = v)
          else dialogCompareVersion(inst0, latest = v, explicit = cfg.checkNow)

        case Success(None) => stickToOld

        case Failure(ex) =>
          if (inst0.isInstalled) Future.successful(inst0)
          else Future.failed(ex)
      }

    } else {
      stickToOld
    }

    runProcess(inst0, futInst)
  }

  final case class RunningProcess(inst: Installation, p: Process, hasOSC: Boolean)

  @tailrec
  private def isInDirectory(child: File, parent: File): Boolean =
    child == parent || (child.getParentFile match {
      case p: File  => isInDirectory(p, parent)
      case _        => false
    })

  def runProcess(inst0: Installation, futInst: Future[Installation])
                (implicit r: Reporter, cfg: Config,
                 cacheResolve: FileCache[Task]): Future[RunningProcess] = {
    val futProcess: Future[RunningProcess] = futInst.map { inst1 =>
      val addCP   = inst1.jars.iterator.map(_.getPath)
      val ph      = ProcessHandle.current()
      val pi      = ph.info()
      val cmd     = pi.command().get()
      val argsIn  = pi.arguments().get().toList

      if (cfg.verbose) {
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
      val idxSelf = argsIn.indexOf(clzSelf)
      assert (idxSelf > idxCP)
      val hasAPI1   = Version(inst1.currentVersion) >= Version(Mellite_API1)
      val _hasOSC   = /*cfg.oscServer &&*/ !cfg.offline && hasAPI1
      val appArgs0  = cfg.appArgs
      val appArgs1  = if (!hasAPI1 || cfg.prefix == DefaultPrefix) appArgs0 else "--prefix" :: cfg.prefix :: appArgs0
      val appArgs   = if (!_hasOSC) appArgs1 else {
        val port = setupOSC(inst1)
        "--launcher" :: port.toString :: appArgs1
      }
      val argsOut: List[String] = argsIn.take(idxSelf).patch(idxCP, newCP.mkString(File.pathSeparator) :: Nil, 1) :::
        (mainClass :: appArgs)

      if (cfg.verbose) {
        println(s"ARGS OUT = ${argsOut.mkString("'", "', '", "'")}")
      }

      val pb  = new ProcessBuilder(cmd +: argsOut: _*)
      pb.inheritIO()
      val newInst = inst1 != inst0
      val instOut = if (!newInst) inst0 else inst1.copy(oldJars = Nil)
      restartAction = restartSame(instOut)
      val p   = pb.start()

      if (newInst) {
        if (inst1.oldJars.nonEmpty) {
          // println(s"OLD JARS ${inst1.oldJars.size}")
          // println(inst1.oldJars.mkString("\n"))
          val oldDirs = inst1.oldJars.iterator.flatMap { f =>
            // make sure we only delete directories within `dataBase`, because
            // when Coursier detects locally published artifacts, it does not
            // copy them but simply forwards their location (`~/.ivy2/local/...`).
            Option(f.getParentFile).filter(isInDirectory(_, cfg.dataBase))
          }.toSet

          // println("OLD DIRS")
          // println(oldDirs.mkString("\n"))
          val newDirs = inst1.jars.iterator.flatMap(f => Option(f.getParentFile)).toSet
          val delDirs = oldDirs -- newDirs
          delDirs.foreach { d =>
            if (cfg.verbose) println(s"DELETE $d")
            deleteDirectory(d)
          }
        }
        if (cfg.verbose) {
          // println("-- old")
          // println(inst0)
          println(s"WRITE PROPERTIES - ${cfg.propFile}")
          println(instOut)
        }
        instOut.write()
      }

      RunningProcess(instOut, p, _hasOSC)
    }

    futProcess.onComplete {
      case Failure(ex) =>
        val m = ex.getMessage
        r.status = if (m == null) "Failed!" else s"Error: $m"
        ex.printStackTrace()

      case Success(run) =>
        if (run.hasOSC) {
          val alive = new Thread(() => {
            //          Thread.sleep(2000)
            r.dispose()
            waitFor(run.p, verbose = cfg.verbose)
          })
          alive.setDaemon(false)
          alive.start()
        } else {
          sys.exit(0)
        }
    }

    futProcess
  }

  private def waitFor(p: Process, verbose: Boolean): Unit = {
    val code = p.waitFor()  // join child process
    if (verbose) {
      println(s"EXIT CODE $code")
    }
    if (code == 82 /* 'R' */) {
      restartAction()
    } else {
      sys.exit(code)
    }
  }
}
