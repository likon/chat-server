
package chat.server

import java.net.InetSocketAddress
import scala.concurrent.dureation._
import akka.pattern.ask
import akka.io.{Tcp, IO}
import akka.util.Timeout
import akk.actor._

class ChatServer(handler:(ActorRef=>ActorRef)) extends Actor with ActorLogging {
  var childrenCount = 0

  def receive = {
    case Tcp.Connected(_,_) =>
      val tcpConnection = sender
      val newChild = context.watch(context.actorOf(Props(handler(tcpConnection))))
      childrenCount += 1
      sender ! Tcp.Register(newChild)
      log.debug("Registered for new connection")

    case Terminated(_) if childrenCount > 0 =>
      childrenCount -= 1
      log.debug("Connection handler stopped, another {} connections open", childrenCount)

    case Terminated(_) =>
      log.debug("Last connection handler stopped, shutting down")
      context.system.shutdown()
  }
}
