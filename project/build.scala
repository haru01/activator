import org.apache.tools.ant.taskdefs.Echo
import sbt._
import ActivatorBuild._
import Dependencies._
import Packaging.localRepoArtifacts
import com.typesafe.sbt.S3Plugin._
import com.typesafe.sbt.SbtNativePackager.Universal
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import com.typesafe.sbt.SbtPgp
import play.PlayImport.PlayKeys
import com.typesafe.sbt.SbtPgp.autoImport._
import com.typesafe.sbt.less.Import.LessKeys
import com.typesafe.sbt.web.SbtWeb.autoImport._
import com.typesafe.sbt.jse.JsEngineImport.JsEngineKeys
// NOTE - This file is only used for SBT 0.12.x, in 0.13.x we'll use build.sbt and scala libraries.
// As such try to avoid putting stuff in here so we can see how good build.sbt is without build.scala.

object TheActivatorBuild extends Build {

  def fixFileForURIish(f: File): String = {
    val uriString = f.toURI.toASCIIString
    if(uriString startsWith "file://") uriString.drop("file://".length)
    else uriString.drop("file:".length)
  }

  // ADD sbt launcher support here.
  override def settings = super.settings ++ SbtSupport.buildSettings ++ baseVersions ++ Seq(
    // This is a hack, so the play application will have the right view of the template directory.
    Keys.baseDirectory <<= Keys.baseDirectory apply { bd =>
      sys.props("activator.home") = fixFileForURIish(bd.getAbsoluteFile)
      bd
    }
  )
  // TODO : Add ++ play.Project.intellijCommandSettings Play 2.3 style to settings above

  val root = (
    Project("root", file("."))  // TODO - Oddities with clean..
    .noAutoPgp
    .doNotPublish
    aggregate(toReferences(publishedProjects ++
      Seq(dist, it, localTemplateRepo, offlinetests)): _*)
  )

  lazy val news: Project = (
    Project("news", file("news"))
    settings(NewsHelper.settings:_*)
  )

  // This project helps us isolate creating the local template repository for testing.
  lazy val localTemplateRepo: Project = (
    Project("template-repository", file("template-repository"))
    .noAutoPgp
    .doNotPublish
    settings(LocalTemplateRepo.settings:_*)
    settings(Keys.resolvers += typesafeIvyReleases)
  )

  // These are the projects we want in the local repository we deploy.
  lazy val publishedProjects: Seq[Project] = Seq(ui, uiCommon, launcher, props)

  // basic project that gives us properties to use in other projects.
  lazy val props = (
    ActivatorJavaProject("props")
    settings(Properties.makePropertyClassSetting(Dependencies.sbtVersion, Dependencies.scalaVersion):_*)
  )

  // Helper for UI projects (CLI + GUI)
  lazy val uiCommon = (
    ActivatorProject("ui-common")
    dependsOnRemote(templateCache)
    dependsOn(props)
  )

  val verboseSbtTests = false

  def configureSbtTest(testKey: Scoped) = Seq(
    // set up embedded sbt for tests, we fork so we can set
    // system properties.
    Keys.fork in Test in testKey := true,
    Keys.javaOptions in Test in testKey <<= (
      SbtSupport.sbtLaunchJar,
      Keys.javaOptions in testKey,
      Keys.update) map {
      (launcher, oldOptions, updateReport) =>
        oldOptions ++
      (if (verboseSbtTests)
        Seq("-Dakka.loglevel=DEBUG",
            "-Dakka.actor.debug.autoreceive=on",
            "-Dakka.actor.debug.receive=on",
            "-Dakka.actor.debug.lifecycle=on")
       else
         Seq.empty)
    })

  import WebKeys.{assets, public}
  import sbt.Keys.products

  lazy val ui = (
    ActivatorPlayProject("ui")
    dependsOnRemote(
      requirejs, jquery, knockout, ace, /*requireCss, requireText,*/ keymage, commonsIo, mimeUtil, activatorAnalytics,
      sbtLauncherInterface % "provided",
      sbtrcClient,
      sbtrcIntegration % "compile;test->test"
    )
    dependsOn(props, uiCommon)
    settings(PlayKeys.playDefaultPort := 8888)
    settings(Keys.includeFilter in (Assets, LessKeys.less) := "*.less")
    settings(Keys.excludeFilter in (Assets, LessKeys.less) := "_*.less")
    settings(Keys.libraryDependencies ++= Seq(Dependencies.akkaTestkit % "test", Dependencies.specs2 % "test"))
    // set up debug props for forked tests
    settings(configureSbtTest(Keys.test): _*)
    settings(configureSbtTest(Keys.testOnly): _*)
    // set up debug props for "run"
    settings(
      Keys.update <<= (
          SbtSupport.sbtLaunchJar,
          Keys.update,
          LocalTemplateRepo.localTemplateCacheCreated in localTemplateRepo) map {
        (launcher, update, templateCache) =>
          sys.props("activator.template.cache") = fixFileForURIish(templateCache)
          sys.props("activator.runinsbt") = "true"
          System.err.println("Template cache = " + sys.props("activator.template.cache"))
          update
      },
      // We need to embed the assets in this JAR for activator.
      // If we add any more play projects, we need to be clever with them.
      public in Assets := (public in Assets).value / "public",
      products in Compile += (assets in Assets).value.getParentFile
    )
    settings(
      Keys.compile in Compile <<= (Keys.compile in Compile, Keys.baseDirectory, Keys.streams) map { (oldCompile, baseDir, streams) =>
        // write version information
        VersionGenerator.createInformation(baseDir)

        // check for JS errors
        val jsErrors = JsChecker.fixAndCheckAll(baseDir, streams.log)
        for (error <- jsErrors) {
          streams.log.error(error)
        }
        if (jsErrors.nonEmpty)
          throw new RuntimeException(jsErrors.length + " JavaScript formatting errors found")
        else
          streams.log.info("JavaScript whitespace meets our exacting standards")
        oldCompile
      }
    )
  )

  lazy val launcher = (
    ActivatorProject("launcher")
    dependsOnRemote(sbtLauncherInterface, sbtCompletion)
    dependsOn(props, uiCommon)
  )

  // A hack project just for convenient IvySBT when resolving artifacts into new local repositories.
  lazy val dontusemeresolvers = (
    ActivatorProject("dontuseme")
    .doNotPublish
    settings(
      // This hack removes the project resolver so we don't resolve stub artifacts.
      Keys.fullResolvers <<= (Keys.externalResolvers, Keys.sbtResolver) map (_ :+ _),
      Keys.resolvers += Resolver.url("sbt-plugin-releases", new URL("http://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/"))(Resolver.ivyStylePatterns),
      Keys.resolvers += "Scalaz Bintray Repo" at "https://dl.bintray.com/scalaz/releases"
    )
  )
  lazy val it = (
      ActivatorProject("integration-tests")
      settings(integration.settings:_*)
      dependsOnRemote(sbtLauncherInterface, sbtIo, sbtrcClient, sbtrcIntegration)
      dependsOn(props)
      settings(
        org.sbtidea.SbtIdeaPlugin.ideaIgnoreModule := true,
        // we don't use doNotPublish because we want to publishLocal
        Keys.publish := {},
        PgpKeys.publishSigned := {}
      )
  )

  lazy val offlinetests = (
    ActivatorProject("offline-tests")
    .doNotPublish
    settings(offline.settings:_*)
  )

  lazy val logDownloadUrls = taskKey[Unit]("log download urls because we are lazy and don't want to hand-construct them")

  lazy val dist = (
    ActivatorProject("dist")
    // TODO - Should publish be pushing the S3 upload?
    .doNotPublish
    settings(Packaging.settings:_*)
    settings(s3Settings:_*)
    settings(
      Keys.scalaBinaryVersion <<= Keys.scalaBinaryVersion in ui,
      Keys.resolvers ++= Seq(
        "Lightbend repository" at "https://repo.typesafe.com/typesafe/releases/",
        Resolver.url("typesafe-ivy-releases", new URL("https://repo.typesafe.com/typesafe/releases/"))(Resolver.ivyStylePatterns),
        Resolver.url("sbt-plugin-releases", new URL("http://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/"))(Resolver.ivyStylePatterns)
      ),
      // TODO - Do this better - This is where we define what goes in the local repo cache.
      localRepoArtifacts <++= (publishedProjects.toSeq map { ref =>
        (Keys.projectID in ref) apply { id => id }
      }).join,
      localRepoArtifacts ++= Seq(
        // base dependencies
        "org.scala-sbt" % "sbt" % Dependencies.sbtVersion,
        "org.scala-lang" % "scala-compiler" % Dependencies.sbtPluginScalaVersion,
        "org.scala-lang" % "scala-compiler" % Dependencies.scalaVersion,

        // sbt stuff
        sbtrcClient,

        // sbt 0.13 plugins
        playSbt13Plugin,
        eclipseSbt13Plugin,
        ideaSbt13Plugin,

        // featured template dependencies
        // *** note: do not use %% here ***
        "org.scala-lang" % "jline" % "2.10.4",
        "org.scala-lang" % "jline" % "2.10.6",

        "com.typesafe.slick" % "slick_2.11" % "3.0.0",
        "com.h2database" % "h2" % "1.3.175",
        "org.jboss.logging" % "jboss-logging" % "3.1.1.GA",

        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-less" % "1.0.0", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-less" % "1.0.6", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-less" % "1.1.0", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-jshint" % "1.0.4", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-rjs" % "1.0.8", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-digest" % "1.1.1", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-mocha" % "1.1.0", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-play-enhancer" % "1.1.0", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.sbt" % "sbt-coffeescript" % "1.0.0", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.play" % "sbt-plugin" % "2.4.4", "0.13", "2.10"),
        Defaults.sbtPluginExtra("com.typesafe.play" % "sbt-plugin" % "2.5.9", "0.13", "2.10"),
        Defaults.sbtPluginExtra("org.irundaia.sbt" % "sbt-sassify" % "1.4.6", "0.13", "2.10"),
        "com.typesafe.play" % "play-jdbc_2.11" % "2.4.4",
        "com.typesafe.play" % "anorm_2.11" % "2.4.4",
        "com.typesafe.play" % "play-cache_2.11" % "2.4.4",
        "com.typesafe.play" % "play-docs_2.11" % "2.4.4",
        "com.typesafe.play" % "play-specs2_2.11" % "2.4.4",
        "com.typesafe.play" % "play-omnidoc_2.11" % "2.4.4",
        "com.typesafe.play" % "play-server_2.11" % "2.5.9",
        "com.typesafe.play" % "play-java_2.11" % "2.5.9",
        "com.typesafe.play" % "play-netty-server_2.11" % "2.5.9",
        "com.typesafe.play" % "play-logback_2.11" % "2.5.9",
        "com.typesafe.play" % "play-java-jdbc_2.11" % "2.5.9",
        "com.typesafe.play" % "play-cache_2.11" % "2.5.9",
        "com.typesafe.play" % "play-java-ws_2.11" % "2.5.9",
        "com.typesafe.play" % "play-test_2.11" % "2.5.9",
        "com.typesafe.play" % "play-omnidoc_2.11" % "2.5.9",
        "org.scalatestplus.play" % "scalatestplus-play_2.11" % "1.5.1",
        "org.scalaz.stream" % "scalaz-stream_2.11" % "0.7a",
        "org.specs2" % "specs2-matcher-extra_2.11" % "3.6",
        "com.typesafe.play" % "play-test_2.11" % "2.4.4",
        "com.typesafe.play" % "play-java_2.11" % "2.4.4",
        "com.typesafe.play" % "play-java-jdbc_2.11" % "2.4.4",
        "com.typesafe.play" % "play-java-ebean_2.11" % "2.4.4",
        "com.typesafe.play" % "play-java-ws_2.11" % "2.4.4",
        "com.typesafe.akka" % "akka-slf4j_2.11" % "2.3.11",
        "com.typesafe.akka" % "akka-actor_2.11" % "2.4.0",
        "com.typesafe.akka" % "akka-testkit_2.11" % "2.4.0",
        "org.webjars" % "bootstrap" % "3.0.0",
        "org.webjars" % "bootstrap" % "2.3.2",
        "org.webjars" % "knockout" % "2.3.0",
        "org.webjars" % "requirejs" % "2.1.11-1",
        "org.webjars" % "leaflet" % "0.7.2",
        "org.webjars" % "flot" % "0.8.0",
        "org.webjars" % "squirejs" % "0.1.0",
        "org.webjars" % "rjs" % "2.1.11-1",
        "org.webjars" % "rjs" % "2.1.11-1-trireme",

        "org.apache.httpcomponents" % "httpcore" % "4.0.1",
        "org.apache.httpcomponents" % "httpclient" % "4.0.1",

        "org.slf4j" % "slf4j-nop" % "1.6.4",
        "com.novocode" % "junit-interface" % "0.11",
        "junit" % "junit" % "4.12",
        "org.scalatest" % "scalatest_2.11" % "2.2.4"
      ),
      Keys.mappings in S3.upload <<= (Keys.packageBin in Universal, Packaging.minimalDist, Keys.version) map { (zip, minimalZip, v) =>
        Seq(minimalZip -> ("typesafe-activator/%s/typesafe-activator-%s-minimal.zip" format (v, v)),
            zip -> ("typesafe-activator/%s/typesafe-activator-%s.zip" format (v, v)))
      },
      S3.host in S3.upload := "downloads.typesafe.com.s3.amazonaws.com",
      S3.progress in S3.upload := true,
      S3.upload := {
        val log = Keys.streams.value.log
        val hash = (LocalTemplateRepo.checkTemplateCacheHash in TheActivatorBuild.localTemplateRepo).value
        log.info("Publishing to S3 with template index " + hash)
        S3.upload.value
      },
      logDownloadUrls := {
        val log = Keys.streams.value.log
        val version = Keys.version.value
        log.info(s"Download: http://downloads.typesafe.com/typesafe-activator/${version}/typesafe-activator-${version}.zip")
        log.info(s"Minimal:  http://downloads.typesafe.com/typesafe-activator/${version}/typesafe-activator-${version}-minimal.zip")
      }
    )
  ).enablePlugins(JavaAppPackaging)
}
