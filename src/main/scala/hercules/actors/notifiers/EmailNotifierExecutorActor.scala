package hercules.actors.notifiers

import akka.actor.Props
import hercules.actors.HerculesActor
import hercules.config.notification.EmailNotificationConfig
import hercules.entities.notification.EmailNotificationUnit
import hercules.protocols.HerculesMainProtocol
import scala.util.{Success, Failure}

object EmailNotifierExecutorActor {
  
  def props(emailConfig: EmailNotificationConfig): Props = {
    Props(new EmailNotifierExecutorActor(emailConfig))
  }
  
}

class EmailNotifierExecutorActor(
  emailConfig: EmailNotificationConfig) extends HerculesActor {
  
  import HerculesMainProtocol._
  import context.dispatcher
  
  def receive = {
    case message: SendNotificationUnitMessage => {
      log.info(self.getClass().getName() + " received a " + message.getClass().getName() + " with a " + message.unit.getClass().getName())
      message.unit match {
        case unit: EmailNotificationUnit => {
          // Keep the reference to sender available for the future
          val parentActor = sender
          // If we manage to send the message, send a confirmation
          val emailDelivery = EmailNotificationUnit.sendNotification(
          	unit,
          	emailConfig.emailRecipients,
          	emailConfig.emailSender,
          	emailConfig.emailPrefix,
          	emailConfig.emailSMTPHost,
          	emailConfig.emailSMTPPort
          )
          emailDelivery onComplete {
          	case Success(_) => {
          		log.info(self.getClass().getName() + " sent a " + unit.getClass().getName() + " successfully")
            	parentActor ! SentNotificationUnitMessage(unit)
            }
            case Failure(t) => {
          		log.info(self.getClass().getName() + " failed sending a " + unit.getClass().getName() + " for the " + (unit.attempts+1) + " time")
            	parentActor ! FailedNotificationUnitMessage(unit.copy(attempts = unit.attempts + 1),t.getMessage)
            }
          }
        }
        case message => {
          log.info(self.getClass().getName() + " received a " + message.getClass().getName() + " message: " + message.toString() + " and ignores it")
        }
      } 
    }
    case message => {
      log.info(self.getClass().getName() + " received a " + message.getClass().getName() + " message: " + message.toString() + " and ignores it")
    }
  }
} 
