package hercules.actors.demultiplexing

import java.io.File
import java.net.URI
import scala.collection.JavaConversions.asScalaBuffer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.AddressFromURIString
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.RootActorPath
import akka.actor.actorRef2Scala
import akka.cluster.Cluster
import akka.contrib.pattern.ClusterClient
import akka.contrib.pattern.ClusterSingletonManager
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import hercules.config.processingunit.IlluminaProcessingUnitConfig
import hercules.entities.illumina.HiSeqProcessingUnit
import hercules.entities.illumina.IlluminaProcessingUnit
import hercules.protocols.HerculesMainProtocol
import com.typesafe.config.Config
import akka.contrib.pattern.ClusterReceptionistExtension
import scala.concurrent.duration._
import akka.contrib.pattern.ClusterClient.SendToAll
import akka.japi.Util.immutableSeq
import akka.contrib.pattern.ClusterReceptionist
import hercules.test.utils.FakeMaster

class IlluminaDemultiplexingActorTest extends TestKit(
  ActorSystem(
    "ClusterSystem",
    ConfigFactory.parseString("""akka.remote.netty.tcp.port=1337""").
      withFallback(ConfigFactory.parseString("""remote.netty.tcp.hostname=127.0.0.1""")).
      withFallback(ConfigFactory.parseString("""seed-nodes = ["akka.tcp://ClusterSystem@127.0.0.1:1337"]""")).
      withFallback(ConfigFactory.load())))
    with FlatSpecLike
    with BeforeAndAfterAll
    with Matchers {

  val runfolder = new File("runfolder1")
  val processingUnit: IlluminaProcessingUnit =
    new HiSeqProcessingUnit(
      new IlluminaProcessingUnitConfig(
        new File("Samplesheet1"),
        new File("DefaultQC"),
        Some(new File("DefaultProg"))),
      runfolder.toURI())

  val generalConfig = ConfigFactory.load()

  val masterSystem: ActorSystem = {
    val config =
      ConfigFactory.
        parseString(
          """
    		akka {
    			remote.netty.tcp.port=2551
    			remote.netty.tcp.hostname=127.0.0.1  
    			cluster.roles=["master"]
    
    			cluster {
    				seed-nodes = ["akka.tcp://ClusterSystem@127.0.0.1:2551"]
    				auto-down-unreachable-after = 10s
    			}
    		}   
    		contact-points = ["akka.tcp://ClusterSystem@127.0.0.1:2551"]            
            """).
          withFallback(generalConfig)
    ActorSystem("ClusterSystem", config)
  }

  object FakeExecutor {
    def props(): Props = {
      Props(new FakeExecutor())
    }
  }

  class FakeExecutor extends DemultiplexingExecutorActor {
    def receive = {
      case _ => log.info("Got a message in the FakeExecutor")
    }
  }

  // Create a fake master
  masterSystem.actorOf(
    ClusterSingletonManager.props(
      FakeMaster.props(testActor),
      "active",
      PoisonPill,
      Some("master")),
    "master")

  val initialContacts = List("akka.tcp://ClusterSystem@127.0.0.1:2551").map {
    case AddressFromURIString(addr) => masterSystem.actorSelection(RootActorPath(addr) / "user" / "receptionist")
  }.toSet

  masterSystem.actorOf(ClusterClient.props(initialContacts), "clusterClient")
  val clusterClient = masterSystem.actorOf(ClusterClient.props(initialContacts))

  val watcherConfig =
    ConfigFactory.
      parseString(
        """
      		remote.netty.tcp.port=2552
			remote.netty.tcp.hostname=127.0.0.1  
            """).
        withFallback(generalConfig)

  val fakeExecutor = FakeExecutor.props()

  val demultiplexer = IlluminaDemultiplexingActor.
    startIlluminaDemultiplexingActor(
      system = masterSystem,
      executor = fakeExecutor,
      clusterClientCustomConfig = () => watcherConfig,
      getClusterClient = (_, _) => clusterClient)

  override def afterAll(): Unit = {
    system.shutdown()
    masterSystem.shutdown()
    Thread.sleep(1000)
  }

  "A IlluminaDemultiplexingActorTest" should " pass RequestDemultiplexingProcessingUnitMessage on to the Master" in {

    demultiplexer ! HerculesMainProtocol.RequestDemultiplexingProcessingUnitMessage()

    within(20.seconds) {
      expectMsg(FakeMaster.MasterWrapped(HerculesMainProtocol.RequestDemultiplexingProcessingUnitMessage))
    }
  }

  it should "acknowledge request to start demultiplexing if it can find the unit" in {

    // Create the dir so that it can be found.
    runfolder.mkdirs()
    demultiplexer.tell(HerculesMainProtocol.StartDemultiplexingProcessingUnitMessage(processingUnit), testActor)

    within(10.seconds) {
      expectMsg(HerculesMainProtocol.Acknowledge)
    }

    runfolder.delete()
  }

  it should "reject request to start demultiplexing if it cannot find the unit" in {
    demultiplexer.tell(HerculesMainProtocol.StartDemultiplexingProcessingUnitMessage(processingUnit), testActor)

    within(10.seconds) {
      expectMsg(HerculesMainProtocol.Reject)
    }
  }

  it should "pass any FinishedDemultiplexingProcessingUnitMessage on to the master" in {

    demultiplexer.tell(HerculesMainProtocol.FinishedDemultiplexingProcessingUnitMessage(processingUnit), testActor)

    within(10.seconds) {
      expectMsg(FakeMaster.MasterWrapped(HerculesMainProtocol.FinishedDemultiplexingProcessingUnitMessage(processingUnit)))
    }

  }
}