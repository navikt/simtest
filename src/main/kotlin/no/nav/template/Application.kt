@file:Suppress("ktlint:standard:property-naming")

package no.nav.template

import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.template.token.DefaultTokenValidator
import no.nav.template.token.MissingUserIdentityException
import no.nav.template.token.TokenExchangeHandler
import no.nav.template.token.TokenValidator
import no.nav.template.token.isNavOBOToken
import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.http4k.routing.PathMethod
import org.http4k.routing.ResourceLoader
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer
import org.slf4j.MarkerFactory
import java.io.File
import java.net.URI

class Application(
    private val tokenValidator: TokenValidator = DefaultTokenValidator(),
    private val exchangeToken: (JwtToken, String) -> JwtToken = TokenExchangeHandler::exchange,
    private val isDev: Boolean = env(env_NAIS_CLUSTER_NAME) in setOf("dev-gcp", "dev-fss"),
) {
    private val log = KotlinLogging.logger { }

    val cluster = env(env_NAIS_CLUSTER_NAME)

    private val TEAM_LOGS = MarkerFactory.getMarker("TEAM_LOGS")
    private val appNamePattern = Regex("dev-(?:gcp|fss):[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler {
        val handler =
            routes(
                "/internal/isAlive" bind Method.GET to { Response(OK) },
                "/internal/isReady" bind Method.GET to { Response(OK) },
                "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
                "/internal/hello" bind Method.GET to {
                    log.info("Hello in the logs")
                    withLoggingContext(
                        "google_cloud_project" to "teamnks-dev-a4ad",
                    ) {
                        log.info(TEAM_LOGS, "Hello in the logs, TEAM LOGS via context")
                    }
                    Response(OK).body("Hello!")
                },
                "/internal/secrethello" authbind Method.GET to { request ->
                    val token = tokenValidator.firstValidToken(request)
                    val response =
                        if (token == null) {
                            Response(Status.UNAUTHORIZED)
                        } else {
                            val name =
                                (token.jwtTokenClaims.get("name") as? String)
                                    ?.trim()
                                    ?.replace(Regex("\\p{Cntrl}"), "")
                                    ?.take(100)
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "Du"
                            Response(OK).body(name)
                        }
                    tokenResponse(response)
                },
                "/internal/token" authbind Method.GET to {
                    val token = tokenValidator.firstValidToken(it)!!
                    Response(OK).header("Cache-Control", "no-store").body(token.encodedToken)
                },
                "/internal/tokenexchange" authbind Method.GET to tokenExchangeRoute("77322f36-6268-422e-a591-4616212cca1e"),
                "/internal/tokenexchange2" authbind Method.GET to tokenExchangeRoute("d4b4792b-3321-45ad-aa24-848a029f8450"),
                "/internal/tokenexchange-name" authbind Method.POST to { req ->
                    val contentType = req.header("Content-Type")?.substringBefore(';')?.trim()
                    val appName = req.bodyString()
                    when {
                        !isDev -> tokenResponse(Response(Status.NOT_FOUND))
                        !sameOrigin(req) -> tokenResponse(Response(Status.FORBIDDEN))
                        contentType != "text/plain" -> tokenResponse(Response(Status.BAD_REQUEST))
                        !appNamePattern.matches(appName) ->
                            tokenResponse(Response(Status.BAD_REQUEST).body("Ugyldig applikasjonsnavn."))
                        else -> tokenExchangeRoute(appName.replace(':', '.'))(req)
                    }
                },
                "/internal/gui" bind static(ResourceLoader.Classpath("/gui")),
            )
        return { request ->
            val response = handler(request)
            if (request.uri.path.startsWith("/internal/gui")) {
                val csp =
                    "default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self' https://cdn.nav.no; " +
                        "object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
                response
                    .header("Cache-Control", "no-store")
                    .header("Content-Security-Policy", csp)
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Referrer-Policy", "no-referrer")
            } else {
                response
            }
        }
    }

    private fun sameOrigin(request: Request): Boolean {
        if (request.header("Sec-Fetch-Site")?.let { it != "same-origin" && it != "none" } == true) return false
        val origin = request.header("Origin") ?: return false
        val host = request.header("Host") ?: return false
        return try {
            val uri = URI(origin)
            uri.scheme == "https" &&
                uri.rawAuthority == host &&
                uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null
        } catch (_: Exception) {
            false
        }
    }

    private fun tokenResponse(response: Response) =
        response
            .header("Cache-Control", "no-store")
            .header("Pragma", "no-cache")
            .header("Content-Type", "text/plain; charset=utf-8")
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")

    private fun tokenExchangeRoute(audience: String): HttpHandler =
        { request ->
            val token = tokenValidator.firstValidToken(request)
            val response =
                when {
                    token == null -> Response(Status.UNAUTHORIZED)
                    !token.isNavOBOToken() ->
                        Response(Status.FORBIDDEN).body("Innloggingen mangler gyldig brukeridentitet. Logg inn med en Nav-bruker.")
                    else ->
                        try {
                            Response(OK).body(exchangeToken(token, audience).encodedToken)
                        } catch (_: MissingUserIdentityException) {
                            Response(Status.FORBIDDEN).body("Innloggingen mangler gyldig brukeridentitet. Logg inn med en Nav-bruker.")
                        } catch (_: Exception) {
                            Response(Status.BAD_GATEWAY).body("Kunne ikke hente token. Prøv igjen senere.")
                        }
                }
            tokenResponse(response)
        }

    /**
     * authbind: a variant of bind that takes care of authentication with use of tokenValidator
     */
    infix fun String.authbind(method: Method) = AuthRouteBuilder(this, method, tokenValidator)

    data class AuthRouteBuilder(
        val path: String,
        val method: Method,
        private val tokenValidator: TokenValidator,
    ) {
        infix fun to(action: HttpHandler): RoutingHttpHandler =
            PathMethod(path, method) to { request ->
                Metrics.apiCalls.labels(path).inc()
                val token = tokenValidator.firstValidToken(request)
                if (token != null) {
                    action(request)
                } else {
                    Response(Status.UNAUTHORIZED)
                        .header("Cache-Control", "no-store")
                        .header("Pragma", "no-cache")
                }
            }
    }

    fun start() {
        try {
            log.info { "Starting in cluster $cluster" }
            File("/tmp/hello").writeText("I am in distroless test")
            File("/tmp/hello2").writeText("I am in distroless test 2")
            File("/tmp/hello3").writeText("I am in distroless test 3")
            apiServer(8080).start()
        } catch (e: Exception) {
            log.error(e) { "Failed to start server: " + e.printStackTrace() }
            throw e
        }
    }

    fun callAsModia(token: JwtToken): String {
        val client: HttpHandler = OkHttp()

        val uri =
            Uri.of("https://sf-henvendelse.intern.dev.nav.no/api/henvendelseinfo/henvendelseliste?aktorid=1000096233942&cache=true")

        val request =
            Request(Method.GET, uri)
                .header("X-Correlation-ID", "df3d62db9b0e4cbc94c2243895f6d111")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Nav-Call-Id", "df3d62db9b0e4cbc94c2243895f6d111")
                .header("Nav-Consumer-Id", "modiabrukerdialog")
                .header("Authorization", "Bearer ${token.encodedToken}")
                .header("Connection", "Keep-Alive")
                .header("Accept-Encoding", "gzip")
        // .header("User-Agent", "okhttp/4.12.0") // Manually replicate the UA
        // .header("traceparent", "00-f9469af424547e8943e13ef3bf3ce373-80b748275162777d-01")

        File("/tmp/callModia").writeText(request.toMessage())
        val response = client(request)
        File("/tmp/responseModia").writeText(response.toMessage())
        return response.toMessage().replace("\n", "<br>")
    }
}
