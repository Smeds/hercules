package hercules.actors.demultiplexing

import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.japi.Util.immutableSeq
import akka.actor.Props
import akka.actor.AddressFromURIString
import akka.actor.RootActorPath
import akka.contrib.pattern.ClusterClient
import akka.actor.ActorRef
import akka.contrib.pattern.ClusterClient.SendToAll
import java.net.InetAddress
import hercules.actors.utils.MasterLookup
import akka.routing.RoundRobinRouter
import scala.concurrent.duration._
import hercules.protocols.HerculesMainProtocol
import java.io.File

object IlluminaDemultiplexingActor extends MasterLookup {

  /**
   * Initiate all the stuff needed to start a IlluminaDemultiplexingActor
   * including initiating the system.
   */
  def startIlluminaDemultiplexingActor(): Unit = {
    val (clusterClient, system) = getMasterClusterClientAndSystem(config = "IlluminaDemultiplexingActor")
    val props = IlluminaDemultiplexingActor.props(clusterClient)
    system.actorOf(props, "demultiplexer")
  }

  /**
   * Create a new IlluminaDemultiplexingActor
   * @param clusterClient A reference to a cluster client thorough which the
   *                      actor will communicate with the rest of the cluster.
   */
  def props(clusterClient: ActorRef): Props = {
    Props(new IlluminaDemultiplexingActor(clusterClient))
  }
}

/**
 * Actors which demultiplex Illumina runfolders should communitate through
 * here. A concrete executor actor (such as the SisyphusDemultiplexingActor)
 * should do the actual work.
 * @param clusterClient A reference to a cluster client thorough which the
 *                      actor will communicate with the rest of the cluster.
 */
class IlluminaDemultiplexingActor(clusterClient: ActorRef) extends DemultiplexingActor {

  import HerculesMainProtocol._

  //@TODO Make the number of demultiplexing instances started configurable.

  //@TODO Make it possible to switch executor implementation
  // Right now I'll just fix the sisyphus one. /JD 11/9 2014
  val demultiplexingRouter =
    context.actorOf(
      SisyphusDemultiplexingExecutorActor.props().
        withRouter(RoundRobinRouter(nrOfInstances = 2)),
      "SisyphusDemultiplexingExecutor")

  import context.dispatcher

  //@TODO Make request new work period configurable.
  // Request new work periodically
  val requestWork =
    context.system.scheduler.schedule(10.seconds, 10.seconds, self, {
      HerculesMainProtocol.RequestDemultiplexingProcessingUnitMessage
    })

  // Make sure that the scheduled event stops if the actors does.
  override def postStop() = {
    requestWork.cancel()
  }

  def receive = {

    case HerculesMainProtocol.RequestDemultiplexingProcessingUnitMessage =>
      log.info("Received a RequestDemultiplexingProcessingUnitMessage and passing it on to the master.")
      clusterClient ! SendToAll("/user/master/active",
        HerculesMainProtocol.RequestDemultiplexingProcessingUnitMessage)

    case message: HerculesMainProtocol.StartDemultiplexingProcessingUnitMessage => {

      //@TODO It is probably reasonable to have some other mechanism than checking if it
      // can spot the file if it can spot the file or not. But for now, this will have to do.
      log.info("Received a StartDemultiplexingProcessingUnitMessage.")

      val pathToTheRunfolder = new File(message.unit.uri)
      if (pathToTheRunfolder.exists()) {
        log.info("Found the runfolder and will acknowlede message.")
        sender ! Acknowledge
        demultiplexingRouter ! message
      }
      else
        sender ! Reject

    }

    case message: FinishedDemultiplexingProcessingUnitMessage =>
      clusterClient ! SendToAll("/user/master/active",
        message)

    case StringMessage(s) => {
      log.info("Got a StringMessage: " + s)
    }

    case _ => log.info("IlluminaDemultiplexingActor got a message!")
  }

}