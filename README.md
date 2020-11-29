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

The idea is that the launcher has almost zero dependencies, so there won't be a conflict with Mellite
itself, and we rarely need to update the launcher itself. (self-update is currently not considered.)
