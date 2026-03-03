package com.clever.photos.api

import com.clever.photos.config.HttpConfig
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.http.*

/** Assembles all Tapir server endpoints into a ZIO-HTTP Routes value,
  * optionally adds the Swagger UI, and starts the HTTP server.
  *
  * All server endpoints are typed with the shared [[AppEnv]] so the route
  * list is homogeneous — no `asInstanceOf` casts needed.  ZIO's contravariance
  * in `R` ensures that endpoint logic using only a subset of services still
  * type-checks against the full `AppEnv`.
  */
object Server:

  /** All abstract endpoint definitions — fed to SwaggerInterpreter to
    * auto-generate the OpenAPI spec and serve the Swagger UI at /docs.
    */
  private val allAbstractEndpoints: List[sttp.tapir.AnyEndpoint] =
    AuthApi.endpoints ++ PhotoApi.endpoints ++ PhotographerApi.endpoints

  /** Business-logic server endpoints — all typed as ZServerEndpoint[AppEnv, Any]. */
  private val businessEndpoints: List[ZServerEndpoint[AppEnv, Any]] =
    AuthApi.serverEndpoints ++
    PhotoApi.serverEndpoints ++
    PhotographerApi.serverEndpoints

  // Swagger UI endpoints — commented out temporarily while resolving
  // sbt-assembly META-INF/resources packaging for tapir-swagger-ui-bundle.
  // private val swaggerEndpoints: List[ZServerEndpoint[AppEnv, Any]] =
  //   SwaggerInterpreter()
  //     .fromEndpoints[[A] =>> RIO[AppEnv, A]](
  //       allAbstractEndpoints,
  //       "Clever Photos API",
  //       "1.0.0"
  //     )

  /** Starts the ZIO-HTTP server and blocks until the application shuts down. */
  def start(config: HttpConfig): ZIO[AppEnv & Scope, Throwable, Nothing] =
    val allEndpoints = businessEndpoints
    val routes       = ZioHttpInterpreter().toHttp(allEndpoints)
    val serverConfig = zio.http.Server.Config.default.binding(config.host, config.port)
    zio.http.Server.serve(routes).provideSomeLayer[AppEnv](
      ZLayer.succeed(serverConfig) >>> zio.http.Server.live
    )
