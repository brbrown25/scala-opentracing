package com.colisweb.tracing.context

import _root_.datadog.trace.api.DDTags.SERVICE_NAME
import _root_.datadog.trace.api.{GlobalTracer => DDGlobalTracer}
import cats.data.OptionT
import cats.effect._
import cats.syntax.all._
import com.colisweb.tracing.core._
import com.colisweb.tracing.core.logger.{PureLogger, TracingLogger}
import com.typesafe.scalalogging.StrictLogging
import datadog.opentracing.DDTracer
import io.opentracing.Span
import net.logstash.logback.marker.Markers.appendEntries
import org.slf4j.{Logger, Marker}

import scala.jdk.CollectionConverters._

/**
  * This tracing context is intended to be used with Datadog APM.
  * It adds the Service Name tag to all spans, required to see the traces in the APM
  * view of Datadog.
  * It also provides access to span id and trace id to correlate logs and traces together.
  */
class DDTracingContext[F[_]: Sync](
    protected val tracer: DDTracer,
    protected val span: Span,
    protected val serviceName: String,
    override val correlationId: String
) extends TracingContext[F] {

  def traceId: OptionT[F, String] =
    OptionT.liftF(Sync[F] delay {
      span.context().toTraceId
    })

  def spanId: OptionT[F, String] =
    OptionT.liftF(Sync[F] delay {
      span.context().toSpanId
    })

  override def span(operationName: String, tags: Tags = Map.empty): TracingContextResource[F] =
    DDTracingContext.apply[F](tracer, serviceName, Some(span), correlationId)(operationName, tags)

  override def addTags(tags: Tags): F[Unit] =
    Sync[F].delay {
      tags.foreach {
        case (key, value) => span.setTag(key, value)
      }
    }

  override def logger(implicit slf4jLogger: Logger): PureLogger[F] =
    TracingLogger.pureTracingLogger[F](slf4jLogger, markers)

  private lazy val markers: F[Marker] = {

    val traceIdMarker = traceId.map(id => Map("dd.trace_id" -> id)).getOrElse(Map.empty)
    val spanIdMarker =
      spanId.map(id => Map("dd.span_id" -> id)).getOrElse(Map.empty)
    for {
      spanId  <- spanIdMarker
      traceId <- traceIdMarker
    } yield appendEntries(
      (traceId ++ spanId).asJava
    )

  }
}

object DDTracingContext extends StrictLogging {
  def builder[F[_]: Sync](name: String): F[TracingContextBuilder[F]] = {
    for {
      tracer <- buildAndRegisterDDTracer
    } yield { (operationName: String, tags: Tags, correlationId: String) =>
      {
        DDTracingContext.apply(
          tracer = tracer,
          serviceName = name,
          correlationId = correlationId
        )(operationName, tags)
      }
    }
  }

  def apply[F[_]: Sync](
      tracer: DDTracer,
      serviceName: String,
      parentSpan: Option[Span] = None,
      correlationId: String
  )(
      operationName: String,
      tags: Tags
  ): TracingContextResource[F] =
    DDTracingContext
      .spanResource[F](tracer, operationName, parentSpan)
      .map(new DDTracingContext(tracer, _, serviceName, correlationId))
      .evalMap(ctx => ctx.addTags(tags + (SERVICE_NAME -> serviceName)).map(_ => ctx))

  private def spanResource[F[_]: Sync](
      tracer: DDTracer,
      operationName: String,
      parentSpan: Option[Span]
  ): Resource[F, Span] = {
    def acquire: F[Span] = {
      val span = tracer.buildSpan(operationName)
      val spanBuilder = parentSpan match {
        case Some(s) => span.asChildOf(s)
        case None    => span
      }
      Sync[F].delay { spanBuilder.start() }
    }

    def release(s: Span): F[Unit] = Sync[F].delay(s.finish())

    Resource.make(acquire)(release)
  }

  private def buildAndRegisterDDTracer[F[_]: Sync]: F[DDTracer] =
    for {
      tracer <- Sync[F].delay(DDTracer.builder().build())
      _ = DDGlobalTracer.registerIfAbsent(tracer)
    } yield tracer
}
