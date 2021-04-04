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
import coursier.cache.CacheLogger
import coursier.util.Task
import net.harawata.appdirs.AppDirsFactory

import java.awt.EventQueue
import java.io.File
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object Launcher {
  // eventually these could become settings of a generic launcher
  final val groupId         = "de.sciss"
  final val artifactId      = "mellite-app_2.13"
  final val mainClass       = "de.sciss.mellite.Mellite"
  final val classPathFilter = "/org.openjfx" :: Nil

  final case class Config(headless: Boolean, verbose: Boolean, offline: Boolean)

  def main(args: Array[String]): Unit = {
    val headless  = args.exists(p => p == "--headless" || p == "-h")
    val verbose   = args.exists(p => p == "--verbose"  || p == "-V")
    val offline   = args.contains("--offline")
    val cfg       = Config(headless = headless, verbose = verbose, offline = offline)

    //    val parent    = getClass.getClassLoader
//    val cl        = new URLClassLoader(new Array(0), parent)
//    val clLaunch  = Class.forName("de.sciss.mellite.Launcher", true, cl)
//    val cons      = clLaunch.getConstructor(classOf[ClassLoader])
//    cons.newInstance(parent)
//    val ph = ProcessHandle.current()
//    val pi = ph.info()
//    println(s"COMMAND: ${pi.command()}")
//    println(s"COMMAND LINE: ${pi.commandLine()}")
//    println(s"ARGS: ${pi.arguments()}")
//    println(pi)

    if (headless) {
      val splash = new ConsoleReporter
      run(cfg, splash)
    } else {
      EventQueue.invokeLater { () =>
        val splash = new Splash
        run(cfg, splash)
      }
    }
  }

  private def run(cfg: Config, r: Reporter): Unit = {
    import cfg._
    import r.status
    val appDirs   = AppDirsFactory.getInstance
    val cacheBase = appDirs.getUserCacheDir ("mellite", /* version */ null, /* author */ "de.sciss")
    val dataBase  = appDirs.getUserDataDir  ("mellite", /* version */ null, /* author */ "de.sciss")
    if (verbose) {
      println(s"Cache path: $cacheBase")
      println(s"Data path : $dataBase" )
    }
    val cacheDir  = new java.io.File(cacheBase, "coursier")
    val artDir    = new java.io.File(dataBase , "coursier")
//    cacheDir.mkdirs()
//    artDir  .mkdirs()

    status = "Checking version..."
    val cacheResolve = cache.FileCache[Task](cacheDir)
      .withTtl(1.hour)
//      .withTtl(1.second)
    //      .withLogger(RefreshLogger.create(System.out))
    //      .withPool(pool)

    val appMod    = Module(Organization(groupId), ModuleName(artifactId))
    val repos     = if (cfg.offline) {
//      val centralURI = new URI(Repositories.central.root)
//      val centralF   = new File(new URI("file", null, centralURI.getPath))
      // XXX TODO: is there a proper way to construct the cache directory for a given repository?
      val centralF    = new File(new File(new File(cacheDir, "https"), "repo1.maven.org"), "maven2")
      val centralURI  = centralF.toURI.toString
//      println(centralURI)
//      assert (centralF.isDirectory, centralF.toString)
      val centralCache = MavenRepository(centralURI)
      LocalRepositories.ivy2Local :: centralCache :: Nil
    } else {
      Resolve.defaultRepositories
    }
    val versions  = Versions(cacheResolve).withModule(appMod).withRepositories(repos)
    val futVer: Future[Versions.Result] = versions.result().future()

//    futVer.foreach { r =>
//      println(s"RES: $r")
//    }

    val futLatest: Future[String] = futVer.map { req =>
      val vs  = req.versions
      if (verbose) {
        println("------ Available Versions ------")
        val av = vs.available
        av.foreach(println)
        if (av.nonEmpty) println(s"Latest: ${vs.latest}")
      }
      vs.latest
    }

//    futLatest.foreach { s =>
//      println(s"LATEST: $s")
//    }

    val futFetch: Future[Fetch.Result] = futLatest.flatMap { v =>
      r.version = s"version $v"
      status = "Resolving dependencies..."
      val appDep    = Dependency(appMod, v)
      val resolve   = Resolve(cacheResolve)
        .addDependencies(appDep).withRepositories(repos)
      val cacheArt  = cache.FileCache[Task](artDir)
      resolve.future().flatMap { resolution =>
        status = "Fetching libraries..."
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
      status = "Fetched libraries."
      if (verbose) {
        println("------ Artifacts ------")
        fetched.detailedArtifacts.foreach { case (dep, pub, art, f) =>
          println(s"DEP $dep | PUB $pub | ART $art | FILE $f")
        }
      }
//      fetched.fullDetailedArtifacts /*detailedArtifacts*/.collectFirst {
//        case (dep, pub, art, f) if
//      }

      fetched.files
    }

    val futInst: Future[Process] = futFiles.map { files =>
      val addCP   = files.iterator.map(_.getPath)
      val ph      = ProcessHandle.current()
      val pi      = ph.info()
      val cmd     = pi.command().get()
      val argsIn  = pi.arguments().get()

      if (verbose)
      {
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
      val argsOut = argsIn.take(idxSelf).patch(idxCP, newCP.mkString(File.pathSeparator) :: Nil, 1) :+ mainClass

      if (verbose)
      {
        println(s"ARGS OUT = ${argsOut.mkString("'", "', '", "'")}")
      }

      val pb      = new ProcessBuilder(cmd +: argsOut: _*)
      pb.inheritIO()
      val p       = pb.start()
      p
    }

    val futErr: Future[Process] = futInst.recover {
      case ex =>
        status = "Failed!"
        ex.printStackTrace()
        throw ex
    }

    futErr.foreach { p =>
      val alive = new Thread(() => {
        r.dispose()
        // EventQueue.invokeLater(() => r.dispose())
        val code = p.waitFor()  // join child process
        if (verbose) {
          println(s"EXIT CODE $code")
        }
        sys.exit(code)
      })
      alive.setDaemon(false)
      alive.start()
    }
  }
}
