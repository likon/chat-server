
package chat.server

import java.net.InetSocketAddress
import scala.collection.immutable.Queue
import scala.concurrent.duration._
import akka.io.{Tcp,IO}
import akka.util.{ByteString,Timeout}
import akka.actor._

abstract class ChatConnection(tcpConnection: ActorRef) extends Actor with ActorLogging {
  context.watch(tcpConnection)

  var queue = Queue.empty
  object SentOk extends Tcp.Event

  def handleReceive(data: ByteString): Unit

  def sendData(data:ByteString) = {
    if queue.isEmpty {
      tcpConnection ! Tcp.Write(data, ack = SentOk)
      context.become(waitingForAck)
    } else {
      queue.enqueue(data)
      context.become(waitingForAckWithQueueData)
    }
    tcpConnection ! Tcp.Write(data)
  }

  def receive = idle

  def idle: Receive = stopOnConnectionTermination orElse {
    case Tcp.Received(data) =>
      log.info("Receive data {}", data)
      handleReceive(data)

    case x:Tcp.ConnectionClosed =>
      log.debug("Connection closed: {}", x)
      context.stop(self)
  }

  def waitingForAck: Receive = stopOnConnectionTermination orElse {
    case Tcp.Received(data) =>
      tcpConnection ! Tcp.SuspendReading
      log.info("Receive data {}", data)
      handleReceive(data)

    case SentOk =>
      context.become(idle)

    case x: Tcp.ConnectionClosed =>
      log.debug("Connection closed: {}, waiting for pending ACK", x)
      context.become(waitingForAckWithQueueData(closed = true))
  }

  def waitingForAckWithQueueData(closed: Boolean = false): Receive = stopOnConnectionTermination orElse {
    case Tcp.Received(data) =>
      log.info("Receive data {}", data)
      handleReceive(data)

    case SentOk if queue.isEmpty && closed =>
      log.debug("No more pending ACKs, stopping")
      tcpConnection ! Tcp.Close
      context.stop(self)

    case SentOk if queue.isEmpty =>
      tcpConnection ! Tcp.ResumeReading
      context.become(idle)

    case SentOk =>
      tcpConnection ! Tcp.Write(queue.head, ack = SentOk)
      context.become(waitingForAckWithQueueData(closed))

    case x: Tcp.ConnectionClosed =>
      log.debug("Connection closed: {}, waiting for completion of {}  pending writes", x, queue.size)
      context.become(waitingForAckWithQueueData(closed = true))
  }

  def stopOnConnectionTermination: Receive = {
    case Terminated(`tcpConnection`) =>
      log.debug("TCP connection actor terminated, stopping...")
      context.stop(self)
  }
}

