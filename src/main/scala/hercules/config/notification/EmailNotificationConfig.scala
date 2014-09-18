package hercules.config.notification

import com.typesafe.config.Config
import java.util.List
import scala.collection.JavaConversions._
import hercules.protocols.NotificationChannelProtocol._

object EmailNotificationConfig {

  /** Create and return a EmailNotificationConfig from the supplied 
   *  configuration.
   */
  def getEmailNotificationConfig(conf: Config): EmailNotificationConfig = {
    val emailRecipients = asScalaBuffer(conf.getStringList("recipients")).toSeq
    val emailSender = conf.getString("sender")
    val emailSMTPHost = conf.getString("smtp_host")
    val emailSMTPPort = conf.getInt("smtp_port")
    val emailPrefix = conf.getString("prefix")
    val emailChannels = asScalaBuffer(
      conf.getStringList("channels")
      ).toSeq.map(
        stringToChannel)
    new EmailNotificationConfig(
      emailRecipients,
      emailSender,
      emailSMTPHost,
      emailSMTPPort,
      emailPrefix,
      emailChannels
    )
  }
  
  def stringToChannel(str:String): NotificationChannel = str match {
    case "progress" => Progress
    case "info" => Info
    case "warning" => Warning
    case "critical" => Critical
  }

}

/**
 * Base class for configuring an email notification
 */
case class EmailNotificationConfig(
  val emailRecipients: Seq[String],
  val emailSender: String,
  val emailSMTPHost: String,
  val emailSMTPPort: Int,
  val emailPrefix: String,
  val channels: Seq[NotificationChannel]) extends NotificationConfig {
}
