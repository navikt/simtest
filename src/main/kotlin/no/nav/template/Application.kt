package no.nav.template

import mu.KotlinLogging
import no.nav.saas.proxy.token.DefaultTokenValidator
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.template.token.TokenExchangeHandler
import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.http4k.core.query
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
            val exchangedToken = TokenExchangeHandler.exchange(token, "77322f36-6268-422e-a591-4616212cca1e")
            Response(OK).body("Result: " + callAsModia(exchangedToken))
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

    fun callAsModia(token: JwtToken): String {
        val client: HttpHandler = OkHttp()



        val uri = Uri.of("https://sf-henvendelse.intern.dev.nav.no/api/henvendelseinfo/henvendelseliste")
            .query("aktorid", "1000096233942") // .query("cache", "true")

        val request = Request(Method.GET, uri)
            .header("X-Correlation-ID", "df3d62db9b0e4cbc94c2243895f6d111")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Nav-Call-Id", "df3d62db9b0e4cbc94c2243895f6d111")
            .header("Nav-Consumer-Id", "modiabrukerdialog")
            .header("Authorization", "Bearer ${token.tokenAsString}")
            .header("Connection", "Keep-Alive")
            .header("Accept-Encoding", "gzip")
        // .header("User-Agent", "okhttp/4.12.0") // Manually replicate the UA
        // .header("traceparent", "00-f9469af424547e8943e13ef3bf3ce373-80b748275162777d-01")

        val response = client(request)
        return response.toMessage()
    }
}
