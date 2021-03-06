import sys.process._
import ammonite.ops._

object Job extends Enumeration {
  type Job = Value
  val Index, Deploy, Test = Value
}

implicit val readCiType: scopt.Read[Job.Value] = scopt.Read.reads(Job withName _)

def runDetached(command: String): Unit = Runtime.getRuntime().exec(command)
def run(args: String*): Unit = Process(args.toList).!
def runD(args: String*)(dir: Path): Unit = Process(args.toList, Some(dir.toIO)).!
def runSlurp(args: String*): String = Process(args.toList).lineStream.toList.headOption.getOrElse("")
def runPipe(args: String*)(file: Path) = (Process(args.toList) #> file.toIO).!
def runEnv(args: String*)(envs: (String, String)*) = Process(command = args.toList, cwd = None, extraEnv = envs: _*).!

def sbt(commands: String*): Unit = {
  val jvmOpts =
    "-DELASTICSEARCH=remote" ::
    "-Xms1G" ::
    "-Xmx3G" ::
    "-XX:ReservedCodeCacheSize=256m" ::
    "-XX:+TieredCompilation" ::
    "-XX:+CMSClassUnloadingEnabled" ::
    "-XX:+UseConcMarkSweepGC" ::
    Nil

  // run index
  runEnv("./sbt", ("clean" :: commands.toList).mkString(";", " ;", ""))(("JVM_OPTS", jvmOpts.mkString(" ")))
}

def datetime = {
  import java.text.SimpleDateFormat
  import java.util.Calendar
  new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Calendar.getInstance().getTime()) 
}

def updatingSubmodules(submodules: List[Path])(f: () => Unit): Unit = {
  submodules.foreach{ submodule =>
    runD("git", "checkout", "master")(submodule)
    runD("git", "pull", "origin", "master")(submodule)
  }

  // run index
  f()


  // publish the latest data
  submodules.foreach{ submodule =>
    runD("git", "add", "-A")(submodule)
    runD("git", "commit", "-m", '"' + datetime + '"' )(submodule)
    runD("git", "push", "origin", "master")(submodule)
  }
}

@main def main(fullBranchName: String, job: Job.Value) = {
  import Job._

  val branch = {
    val origin = "origin/"
    if(fullBranchName.startsWith(origin)) fullBranchName.drop(origin.length)
    else fullBranchName
  }

  println(s"job $job")
  println(s"branch $branch")
 
  if(job == Deploy && branch != "master") {
    println("Exit 1")
    sys.exit
  }

  if(job == Test && branch == "master") {
    println("Exit 2")
    sys.exit
  }

  println("OK ...")

  if(!exists(cwd / "sbt")) {
    runPipe("curl", "-s", "https://raw.githubusercontent.com/paulp/sbt-extras/master/sbt")(cwd / "sbt")
    run("chmod", "a+x", "sbt")
  }

  val credentialsDest = home
  val credentialsFolder = credentialsDest / "scaladex-credentials"
  if(!exists(credentialsFolder)) {
    runD("git", "clone", "git@github.com:scalacenter/scaladex-credentials.git")(credentialsDest)
  } else {
    runD("git", "pull", "origin", "master")(credentialsFolder)
  }

  val bintrayCredentialsFolder = home / ".bintray"
  if(!exists(bintrayCredentialsFolder)) {
    mkdir(bintrayCredentialsFolder)
  }

  val searchCredentialsFolder = bintrayCredentialsFolder / ".credentials2"
  if(!exists(searchCredentialsFolder)){
    cp(credentialsFolder / "search-credentials", searchCredentialsFolder)
  }

  val publishPluginCredentialsFolder = bintrayCredentialsFolder / ".credentials"
  if(!exists(publishPluginCredentialsFolder)){
    cp(credentialsFolder / "sbt-plugin-credentials", publishPluginCredentialsFolder)
  }

  run("git", "submodule", "init")
  run("git", "submodule", "update")
  
  val contribFolder = cwd / "contrib"
  val indexFolder = cwd / "index"

  if(job == Index){

    updatingSubmodules(List(contribFolder, indexFolder)){ () =>
      // run index
      sbt("data/run all")
    }

  } else if(job == Test) {

    sbt("test")

  } else if(job == Deploy) {

    updatingSubmodules(List(indexFolder)){ () =>
      sbt(
        // "data/run live",
        // "data/run elastic",
        "server/universal:packageBin"
      )
    }
    
    val scaladex = home / "scaladex"
    if(!exists(scaladex)) mkdir(scaladex)

    val scaladexReleases = scaladex / "releases"
    if(!exists(scaladexReleases)) mkdir(scaladexReleases)

    val gitDescribe = runSlurp("git", "describe", "--tags")
    val destGitDescribe = scaladexReleases / gitDescribe
    if(exists(destGitDescribe)) rm(destGitDescribe)

    mkdir(destGitDescribe)

    val packageBin = cwd / "server" / "target" / "universal" / "scaladex.zip"

    run("unzip", packageBin.toString, "-d", destGitDescribe.toString)

    val current = "current"
    val currentLink = scaladex / current
    if(exists(currentLink)) {

      // kill current server if running

      val pidFile = currentLink / "PID"
      if(exists(pidFile)) {
        val pid = runSlurp("cat", "pidFile.toString")
        println(s"killing $pid")
        run("kill", pid)
      }

      rm(currentLink)
    }

    // /scaladex/current -> /scaladex/releases/1.2.3-sha
    runD("ln", "-s", destGitDescribe.toString, current)(scaladex)

    val configFile = credentialsFolder / "application.conf"

    val serverBin = (currentLink / "scaladex" / "bin" / "server").toString
    val config = "-Dconfig.file=" + configFile.toString

    val toRun = s"nohup $serverBin $config &"
    println(s"running: $toRun")

    runDetached(toRun)
  }
}