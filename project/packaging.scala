import sbt._
import com.typesafe.sbt.packager.Keys._
import sbt.Keys._
import com.typesafe.sbt.SbtNativePackager._

object Packaging {

  val sbtLaunchJarUrl = SettingKey[String]("sbt-launch-jar-url")
  val sbtLaunchJarLocation = SettingKey[File]("sbt-launch-jar-location")  
  val sbtLaunchJar = TaskKey[File]("sbt-launch-jar", "Resolves SBT launch jar")
  val moduleID = (organization, sbtVersion) apply { (o,v) => ModuleID(o,"sbt",v) }

  val bintrayLinuxPattern = "[module]/[revision]/[module]-[revision].[ext]"
  val bintrayGenericPattern = "[module]/[revision]/[module]/[revision]/[module]-[revision].[ext]"
  val bintrayDebianUrl = "https://api.bintray.com/content/sbt/debian/"
  val bintrayRpmUrl = "https://api.bintray.com/content/sbt/rpm/"
  val bintrayGenericPackagesUrl = "https://api.bintray.com/content/sbt/native-packages/"
  val bintrayPublishAllStaged = TaskKey[Unit]("bintray-publish-all-staged", "Publish all staged artifacts on bintray.")
    
  // Note:  The legacy api.
  //val genericNativeReleasesUrl = "http://scalasbt.artifactoryonline.com/scalasbt/sbt-native-packages"
  //val genericNativeReleasesPattern = "[organisation]/[module]/[revision]/[module].[ext]"

  import util.control.Exception.catching  

  def downloadUrlForVersion(v: String) = (v split "[^\\d]" flatMap (i => catching(classOf[Exception]) opt (i.toInt))) match {
    case Array(0, 11, 3, _*)           => "http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/0.11.3-2/sbt-launch.jar"
    case Array(0, 11, x, _*) if x >= 3 => "http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/"+v+"/sbt-launch.jar"
    case Array(0, y, _*) if y >= 12    => "http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/"+v+"/sbt-launch.jar"
    case _                             => "http://repo.typesafe.com/typesafe/ivy-releases/org.scala-tools.sbt/sbt-launch/"+v+"/sbt-launch.jar"
  }
  /** Returns an id, url and pattern for publishing based on the given configuration. */
  def getPublishSettings(config: Configuration): (String, String, String) = 
    config.name match {
      case Debian.name => ("debian", bintrayDebianUrl, bintrayLinuxPattern)
      case Rpm.name => ("rpm", bintrayRpmUrl, bintrayLinuxPattern)
      case _ => ("native-packages", bintrayGenericPackagesUrl, bintrayGenericPattern)
    }
  
  def makePublishTo(id: String, url: String, pattern: String): Setting[_] = {
    publishTo := {
      val resolver = Resolver.url(id, new URL(url))(Patterns(pattern))
      Some(resolver)
    }
  }
  
  def makePublishToForConfig(config: Configuration) = {
    val (id, url, pattern) = getPublishSettings(config)


    // Add the publish to and ensure global resolvers has the resolver we just configured.
    inConfig(config)(Seq(
       makePublishTo(id, url, pattern),
       bintrayPublishAllStaged <<= (credentials, version) map { (creds, version) =>
         publishContent(id, version, creds)
       },
       // TODO - This is a little funky...
       publish <<= (publish, credentials, version) apply { (publish, creds, version) =>
         for {
           pub <- publish
           creds <- creds
         } yield publishContent(id, version, creds)
       }
    )) ++ Seq(
       resolvers <++= (publishTo in config) apply (_.toSeq)
    )
  }

  def publishToSettings: Seq[Setting[_]] = 
    Seq[Configuration](Debian, Universal, Windows, Rpm) flatMap makePublishToForConfig
  
  def bintrayCreds(creds: Seq[sbt.Credentials]): (String, String) = {
    val matching = 
      for {
        c <- creds
        if c.isInstanceOf[sbt.DirectCredentials]
        val cred = c.asInstanceOf[sbt.DirectCredentials]
        if cred.host == "api.bintray.com"
      } yield cred.userName -> cred.passwd

    matching.headOption getOrElse sys.error("Unable to find bintray credentials (api.bintray.com)")
  }

  def publishContent(repo: String, version: String, creds: Seq[sbt.Credentials]): Unit = {
    val subject = "sbt" // Sbt org
    val pkg = "sbt" // Sbt package
    val uri = s"https://bintray.com/api/v1/content/$subject/$repo/$pkg/$version/publish"
  
    val (u,p) = bintrayCreds(creds)
    import dispatch.classic._
    // TODO - Log the output
    Http(url(uri).POST.as(u,p).>|)
  }

  
  val settings: Seq[Setting[_]] = packagerSettings ++ deploymentSettings ++ mapGenericFilesToLinux ++ mapGenericFilesToWindows ++ publishToSettings ++ Seq(
    sbtLaunchJarUrl <<= sbtVersion apply downloadUrlForVersion,
    sbtLaunchJarLocation <<= target apply (_ / "sbt-launch.jar"),
    sbtLaunchJar <<= (sbtLaunchJarUrl, sbtLaunchJarLocation) map { (uri, file) =>
      import dispatch.classic._
      if(!file.exists) {
         // oddly, some places require us to create the file before writing...
         IO.touch(file)
         val writer = new java.io.BufferedOutputStream(new java.io.FileOutputStream(file))
         try Http(url(uri) >>> writer)
         finally writer.close()
      }
      // TODO - GPG Trust validation.
      file
    },
    // GENERAL LINUX PACKAGING STUFFS
    maintainer := "Josh Suereth <joshua.suereth@typesafe.com>",
    packageSummary := "Simple Build Tool for Scala-driven builds",
    packageDescription := """This script provides a native way to run the Simple Build Tool,
  a build tool for Scala software, also called SBT.""",
    // Here we remove the jar file and launch lib from the symlinks:
    linuxPackageSymlinks <<= linuxPackageSymlinks map { links =>
      for { 
        link <- links
        if !(link.destination endsWith "sbt-launch-lib.bash")
        if !(link.destination endsWith "sbt-launch.jar")
      } yield link
    },
    // DEBIAN SPECIFIC    
    name in Debian <<= (sbtVersion) apply { (sv) => "sbt" /* + "-" + (sv split "[^\\d]" take 3 mkString ".")*/ },
    version in Debian <<= (version, sbtVersion) apply { (v, sv) =>
      val nums = (v split "[^\\d]")
      "%s-%s-build-%03d" format (sv, (nums.init mkString "."), nums.last.toInt + 1)
    },
    debianPackageDependencies in Debian ++= Seq("curl", "java2-runtime", "bash (>= 2.05a-11)"),
    debianPackageRecommends in Debian += "git",
    linuxPackageMappings in Debian <+= (sourceDirectory) map { bd =>
      (packageMapping(
        (bd / "debian/changelog") -> "/usr/share/doc/sbt/changelog.gz"
      ) withUser "root" withGroup "root" withPerms "0644" gzipped) asDocs()
    },
    
    // RPM SPECIFIC
    name in Rpm := "sbt",
    version in Rpm <<= sbtVersion apply { sv => (sv split "[^\\d]" filterNot (_.isEmpty) mkString ".") },
    rpmRelease := "1",
    rpmVendor := "typesafe",
    rpmUrl := Some("http://github.com/paulp/sbt-extras"),
    rpmLicense := Some("BSD"),
    rpmRequirements :=Seq("java","java-devel","jpackage-utils"),
    rpmProvides := Seq("sbt"),
    
    
    // WINDOWS SPECIFIC
    name in Windows := "sbt",
    version in Windows <<= (sbtVersion) apply { sv =>
      (sv split "[^\\d]" filterNot (_.isEmpty)) match {
        case Array(major,minor,bugfix, _*) => Seq(major,minor,bugfix, "1") mkString "."
        case Array(major,minor) => Seq(major,minor,"0","1") mkString "."
        case Array(major) => Seq(major,"0","0","1") mkString "."
      }
    },
    maintainer in Windows := "Typesafe, Inc.",
    packageSummary in Windows := "Simple Build Tool",
    packageDescription in Windows := "THE reactive build tool.",
    wixProductId := "ce07be71-510d-414a-92d4-dff47631848a",
    wixProductUpgradeId := "4552fb0e-e257-4dbd-9ecb-dba9dbacf424",
    javacOptions := Seq("-source", "1.5", "-target", "1.5"),

    // Universal ZIP download install.
    name in Universal := "sbt",
    version in Universal <<= sbtVersion,
    mappings in Universal <+= sbtLaunchJar map { _ -> "bin/sbt-launch.jar" },
    
    // Misccelaneous publishing stuff...
    projectID in Debian    <<= moduleID,
    projectID in Windows   <<= moduleID,
    projectID in Rpm       <<= moduleID,
    projectID in Universal <<= moduleID
  )
}
