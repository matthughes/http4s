/*
 * Copyright 2019 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.ember.server.internal

import cats._
import cats.effect._
import cats.effect.kernel.Resource
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.Stream
import fs2.io.net._
import fs2.io.net.tls._
import fs2.io.net.unixsocket.UnixSocketAddress
import fs2.io.net.unixsocket.UnixSockets
import org.http4s._
import org.http4s.ember.core.Drain
import org.http4s.ember.core.EmberException
import org.http4s.ember.core.Encoder
import org.http4s.ember.core.Parser
import org.http4s.ember.core.Read
import org.http4s.ember.core.Util._
import org.http4s.headers.Connection
import org.http4s.headers.Date
import org.http4s.internal.tls.deduceKeyLength
import org.http4s.internal.tls.getCertChain
import org.http4s.server.SecureSession
import org.http4s.server.ServerRequestKeys
import org.http4s.websocket.WebSocketContext
import org.typelevel.log4cats.Logger
import org.typelevel.vault.Key
import org.typelevel.vault.Vault
import scodec.bits.ByteVector
import org.http4s.ember.core.h2.H2Server
import org.http4s.ember.core.h2.H2Frame

import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

private[server] object ServerHelpers {

  private val serverFailure =
    Response(Status.InternalServerError).putHeaders(org.http4s.headers.`Content-Length`.zero)

  def server[F[_]](
      host: Option[Host],
      port: Port,
      additionalSocketOptions: List[SocketOption],
      sg: SocketGroup[F],
      httpApp: HttpApp[F],
      tlsInfoOpt: Option[(TLSContext[F], TLSParameters)],
      ready: Deferred[F, Either[Throwable, InetSocketAddress]],
      shutdown: Shutdown[F],
      // Defaults
      errorHandler: Throwable => F[Response[F]],
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit],
      maxConnections: Int,
      receiveBufferSize: Int,
      maxHeaderSize: Int,
      requestHeaderReceiveTimeout: Duration,
      idleTimeout: Duration,
      logger: Logger[F],
      webSocketKey: Key[WebSocketContext[F]],
      enableHttp2: Boolean
  )(implicit F: Async[F]): Stream[F, Nothing] = {
    val server: Stream[F, Socket[F]] =
      Stream
        .resource(sg.serverResource(host, Some(port), additionalSocketOptions))
        .attempt
        .evalTap(e => ready.complete(e.map(_._1.toInetSocketAddress)))
        .rethrow
        .flatMap(_._2)
    serverInternal(
      server,
      httpApp: HttpApp[F],
      tlsInfoOpt: Option[(TLSContext[F], TLSParameters)],
      shutdown: Shutdown[F],
      // Defaults
      errorHandler: Throwable => F[Response[F]],
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit],
      maxConnections: Int,
      receiveBufferSize: Int,
      maxHeaderSize: Int,
      requestHeaderReceiveTimeout: Duration,
      idleTimeout: Duration,
      logger: Logger[F],
      true,
      webSocketKey,
      enableHttp2
    )
  }

  def unixSocketServer[F[_]: Async](
      unixSockets: UnixSockets[F],
      unixSocketAddress: UnixSocketAddress,
      deleteIfExists: Boolean,
      deleteOnClose: Boolean,
      httpApp: HttpApp[F],
      tlsInfoOpt: Option[(TLSContext[F], TLSParameters)],
      ready: Deferred[F, Either[Throwable, InetSocketAddress]],
      shutdown: Shutdown[F],
      // Defaults
      errorHandler: Throwable => F[Response[F]],
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit],
      maxConnections: Int,
      receiveBufferSize: Int,
      maxHeaderSize: Int,
      requestHeaderReceiveTimeout: Duration,
      idleTimeout: Duration,
      logger: Logger[F],
      webSocketKey: Key[WebSocketContext[F]],
      enableHttp2: Boolean,
  ): Stream[F, Nothing] = {
    val server =
      // Our interface has an issue
      Stream
        .eval(
          ready.complete( // This is a lie, there isn't any signal from fs2 when the server is actually ready
            Either.right(InetSocketAddress.createUnresolved(unixSocketAddress.path, 0))
          )
        ) // Sketchy
        .drain ++
        unixSockets
          .server(unixSocketAddress, deleteIfExists, deleteOnClose)

    serverInternal(
      server,
      httpApp: HttpApp[F],
      tlsInfoOpt: Option[(TLSContext[F], TLSParameters)],
      shutdown: Shutdown[F],
      // Defaults
      errorHandler: Throwable => F[Response[F]],
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit],
      maxConnections: Int,
      receiveBufferSize: Int,
      maxHeaderSize: Int,
      requestHeaderReceiveTimeout: Duration,
      idleTimeout: Duration,
      logger: Logger[F],
      false,
      webSocketKey,
      enableHttp2
    )
  }

  def serverInternal[F[_]: Async](
      server: Stream[F, Socket[F]],
      httpApp: HttpApp[F],
      tlsInfoOpt: Option[(TLSContext[F], TLSParameters)],
      shutdown: Shutdown[F],
      // Defaults
      errorHandler: Throwable => F[Response[F]],
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit],
      maxConnections: Int,
      receiveBufferSize: Int,
      maxHeaderSize: Int,
      requestHeaderReceiveTimeout: Duration,
      idleTimeout: Duration,
      logger: Logger[F],
      createRequestVault: Boolean,
      webSocketKey: Key[WebSocketContext[F]],
      enableHttp2: Boolean,
  ): Stream[F, Nothing] = {
    val streams: Stream[F, Stream[F, Nothing]] = server
      .interruptWhen(shutdown.signal.attempt)
      .map { connect =>
        val handler: Stream[F, fs2.INothing] = shutdown.trackConnection >>
          Stream
            .resource(upgradeSocket(connect, tlsInfoOpt, logger, enableHttp2))
            .flatMap{ 
              case (socket, Some("h2")) => 
                // ALPN H2 Strategy
                Stream.exec(H2Server.requireConnectionPreface(socket)) ++
                Stream.resource(H2Server.fromSocket[F](socket, httpApp, H2Frame.Settings.ConnectionSettings.default))
                  .drain
              case (socket, Some(_)) =>
                runConnection(
                  socket,
                  logger,
                  idleTimeout,
                  receiveBufferSize,
                  maxHeaderSize,
                  requestHeaderReceiveTimeout,
                  httpApp,
                  errorHandler,
                  onWriteFailure,
                  createRequestVault,
                  webSocketKey,
                  ByteVector.empty
                ).drain
              case (socket, None) => // Cleartext Protocol
                enableHttp2 match {
                  case true => 
                    // Http2 Prior Knowledge Check, if prelude is first bytes received tread as http2
                    // Otherwise this is now http1
                    Stream.eval(H2Server.checkConnectionPreface(socket)).flatMap{
                      case Left(bv) => 
                        runConnection(
                          socket,
                          logger,
                          idleTimeout,
                          receiveBufferSize,
                          maxHeaderSize,
                          requestHeaderReceiveTimeout,
                          httpApp,
                          errorHandler,
                          onWriteFailure,
                          createRequestVault,
                          webSocketKey,
                          ByteVector.empty
                        ).drain
                      case Right(_) => 
                        Stream.resource(H2Server.fromSocket[F](socket, httpApp, H2Frame.Settings.ConnectionSettings.default))
                          .drain
                    }
                  case false => 
                    runConnection(
                      socket,
                      logger,
                      idleTimeout,
                      receiveBufferSize,
                      maxHeaderSize,
                      requestHeaderReceiveTimeout,
                      httpApp,
                      errorHandler,
                      onWriteFailure,
                      createRequestVault,
                      webSocketKey,
                      ByteVector.empty
                    ).drain
                }
            }

        handler.handleErrorWith{ t =>
          Stream.eval(logger.error(t)("Request handler failed with exception")).drain
        }
      }

    streams.parJoin(
      maxConnections
    ) // TODO: replace with forking after we fix serverResource upstream
    // StreamForking.forking(streams, maxConnections)
  }

  // private[internal] def reachedEndError[F[_]: Sync](
  //     socket: Socket[F],
  //     idleTimeout: Duration,
  //     receiveBufferSize: Int): Stream[F, Byte] =
  //   Stream.repeatEval(socket.read(receiveBufferSize, durationToFinite(idleTimeout))).flatMap {
  //     case None =>
  //       Stream.raiseError(new EOFException("Unexpected EOF - socket.read returned None") with NoStackTrace)
  //     case Some(value) => Stream.chunk(value)
  //   }

  private[internal] def upgradeSocket[F[_]: Monad](
      socketInit: Socket[F],
      tlsInfoOpt: Option[(TLSContext[F], TLSParameters)],
      logger: Logger[F],
      enableHttp2: Boolean,
  ): Resource[F, (Socket[F], Option[String])] =
    tlsInfoOpt.fold((socketInit, Option.empty[String]).pure[Resource[F, *]]) { case (context, params) =>
      val newParams = if (enableHttp2) {
        TLSParameters(
          algorithmConstraints = params.algorithmConstraints,
          applicationProtocols = List("http/1.1", "h2").some,
          cipherSuites = params.cipherSuites,
          enableRetransmissions = params.enableRetransmissions,
          endpointIdentificationAlgorithm = params.endpointIdentificationAlgorithm,
          maximumPacketSize = params.maximumPacketSize,
          protocols = params.protocols, 
          serverNames = params.serverNames,
          sniMatchers = params.sniMatchers,
          useCipherSuitesOrder = params.useCipherSuitesOrder,
          needClientAuth = params.needClientAuth,
          wantClientAuth = params.wantClientAuth,
          handshakeApplicationProtocolSelector = {(t: javax.net.ssl.SSLEngine, l:List[String])  => 
            l.find(_ === "h2").getOrElse("http/1.1")
          }.some
        )
      } else params
      
      context
        .serverBuilder(socketInit)
        .withParameters(newParams)
        .withLogging(s => logger.trace(s))
        .build
        .evalMap(tlsSocket => 
            tlsSocket.write(fs2.Chunk.empty) >>
            tlsSocket.applicationProtocol.map(protocol => (tlsSocket: Socket[F], protocol.some))
        )
    }

  private[internal] def runApp[F[_]](
      head: Array[Byte],
      read: Read[F],
      maxHeaderSize: Int,
      requestHeaderReceiveTimeout: Duration,
      httpApp: HttpApp[F],
      errorHandler: Throwable => F[Response[F]],
      socket: Socket[F],
      createRequestVault: Boolean,
  )(implicit F: Temporal[F], D: Defer[F]): F[(Request[F], Response[F], Drain[F])] = {

    val parse = Parser.Request.parser(maxHeaderSize)(head, read)
    val parseWithHeaderTimeout = timeoutToMaybe(
      parse,
      requestHeaderReceiveTimeout,
      D.defer(
        F.raiseError[(Request[F], F[Option[Array[Byte]]])](
          EmberException.RequestHeadersTimeout(requestHeaderReceiveTimeout)
        )
      ),
    )

    for {
      tmp <- parseWithHeaderTimeout
      (req, drain) = tmp
      requestVault <- if (createRequestVault) mkRequestVault(socket) else Vault.empty.pure[F]
      resp <- httpApp
        .run(req.withAttributes(requestVault))
        .handleErrorWith(errorHandler)
        .handleError(_ => serverFailure.covary[F])
    } yield (req, resp, drain)
  }

  private[internal] def send[F[_]: Temporal](socket: Socket[F])(
      request: Option[Request[F]],
      resp: Response[F],
      idleTimeout: Duration,
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit],
  ): F[Unit] =
    Encoder
      .respToBytes[F](resp)
      .through(_.chunks.foreach(c => timeoutMaybe(socket.write(c), idleTimeout)))
      .compile
      .drain
      .attempt
      .flatMap {
        case Left(err) => onWriteFailure(request, resp, err)
        case Right(()) => Applicative[F].unit
      }

  private[internal] def postProcessResponse[F[_]: Concurrent: Clock](
      req: Request[F],
      resp: Response[F],
  ): F[Response[F]] = {
    val connection = connectionFor(req.httpVersion, req.headers)
    for {
      date <- HttpDate.current[F].map(Date(_))
    } yield resp.withHeaders(Headers(date, connection) ++ resp.headers)
  }

  private[internal] def runConnection[F[_]: Async](
      socket: Socket[F],
      logger: Logger[F],
      idleTimeout: Duration,
      receiveBufferSize: Int,
      maxHeaderSize: Int,
      requestHeaderReceiveTimeout: Duration,
      httpApp: HttpApp[F],
      errorHandler: Throwable => F[org.http4s.Response[F]],
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit],
      createRequestVault: Boolean,
      webSocketKey: Key[WebSocketContext[F]],
      initialBuffer: ByteVector,
  ): Stream[F, Nothing] = {
    type State = (Array[Byte], Boolean)
    val _ = logger
    val read: Read[F] = timeoutMaybe(socket.read(receiveBufferSize), idleTimeout)
      .adaptError {
        // TODO MERGE: Replace with TimeoutException on series/0.23+.
        case _: TimeoutException => EmberException.ReadTimeout(idleTimeout)
      }
    Stream
      .unfoldEval[F, State, (Request[F], Response[F])](initialBuffer.toArray -> false) {
        case (buffer, reuse) =>
          val initRead: F[Array[Byte]] = if (buffer.nonEmpty) {
            // next request has already been (partially) received
            buffer.pure[F]
          } else if (reuse) {
            // the connection is keep-alive, but we don't have any bytes.
            // we want to be on the idle timeout until the next request is received.
            read
              .flatMap {
                case Some(chunk) => chunk.toArray.pure[F]
                case None => Concurrent[F].raiseError(EmberException.EmptyStream())
              }
          } else {
            // first request begins immediately
            Array.emptyByteArray.pure[F]
          }

          val result = initRead.flatMap { initBuffer =>
            runApp(
              initBuffer,
              read,
              maxHeaderSize,
              requestHeaderReceiveTimeout,
              httpApp,
              errorHandler,
              socket,
              createRequestVault,
            )
          }

          result.attempt.flatMap {
            case Right((req, resp, drain)) =>
              // TODO: Should we pay this cost for every HTTP request?
              // Intercept the response for various upgrade paths
              resp.attributes.lookup(webSocketKey) match {
                case Some(ctx) =>
                  drain.flatMap {
                    case Some(buffer) =>
                      WebSocketHelpers
                        .upgrade(
                          socket,
                          req,
                          ctx,
                          buffer,
                          receiveBufferSize,
                          idleTimeout,
                          onWriteFailure,
                          errorHandler,
                          logger,
                        )
                        .as(None)
                    case None =>
                      Applicative[F].pure(None)
                  }
                case None =>
                  for {
                    nextResp <- postProcessResponse(req, resp)
                    _ <- send(socket)(Some(req), nextResp, idleTimeout, onWriteFailure)
                    nextBuffer <- drain
                  } yield nextBuffer.map(buffer => ((req, nextResp), (buffer, true)))
              }
            case Left(err) =>
              err match {
                case EmberException.EmptyStream() | EmberException.RequestHeadersTimeout(_) |
                    EmberException.ReadTimeout(_) =>
                  Applicative[F].pure(None)
                case err =>
                  errorHandler(err)
                    .handleError(_ => serverFailure.covary[F])
                    .flatMap(send(socket)(None, _, idleTimeout, onWriteFailure))
                    .as(None)
              }
          }
      }
      .takeWhile { case (_, resp) =>
        resp.headers.get[Connection].exists(_.hasKeepAlive)
      }
      .drain
  }

  private def mkRequestVault[F[_]: Applicative](socket: Socket[F]): F[Vault] =
    (mkConnectionInfo(socket), mkSecureSession(socket)).mapN(_ ++ _)

  private def mkConnectionInfo[F[_]: Apply](socket: Socket[F]) =
    (socket.localAddress, socket.remoteAddress).mapN { case (local, remote) =>
      Vault.empty.insert(
        Request.Keys.ConnectionInfo,
        Request.Connection(
          local = local,
          remote = remote,
          secure = socket.isInstanceOf[TLSSocket[F]],
        ),
      )
    }

  private def mkSecureSession[F[_]: Applicative](socket: Socket[F]) =
    socket match {
      case socket: TLSSocket[F] =>
        socket.session
          .map { session =>
            (
              Option(session.getId).map(ByteVector(_).toHex),
              Option(session.getCipherSuite),
              Option(session.getCipherSuite).map(deduceKeyLength),
              Some(getCertChain(session)),
            ).mapN(SecureSession.apply)
          }
          .map(Vault.empty.insert(ServerRequestKeys.SecureSession, _))
      case _ =>
        Vault.empty.pure[F]
    }
}
