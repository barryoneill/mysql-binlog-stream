package io.laserdisc.mysql.binlog

import cats.effect.concurrent.Ref
import cats.effect.{ ConcurrentEffect, Sync }
import cats.implicits._
import com.github.shyiko.mysql.binlog.event.Event
import event.EventMessage
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger

package object stream {
  def streamEvents[F[_]: ConcurrentEffect: SelfAwareStructuredLogger](
    transactionState: Ref[F, TransactionState]
  ): fs2.Pipe[F, Event, EventMessage] =
    _.through(streamTransactionPackages[F](transactionState)).flatMap(pkg =>
      fs2.Stream.eval(warnBigTransactionPackage(pkg)) >> fs2.Stream(pkg.events: _*)
    )

  def streamCompactedEvents[F[_]: ConcurrentEffect: SelfAwareStructuredLogger](
    transactionState: Ref[F, TransactionState]
  ): fs2.Pipe[F, Event, EventMessage] =
    _.through(streamTransactionPackages[F](transactionState)).flatMap(pkg =>
      fs2.Stream(compaction.compact(pkg.events): _*)
    )

  def streamTransactionPackages[F[_]: ConcurrentEffect: SelfAwareStructuredLogger](
    transactionState: Ref[F, TransactionState]
  ): fs2.Pipe[F, Event, TransactionPackage] =
    _.evalMap(event =>
      SelfAwareStructuredLogger[F]
        .debug(s"received binlog event $event") >> transactionState
        .modifyState(TransactionState.nextState(event))
    ).unNone

  def warnBigTransactionPackage[F[_]: Sync: SelfAwareStructuredLogger](
    transactionPackage: TransactionPackage
  ): F[Unit] =
    if (transactionPackage.events.size >= 1000)
      for {
        distro <- Sync[F].delay(transactionPackage.events.groupBy(_.table).map {
                    case (k, v) => k -> v.size
                  })
        _ <- SelfAwareStructuredLogger[F].warn(s"""Transaction has > then 1000 elements in it with
                                 |following distribution $distro
        """.stripMargin)
      } yield ()
    else
      Sync[F].unit

}
