package ch.epfl.scala.platform

import ch.epfl.scala.platform
import ch.epfl.scala.platform.github.GitHubReleaser
import ch.epfl.scala.platform.search.{ModuleSearch, ScalaModule}
import ch.epfl.scala.platform.util.Error
import ch.epfl.scala.platform.github.GitHubReleaser.{
GitHubEndpoint,
GitHubRelease
}

import cats.data.Xor
import coursier.core.Version
import coursier.core.Version.{Literal, Qualifier}
import org.joda.time.DateTime
import sbt._
import sbt.complete.Parser
import sbtrelease.ReleasePlugin.autoImport.ReleaseStep
import sbtrelease.{Git, ReleaseStateTransformations}
import sbtrelease.Version.Bump

import scala.util.{Random, Try}

object PlatformPlugin extends sbt.AutoPlugin {

  object autoImport extends PlatformSettings

  override def trigger = allRequirements

  override def requires =
    bintray.BintrayPlugin &&
      sbtrelease.ReleasePlugin &&
      com.typesafe.sbt.SbtPgp &&
      com.typesafe.tools.mima.plugin.MimaPlugin

  override def projectSettings = PlatformKeys.settings
}

trait PlatformSettings {
  case class CIEnvironment(rootDir: File,
                           name: String,
                           repo: String,
                           branch: String,
                           commit: String,
                           buildDir: String,
                           buildUrl: String,
                           buildNumber: Int,
                           pullRequest: Option[String],
                           jobNumber: Int,
                           tag: Option[String])

  // CI-defined environment variables
  val platformInsideCi = settingKey[Boolean]("Checks if CI is executing the build.")
  val platformCiEnvironment = settingKey[Option[CIEnvironment]]("Get the Drone environment.")

  // Custom environment variables
  val sonatypeUsername = settingKey[Option[String]]("Get sonatype username.")
  val sonatypePassword = settingKey[Option[String]]("Get sonatype password.")

  // FORMAT: OFF
  val platformLogger = taskKey[Logger]("Return the sbt logger.")
  val platformReleaseOnMerge = settingKey[Boolean]("Release on every PR merge.")
  val platformModuleTags = settingKey[Seq[String]]("Tags for the bintray module package.")
  val platformTargetBranch = settingKey[String]("Branch used for the platform release.")
  val platformValidatePomData = taskKey[Unit]("Ensure that all the data is available before generating a POM file.")
  // TODO(jvican): Make sure every sbt subproject has an independent module
  val platformScalaModule = settingKey[ScalaModule]("Create the ScalaModule from the basic assert info.")
  val platformSbtDefinedVersion = settingKey[Version]("Get the sbt-defined version of the current module.")
  val platformCurrentVersion = taskKey[Version]("Get the current version used for releases.")
  val platformLatestPublishedVersion = taskKey[Version]("Fetch latest published stable version.")
  val platformNextVersionFun = taskKey[Version => Version]("Function that decides the next version.")
  val platformRunMiMa = taskKey[Unit]("Run MiMa and report results based on current version.")
  val platformGitHubToken = settingKey[String]("Token to publish releses to GitHub.")
  val platformReleaseNotesDir = settingKey[File]("Directory with the markdown release notes.")
  val platformGetReleaseNotes = taskKey[String]("Get the correct release notes for a release.")
  val platformReleaseToGitHub = taskKey[Unit]("Create a release in GitHub.")
  val platformGitHubRepo = settingKey[Option[(String, String)]]("Get GitHub organization and repository from .git folder.")
  val platformNightlyReleaseProcess = settingKey[Seq[ReleaseStep]]("The nightly release process for a Platform module.")
  val platformStableReleaseProcess = settingKey[Seq[ReleaseStep]]("The nightly release process for a Platform module.")
  val platformSignArtifact = settingKey[Boolean]("Enable to sign artifacts with the platform pgp key.")
  val platformCustomRings = settingKey[Option[File]]("File that stores the pgp secret ring.")
  val platformDefaultPublicRingName = settingKey[String]("Default file name for fetching the public gpg keys.")
  val platformDefaultPrivateRingName = settingKey[String]("Default file name for fetching the private gpg keys.")
  // Release process hooks -- useful for easily extending the default release process
  val platformBeforePublishHook = taskKey[Unit]("A release hook to customize the beginning of the release process.")
  val platformAfterPublishHook = taskKey[Unit]("A release hook to customize the end of the release process.")
  // FORMAT: ON
}

object PlatformKeys {

  import PlatformPlugin.autoImport._

  def settings: Seq[Setting[_]] =
    resolverSettings ++ compilationSettings ++ publishSettings ++ platformSettings

  import sbt._, Keys._
  import Helper._
  import sbtrelease.ReleasePlugin.autoImport._
  import bintray.BintrayPlugin.autoImport._
  import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._

  private val PlatformReleases =
    Resolver.bintrayRepo("scalaplatform", "modules-releases")
  private val PlatformTools =
    Resolver.bintrayRepo("scalaplatform", "tools")

  lazy val resolverSettings = Seq(
    resolvers ++= Seq(PlatformReleases, PlatformTools))

  private val defaultCompilationFlags =
    Seq("-deprecation", "-encoding", "UTF-8", "-unchecked")
  private val twoLastScalaVersions = Seq("2.10.6", "2.11.8")
  lazy val compilationSettings: Seq[Setting[_]] = Seq(
    scalacOptions in Compile ++= defaultCompilationFlags,
    crossScalaVersions in Compile := twoLastScalaVersions
  )

  lazy val publishSettings: Seq[Setting[_]] = Seq(
    bintrayOrganization := Some("scalaplatform"),
    publishTo := (publishTo in bintray).value,
    // Necessary for synchronization with Maven Central
    publishMavenStyle := true,
    bintrayReleaseOnPublish in ThisBuild := false,
    releaseCrossBuild := true
  ) ++ defaultReleaseSettings

  /** Define custom release steps and add them to the default pipeline. */

  import com.typesafe.sbt.SbtPgp.autoImport._

  lazy val defaultReleaseSettings = Seq(
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    // Empty the default release process to avoid errors
    releaseProcess := Seq.empty[ReleaseStep],
    platformNightlyReleaseProcess :=
      PlatformReleaseProcess.Nightly.releaseProcess,
    platformStableReleaseProcess :=
      PlatformReleaseProcess.Stable.releaseProcess
  )

  val emptyModules = Set.empty[ModuleID]
  lazy val platformSettings: Seq[Setting[_]] = Seq(
    platformInsideCi := getEnvVariable("CI").exists(toBoolean),
    platformCiEnvironment := {
      if (!platformInsideCi.value) None
      else {
        for {
          ciName <- getEnvVariable("CI_NAME")
          ciRepo <- getEnvVariable("CI_REPO")
          ciBranch <- getEnvVariable("CI_BRANCH")
          ciCommit <- getEnvVariable("CI_COMMIT")
          ciBuildDir <- getEnvVariable("CI_BUILD_DIR")
          ciBuildUrl <- getEnvVariable("CI_BUILD_URL")
          ciBuildNumber <- getEnvVariable("CI_BUILD_NUMBER")
          ciJobNumber <- getEnvVariable("CI_JOB_NUMBER")
        } yield
          CIEnvironment(file("/drone"),
            ciName,
            ciRepo,
            ciBranch,
            ciCommit,
            ciBuildDir,
            ciBuildUrl,
            ciBuildNumber.toInt,
            getEnvVariable("CI_PULL_REQUEST"),
            ciJobNumber.toInt,
            getEnvVariable("CI_TAG"))
      }
    },
    sonatypeUsername := getEnvVariable("SONATYPE_USERNAME"),
    sonatypePassword := getEnvVariable("SONATYPE_PASSWORD"),
    platformLogger := streams.value.log,
    platformReleaseOnMerge := false, // By default, disabled
    platformModuleTags := Seq.empty[String],
    platformTargetBranch := "platform-release",
    platformValidatePomData := {
      if (scmInfo.value.isEmpty)
        throw new NoSuchElementException(Feedback.forceDefinitionOfScmInfo)
      if (licenses.value.isEmpty)
        throw new NoSuchElementException(Feedback.forceValidLicense)
      bintrayEnsureLicenses.value
    },
    platformSbtDefinedVersion := {
      if (version.value.isEmpty) sys.error(Feedback.unexpectedEmptyVersion)
      val definedVersion = version.value
      val validatedVersion = for {
        version <- Try(Version(definedVersion)).toOption
        if !version.items.exists(_.isInstanceOf[Literal])
      } yield version
      validatedVersion.getOrElse(
        sys.error(Feedback.invalidVersion(definedVersion)))
    },
    platformCurrentVersion := {
      // Current version is a task whose value changes over time
      platformSbtDefinedVersion.value
    },
    platformScalaModule := {
      val org = organization.value
      val artifact = moduleName.value
      val version = scalaBinaryVersion.value
      ScalaModule(org, artifact, version)
    },
    mimaPreviousArtifacts := {
      val highPriorityArtifacts = mimaPreviousArtifacts.value
      if (highPriorityArtifacts.isEmpty) {
        /* This is a setting because modifies previousArtifacts, so we protect
         * ourselves from errors if users don't have connection to Internet. */
        val targetModule = platformScalaModule.value
        Helper.getPublishedArtifacts(targetModule)
      } else highPriorityArtifacts
    },
    platformLatestPublishedVersion := {
      val previousArtifacts = mimaPreviousArtifacts.value
      // Retry in case where sbt boots up without Internet connection
      val retryPreviousArtifacts =
      if (previousArtifacts.nonEmpty) previousArtifacts
      else Helper.getPublishedArtifacts(platformScalaModule.value)
      val moduleId = retryPreviousArtifacts.headOption.getOrElse(
        sys.error(Feedback.forceDefinitionOfPreviousArtifacts))
      Version(moduleId.revision)
    },
    platformNextVersionFun := {
      (version: Version) =>
        Bump.Minor.bump.apply(version.toSbtRelease).toCoursier
    },
    platformRunMiMa := {
      val currentVersion = platformCurrentVersion.value
      val previousVersion = platformLatestPublishedVersion.value
      mimaFailOnProblem := currentVersion.items.head > previousVersion.items.head
      val canBreakCompat = version.value.startsWith("0.")
      if (canBreakCompat && mimaPreviousArtifacts.value.isEmpty)
        sys.error(Feedback.forceDefinitionOfPreviousArtifacts)
      mimaReportBinaryIssues.value
    },
    platformReleaseNotesDir := baseDirectory.value / "notes",
    platformGetReleaseNotes := {
      val mdFile = s"${version.value}.md"
      val markdownFile = s"${version.value}.markdown"
      val notes = List(mdFile, markdownFile).foldLeft("") { (acc, curr) =>
        if (acc.nonEmpty) acc
        else {
          val presumedFile = platformReleaseNotesDir.value / curr
          if (!presumedFile.exists) acc
          else IO.read(presumedFile)
        }
      }
      if (notes.isEmpty)
        platformLogger.value.warn(Feedback.emptyReleaseNotes)
      notes
    },
    platformGitHubRepo := {
      releaseVcs.value match {
        case Some(g: Git) =>
          // Fetch git endpoint automatically
          if (g.trackingRemote.isEmpty) sys.error(Feedback.incorrectGitHubRepo)
          val trackingRemote = g.trackingRemote
          val p = g.cmd("config", "remote.%s.url" format trackingRemote)
          val gitResult = p.!!.trim
          gitResult match {
            case GitHubReleaser.SshGitHubUrl(org, repo) => Some(org, repo)
            case GitHubReleaser.HttpsGitHubUrl(org, repo) => Some(org, repo)
            case _ =>
              sys.error(Feedback.incorrectGitHubUrl(trackingRemote, gitResult))
          }
        case Some(vcs) => sys.error("Only git is supported for now.")
        case None => None
      }
    },
    scmInfo := {
      scmInfo.value.orElse {
        platformGitHubRepo.value.map { t =>
          val (org, repo) = t
          val gitHubUrl = GitHubReleaser.generateGitHubUrl(org, repo)
          ScmInfo(url(gitHubUrl), s"scm:git:$gitHubUrl")
        }
      }
    },
    platformReleaseToGitHub := {
      def createReleaseInGitHub(org: String, repo: String, token: String) = {
        val endpoint = GitHubEndpoint(org, repo, token)
        val notes = platformGetReleaseNotes.value
        val releaseVersion = Version(version.value)
        platformLogger.value.info(
          s"Releasing $releaseVersion to GitHub($org, $repo, $token)")
        val release = GitHubRelease(releaseVersion, notes)
        endpoint.pushRelease(release)
      }

      // TODO(jvican): Change environment name in Drone
      val tokenEnvName = "GITHUB_PLATFORM_TEST_TOKEN"
      val githubToken = sys.env.get(tokenEnvName)
      githubToken match {
        case Some(token) =>
          val (org, repo) = platformGitHubRepo.value.getOrElse(
            sys.error(Feedback.incorrectGitHubRepo))
          createReleaseInGitHub(org, repo, token)
        case None =>
          sys.error(Feedback.undefinedEnvironmentVariable(tokenEnvName))
      }
    },
    platformSignArtifact := true,
    platformCustomRings := None,
    platformDefaultPublicRingName := "platform.pubring.asc",
    platformDefaultPrivateRingName := "platform.secring.asc",
    pgpSigningKey := {
      val PlatformPgpKey = "11BCFDCC60929524"
      if (platformSignArtifact.value) {
        Some(new java.math.BigInteger(PlatformPgpKey, 16).longValue)
      } else None
    },
    pgpPassphrase := {
      if (platformSignArtifact.value)
        sys.env.get("PLATFORM_PGP_PASSPHRASE").map(_.toCharArray)
      else None
    },
    pgpPublicRing := {
      if (platformSignArtifact.value) {
        Helper.getPgpRingFile(platformCiEnvironment.value,
          platformCustomRings.value,
          platformDefaultPublicRingName.value)
      } else pgpPublicRing.value
    },
    pgpSecretRing := {
      if (platformSignArtifact.value) {
        Helper.getPgpRingFile(platformCiEnvironment.value,
          platformCustomRings.value,
          platformDefaultPrivateRingName.value)
      } else pgpSecretRing.value
    },
    platformBeforePublishHook := {},
    platformAfterPublishHook := {},
    commands += PlatformReleaseProcess.releaseCommand
  ) ++ PlatformReleaseProcess.aliases

  object Helper {
    implicit class XtensionCoursierVersion(v: Version) {
      def toSbtRelease: sbtrelease.Version = {
        val repr = v.repr
        sbtrelease.Version(repr).getOrElse(
          sys.error(Feedback.unexpectedVersionInteraction(repr)))
      }
    }

    implicit class XtensionSbtReleaseVersion(v: sbtrelease.Version) {
      def toCoursier: Version = validateVersion(v.string)
    }

    def getEnvVariable(key: String): Option[String] = sys.env.get(key)

    def getDroneEnvVariableOrDie(key: String) = {
      getEnvVariable(key).getOrElse(
        sys.error(Feedback.undefinedEnvironmentVariable(key)))
    }

    def getDroneEnvVariableOrDie[T](key: String, conversion: String => T): T = {
      getEnvVariable(key)
        .map(conversion)
        .getOrElse(sys.error(Feedback.undefinedEnvironmentVariable(key)))
    }

    def toBoolean(presumedBoolean: String) = presumedBoolean.toBoolean

    def toInt(presumedInt: String) = presumedInt.toInt

    def validateVersion(definedVersion: String): Version = {
      val validatedVersion = for {
        version <- Try(Version(definedVersion)).toOption
        // Double check that literals & qualifiers are stripped off
        if !version.items.exists(i =>
          i.isInstanceOf[Literal] || i.isInstanceOf[Qualifier])
      } yield version
      validatedVersion.getOrElse(
        sys.error(Feedback.invalidVersion(definedVersion)))
    }

    def getPublishedArtifacts(targetModule: ScalaModule): Set[ModuleID] = {
      val response = ModuleSearch.searchLatest(targetModule)
      val moduleResponse = response.map(_.map(rmod =>
        targetModule.orgId %% targetModule.artifactId % rmod.latest_version))
      moduleResponse
        .map(_.map(Set[ModuleID](_)).getOrElse(emptyModules))
        .getOrElse(emptyModules)
    }

    def getPgpRingFile(ciEnvironment: Option[CIEnvironment],
                       customRing: Option[File],
                       defaultRingFileName: String) = {
      ciEnvironment.map {
        _.rootDir / ".gnupg" / defaultRingFileName
      }.getOrElse {
        if (customRing.isEmpty) {
          val homeFolder = System.getProperty("user.home")
          if (homeFolder.isEmpty) sys.error("Define $HOME to start with.")
          else file(s"$homeFolder/.gnupg/$defaultRingFileName")
        } else customRing.get
      }
    }
  }

  object PlatformReleaseProcess {

    import ReleaseKeys._
    import sbtrelease.Utilities._
    import sbtrelease.ReleaseStateTransformations._

    // Attributes for the custom release command
    val releaseProcess = AttributeKey[String]("releaseProcess")
    val commandLineVersion = AttributeKey[Option[String]]("commandLineVersion")
    val validReleaseVersion = AttributeKey[Version]("validatedReleaseVersions")

    private def generateUbiquituousVersion(version: String, st: State) = {
      val ci = st.extract.get(platformCiEnvironment)
      val unique = ci.map(_.buildNumber.toString)
        .getOrElse(Random.nextLong.abs.toString)
      s"$version-$unique"
    }

    /** Update the SBT tasks and attribute that holds the current version value. */
    def updateCurrentVersion(definedVersion: Version, st: State): State = {
      val updated = st.extract.append(
        Seq(platformCurrentVersion := definedVersion), st)
      updated.put(validReleaseVersion, definedVersion)
    }

    val decideAndValidateVersion: ReleaseStep = { (st0: State) =>
      val logger = st0.globalLogging.full
      val userVersion = st0.get(commandLineVersion).flatten.map(validateVersion)
      val definedVersion = userVersion.getOrElse(st0.extract.get(platformSbtDefinedVersion))
      // TODO(jvican): Make sure minor and major depend on platform version
      val (st, nextVersionFun) =
        st0.extract.runTask(platformNextVersionFun, st0)
      val nextVersion = nextVersionFun.apply(definedVersion)
      logger.info(s"Current version is $definedVersion.")
      logger.info(s"Next version is set to $nextVersion.")
      updateCurrentVersion(definedVersion, st)
        .put(versions, (definedVersion.repr, nextVersion.repr))
    }

    val checkVersionIsNotPublished: ReleaseStep = { (st: State) =>
      val definedVersion = st
        .get(validReleaseVersion)
        .getOrElse(sys.error(Feedback.undefinedVersion))
      val module = st.extract.get(platformScalaModule)
      // TODO(jvican): Improve error handling here
      ModuleSearch
        .exists(module, definedVersion)
        .flatMap { exists =>
          if (!exists) Xor.right(st)
          else Xor.left(Error(Feedback.versionIsAlreadyPublished(definedVersion)))
        }
        .fold(e => sys.error(e.msg), identity)
    }

    import ReleaseKeys._

    object PlatformParseResult {
      case class ReleaseProcess(value: String) extends ParseResult
    }

    import sbt.complete.DefaultParsers.{Space, token, StringBasic}

    val releaseProcessToken = "release-process"
    val ReleaseProcess: Parser[ParseResult] =
      (Space ~> token("release-process") ~> Space ~> token(
        StringBasic,
        "<nightly | stable>")) map PlatformParseResult.ReleaseProcess

    val releaseParser: Parser[Seq[ParseResult]] = {
      (ReleaseProcess ~ (ReleaseVersion | SkipTests | CrossBuild).*).map {
        args =>
          val (mandatoryArg, optionalArgs) = args
          mandatoryArg +: optionalArgs
      }
    }

    val FailureCommand = "--failure--"
    val releaseCommand: Command =
      Command("releaseModule")(_ => releaseParser) { (st, args) =>
        val logger = st.globalLogging.full
        val extracted = Project.extract(st)
        val crossEnabled = extracted.get(releaseCrossBuild) ||
          args.contains(ParseResult.CrossBuild)
        val selectedReleaseProcess = args.collectFirst {
          case PlatformParseResult.ReleaseProcess(value) => value
        }.getOrElse(Feedback.missingReleaseProcess)

        val startState = st
          .copy(onFailure = Some(FailureCommand))
          .put(releaseProcess, selectedReleaseProcess)
          .put(skipTests, args.contains(ParseResult.SkipTests))
          .put(cross, crossEnabled)
          .put(commandLineVersion, args.collectFirst {
            case ParseResult.ReleaseVersion(value) => value
          })

        val releaseParts = selectedReleaseProcess.toLowerCase match {
          case "nightly" =>
            logger.info("Nightly release process has been selected.")
            extracted.get(platformNightlyReleaseProcess)
          case "stable" =>
            logger.info("Stable release process has been selected.")
            extracted.get(platformStableReleaseProcess)
          case rp => sys.error(Feedback.unexpectedReleaseProcess(rp))
        }

        val initialChecks = releaseParts.map(_.check)
        val process = releaseParts.map { step =>
          if (step.enableCrossBuild && crossEnabled) {
            filterFailure(
              ReleaseStateTransformations.runCrossBuild(step.action)) _
          } else filterFailure(step.action) _
        }

        val removeFailureCommand = { s: State =>
          s.remainingCommands match {
            case FailureCommand :: tail => s.copy(remainingCommands = tail)
            case _ => s
          }
        }
        initialChecks.foreach(_ (startState))
        Function.chain(process :+ removeFailureCommand)(startState)
      }

    val aliases = {
      // Aliases that use the custom release command
      PlatformReleaseProcess.Nightly.releaseCommand ++
        PlatformReleaseProcess.Stable.releaseCommand
    }

    object Nightly {
      val tagAsNightly: ReleaseStep = { (st0: State) =>
        val (st, logger) = st0.extract.runTask(platformLogger, st0)
        val targetVersion = st
          .get(validReleaseVersion)
          .getOrElse(sys.error(Feedback.validVersionNotFound))
        val now = DateTime.now()
        val month = now.dayOfMonth().get
        val day = now.monthOfYear().get
        val year = now.year().get
        val template = s"${targetVersion.repr}-alpha-$year-$month-$day"
        val nightlyVersion =
          if (!platform.testing) template
          else generateUbiquituousVersion(template, st)
        val generatedVersion = targetVersion.copy(nightlyVersion)
        logger.info(s"Nightly version is set to ${generatedVersion.repr}.")
        val previousVersions =
          st.get(versions).getOrElse(sys.error(Feedback.undefinedVersion))
        updateCurrentVersion(generatedVersion, st)
          .put(versions, (generatedVersion.repr, previousVersions._2))
      }

      val releaseCommand =
        addCommandAlias("releaseNightly",
          "releaseModule release-process nightly")

      val releaseProcess = {
        Seq[ReleaseStep](
          decideAndValidateVersion,
          tagAsNightly,
          checkVersionIsNotPublished,
          setReleaseVersion,
          releaseStepTask(platformValidatePomData),
          checkSnapshotDependencies,
          runTest,
          releaseStepTask(platformRunMiMa),
          releaseStepTask(platformBeforePublishHook),
          publishArtifacts,
          releaseStepTask(platformAfterPublishHook),
          releaseStepTask(bintrayRelease)
        )
      }
    }

    object Stable {
      def cleanUpTag(tag: String): String =
        if (tag.startsWith("v")) tag.replaceFirst("v", "") else tag

      val setVersionFromGitTag: ReleaseStep = { (st: State) =>
        val logger = st.globalLogging.full
        val commandLineDefinedVersion = st.get(commandLineVersion)
        // Command line version always takes precedence
        val specifiedVersion = commandLineDefinedVersion.flatten match {
          case Some(version) if version.nonEmpty => version
          case None =>
            val ciInfo = st.extract.get(platformCiEnvironment)
            ciInfo.map(e => e.tag) match {
              case Some(Some(versionTag)) => versionTag
              case Some(None) => sys.error(Feedback.expectedGitTag)
              case None => sys.error(Feedback.onlyCiCommand("releaseStable"))
            }
        }

        // TODO(jvican): Separate testing from main logic
        val stableVersion = if (platform.testing) {
          generateUbiquituousVersion(cleanUpTag(specifiedVersion), st)
        } else cleanUpTag(specifiedVersion)
        logger.info(s"Version read from the git tag: $stableVersion")
        st.put(commandLineVersion, Some(stableVersion))
      }

      val releaseCommand =
        addCommandAlias("releaseStable",
          "releaseModule release-process stable")

      val releaseProcess = {
        Seq[ReleaseStep](
          setVersionFromGitTag,
          decideAndValidateVersion,
          checkVersionIsNotPublished,
          setReleaseVersion,
          releaseStepTask(platformValidatePomData),
          checkSnapshotDependencies,
          runTest,
          releaseStepTask(platformRunMiMa),
          releaseStepTask(platformBeforePublishHook),
          publishArtifacts,
          releaseStepTask(platformAfterPublishHook),
          releaseStepTask(bintrayRelease)
        )
      }

    }

  }

}
