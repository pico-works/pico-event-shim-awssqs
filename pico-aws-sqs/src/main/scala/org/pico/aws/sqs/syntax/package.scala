package org.pico.aws.sqs

import java.lang.{Integer => BoxedInt}
import java.util.concurrent.atomic.AtomicBoolean

import com.amazonaws.services.sqs.AmazonSQSAsync
import com.amazonaws.services.sqs.model._
import org.pico.aws.core.Async
import org.pico.disposal.SimpleDisposer
import org.pico.disposal.std.autoCloseable._
import org.pico.event.{Bus, Sink, SinkSource, Source}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

package object syntax {
  implicit class AmazonSQSOps_9xksP7M(val self: AmazonSQSAsync) extends AnyVal {
    def asyncReceive(queueUrl: String)(implicit ec: ExecutionContext): Future[SqsReceiveContext[ReceiveMessageResult]] = {
      Async.handle[ReceiveMessageRequest, ReceiveMessageResult] { handler =>
        self.receiveMessageAsync(queueUrl, handler)
      }.map(result => SqsReceiveContext(result._1, result._2))
    }

    def asyncReceive(
        receiveMessageRequest: ReceiveMessageRequest): Future[(ReceiveMessageRequest, ReceiveMessageResult)] = {
      Async.handle[ReceiveMessageRequest, ReceiveMessageResult] { handler =>
        self.receiveMessageAsync(receiveMessageRequest, handler)
      }
    }

    def doneBus[A]: Bus[SqsMessageEnvelope[A]] = {
      val bus = Bus[SqsMessageEnvelope[A]]

      bus.disposes(bus.subscribe { envelope =>
        val deleteMessageRequests = new DeleteMessageRequest(
          envelope.context.sourceQueueUrl.url, envelope.context.receiptHandle.handle)

        self.deleteMessage(deleteMessageRequests)
      })

      bus
    }

    def asyncPoll[A: SqsDecode](
        queueUrl: String,
        attributeNames: Seq[String] = Seq.empty,
        messageAttributeNames: Seq[String] = Seq.empty,
        maxNumberOfMessages: Option[Int] = None,
        visibilityTimeout: Option[Duration] = None,
        waitTimeSeconds: Option[Duration] = None,
        parallelism: Int = 1)(implicit ec: ExecutionContext): SqsPolled[A] = {
      SqsPolled.asyncPoll(
        self, queueUrl, attributeNames, messageAttributeNames,
        maxNumberOfMessages, visibilityTimeout, waitTimeSeconds, parallelism)
    }

    def asyncReceive(
        queueUrl: String,
        attributeNames: Seq[String] = Seq.empty,
        messageAttributeNames: Seq[String] = Seq.empty,
        maxNumberOfMessages: Option[Int] = None,
        visibilityTimeout: Option[Duration] = None,
        waitTimeSeconds: Option[Duration] = None): Future[(ReceiveMessageRequest, ReceiveMessageResult)] = {
      def boxed(value: Int): BoxedInt = value

      val receiveMessageRequest = new ReceiveMessageRequest {
        this.setQueueUrl(queueUrl)
        this.setAttributeNames(attributeNames.asJava)
        this.setAttributeNames(messageAttributeNames.asJava)
        this.setMaxNumberOfMessages(maxNumberOfMessages.map(boxed).orNull)
        this.setVisibilityTimeout(visibilityTimeout.map(v => boxed(v.toSeconds.toInt)).orNull)
        this.setWaitTimeSeconds(visibilityTimeout.map(v => boxed(v.toSeconds.toInt)).orNull)
      }

      Async.handle[ReceiveMessageRequest, ReceiveMessageResult] { handler =>
        self.receiveMessageAsync(receiveMessageRequest, handler)
      }
    }

    def sendTo[A: SqsEncode](
        queueUrl: String)(implicit ev: ExecutionContext): SinkSource[A, Option[Future[(A, SendMessageResult)]]] = {
      SinkSource[A, Option[Future[(A, SendMessageResult)]]] { a =>
        val message: SqsEncodedMessage = a.sqsEncode

        val maybeQueueUrl = message.queueUrl match {
          case Some(url)  => if (url == queueUrl) message.queueUrl else None
          case None       => Some(queueUrl)
        }

        maybeQueueUrl map { targetQueueUrl =>
          val request = new SendMessageRequest {
            this.setQueueUrl(queueUrl)

            message.body.foreach(body => this.setMessageBody(body.body))
          }

          Async.handle[SendMessageRequest, SendMessageResult] { handler =>
            self.sendMessageAsync(request, handler)
          }.map(v => (a, v._2))
        }
      }
    }
  }

  implicit class SqsMessageContextOps_9xksP7M[A](val self: SqsReceiveContext[Seq[A]]) extends AnyVal {
    def sequence: Seq[SqsReceiveContext[A]] = self.value.map(v => SqsReceiveContext(self.request, v))
  }

  implicit class IpOps_9xksP7M[A](val self: A) extends AnyVal {
    @inline
    def sqsEncode(implicit ev: SqsEncode[A]): SqsEncodedMessage = ev.sqsEncode(self)
  }
}
