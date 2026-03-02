package com.clever.photos.api

import com.clever.photos.auth.AuthService
import com.clever.photos.config.HttpConfig
import com.clever.photos.repository.{ApiClientRepository, PhotoRepository, PhotographerRepository}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import zio.*
import zio.http.*

/** Assembles all Tapir server endpoints into a ZIO-HTTP Routes value,
  * adds the Swagger UI, and starts the HTTP server.
  */
object Server:

  type AppEnv =
    PhotoRepository & PhotographerRepository & ApiClientRepository & AuthService

  /** All abstract endpoint definitions — passed to SwaggerInterpreter to
    * auto-generate the OpenAPI spec and serve the Swagger UI at /docs.
    */
  private val allAbstractEndpoints: List[sttp.tapir.AnyEndpoint] =
    AuthApi.endpoints ++ PhotoApi.endpoints ++ PhotographerApi.endpoints

  /** Swagger UI endpoints (served as static files at /docs). */
  private val swaggerEndpoints: List[sttp.tapir.server.ServerEndpoint[Any, Task]] =
    SwaggerInterpreter()
      .fromEndpoints[Task](
        allAbstractEndpoints,
        "Clever Photos API",
        "1.0.0"
      )

  /** All server-side endpoints combined (business logic + Swagger UI). */
  private val allServerEndpoints
    : List[sttp.tapir.server.ServerEndpoint[Any, RIO[AppEnv, *]]] =
    (AuthApi.serverEndpoints ++
      PhotoApi.serverEndpoints ++
      PhotographerApi.serverEndpoints)
      .map(_.asInstanceOf[sttp.tapir.server.ServerEndpoint[Any, RIO[AppEnv, *]]]) ++
    swaggerEndpoints.map(
      _.asInstanceOf[sttp.tapir.server.ServerEndpoint[Any, RIO[AppEnv, *]]]
    )

  /** Starts the ZIO-HTTP server and blocks until the application shuts down. */
  def start(config: HttpConfig): ZIO[AppEnv & Scope, Throwable, Nothing] =
    val routes = ZioHttpInterpreter().toHttp(allServerEndpoints)
    val serverConfig = zio.http.Server.Config.default
      .binding(config.host, config.port)
    zio.http.Server.serve(routes).provide(
      ZLayer.succeed(serverConfig),
      zio.http.Server.live
    )
