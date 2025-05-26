package no.nav.template

import mu.KotlinLogging
import no.nav.saas.proxy.token.DefaultTokenValidator
import no.nav.template.token.TokenExchangeHandler
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.PathMethod
import org.http4k.routing.ResourceLoader
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.ApacheServer
import org.http4k.server.Http4kServer
import org.http4k.server.asServer

class Application(
    private val tokenValidator: DefaultTokenValidator = DefaultTokenValidator()
) {
    private val log = KotlinLogging.logger { }

    val cluster = env(env_NAIS_CLUSTER_NAME)

    fun apiServer(port: Int): Http4kServer = api().asServer(ApacheServer(port))

    fun api(): HttpHandler = routes(
        "/internal/isAlive" bind Method.GET to { Response(OK) },
        "/internal/isReady" bind Method.GET to { Response(OK) },
        "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
        "/internal/hello" bind Method.GET to { Response(OK).body("Hello") },
        "/internal/secrethello" authbind Method.GET to { Response(OK).body("Secret Hello") },
        "/internal/tokenexchange" authbind Method.GET to {
            val token = tokenValidator.firstValidToken(it).get()
            Response(OK).body("Result: " + TokenExchangeHandler.exchange(token, "dev-gcp:teamnks:sf-henvendelse-api-proxy"))
        },
        "/internal/gui" bind static(ResourceLoader.Classpath("/gui")),
    )

    /**
     * authbind: a variant of bind that takes care of authentication with use of tokenValidator
     */
    infix fun String.authbind(method: Method) = AuthRouteBuilder(this, method, tokenValidator)

    data class AuthRouteBuilder(
        val path: String,
        val method: Method,
        private val tokenValidator: DefaultTokenValidator
    ) {
        infix fun to(action: HttpHandler): RoutingHttpHandler =
            PathMethod(path, method) to { request ->
                Metrics.apiCalls.labels(path).inc()
                val token = tokenValidator.firstValidToken(request)
                if (token.isPresent) {
                    action(request)
                } else {
                    Response(Status.UNAUTHORIZED)
                }
            }
    }

    fun start() {
        log.info { "Starting in cluster $cluster" }
        apiServer(8080).start()
    }
}
