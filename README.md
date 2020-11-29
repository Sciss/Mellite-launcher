# Mellite-launcher

[![Build Status](https://travis-ci.org/Sciss/Mellite-launcher.svg?branch=main)](https://travis-ci.org/Sciss/Mellite-launcher)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/de.sciss/mellite-launcher_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/de.sciss/mellite-launcher_2.13)

## statement

Mellite-launcher is currently an __experiment__ to build a small JDK-bundled launcher stub for
[Mellite](https://github.com/Sciss/Mellite) that can download and update the actual application from
Maven Central artifacts retrieved by Coursier. The idea is to avoid having to upload hundreds and hundreds
of megabytes of new application artifacts for small updates, and to avoid having to build platform specific
artifacts over and over again. Instead, one should be able to install the launcher once, and all subsequent
updates go through the launcher itself, so that releasing new Mellite artifacts boils down to publishing
new Maven artifacts.

The project is (C)opyright 2020 by Hanns Holger Rutz. All rights reserved.
Since the launcher essentially establishes the classpath to Mellite, it is also 
released under 
the [GNU Affero General Public License](https://git.iem.at/sciss/Mellite-launcher/raw/main/LICENSE) v3+ 
and comes with absolutely no warranties. To contact the author, send an e-mail to `contact at sciss.de`.

## requirements / installation

This project builds with sbt against Scala 2.13 (JVM).

## notes

It's difficult to keep the classpath of the launcher and application itself separate; for example, once
AWT is initialised, it cannot be "uninitialised", and thus adding a look-and-feel jar to classpath has no
effect on the `UIManager` finding the new jar. Therefore, we spawn an entirely new process for the application,
then close the splash screen and join the child process until its termination.

-----

## creating new releases

This section is an aide-mémoire for me in releasing stable versions.

- check that no `SNAPSHOT` versions of libraries are used: `cat build.sbt | grep SNAPSHOT`.
   Change `projectVersion` appropriately.
- check that libraries are up-to-date, and that there are no binary conflicts:
   `sbt dependencyUpdates evicted`
- Make sure the XFree desktop file version is set:
   `vim app/src/debian/Mellite-launcher.desktop`
- Update the release versions in `README.md`
- Test the app building: `sbt app/clean app/update app/test`
- Build the native image:
    `sbt -java-home '/home/hhrutz/Downloads/OpenJDK11U-jdk_x64_linux_hotspot_11.0.9_11/jdk-11.0.9+11' full/universal:packageBin full/debian:packageBin`
  