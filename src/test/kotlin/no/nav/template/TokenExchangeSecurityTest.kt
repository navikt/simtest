package no.nav.template

import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.template.token.AzureOboExchange
import no.nav.template.token.TokenValidator
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class TokenExchangeSecurityTest {
    private fun jwt(claims: String): JwtToken {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return JwtToken("${encode("""{"alg":"none"}""")}.${encode(claims)}.")
    }

    private fun validator(token: JwtToken?): TokenValidator =
        object : TokenValidator {
            override fun firstValidToken(request: Request) = token
        }

    private fun app(
        token: JwtToken? = null,
        dev: Boolean = true,
        exchange: (JwtToken, String) -> JwtToken = { _, _ -> error("Unexpected exchange") },
    ): HttpHandler = Application(tokenValidator = validator(token), exchangeToken = exchange, isDev = dev).api()

    private fun nameRequest(
        name: String,
        origin: String = "https://simtest.intern.dev.nav.no",
    ) = Request(Method.POST, "/internal/tokenexchange-name")
        .header("Host", "simtest.intern.dev.nav.no")
        .header("Origin", origin)
        .header("Content-Type", "text/plain")
        .body(name)

    @Test
    fun `built React GUI and assets are served under the same prefix with security headers`() {
        val api = app()
        val page = api(Request(Method.GET, "/internal/gui/"))
        assertEquals(Status.OK, page.status)
        assertEquals(Status.OK, api(Request(Method.GET, "/internal/gui")).status)
        assertTrue(page.bodyString().contains("id=\"root\""))
        assertTrue(page.bodyString().contains("class=\"dark\""))
        assertEquals("no-store", page.header("Cache-Control"))
        assertEquals("nosniff", page.header("X-Content-Type-Options"))
        val csp = page.header("Content-Security-Policy") ?: ""
        assertTrue(csp.contains("script-src 'self'"))
        assertTrue(csp.contains("font-src 'self' https://cdn.nav.no"))
        assertFalse(csp.contains("unsafe-inline"))
        assertFalse(csp.contains("unsafe-eval"))

        val scriptPath =
            Regex("/internal/gui/assets/[a-zA-Z0-9_-]+\\.js")
                .find(page.bodyString())
                ?.value ?: error("Missing hashed JS asset in built HTML")
        val script = api(Request(Method.GET, scriptPath))
        assertEquals(Status.OK, script.status)
        assertEquals("no-store", script.header("Cache-Control"))
        assertEquals(csp, script.header("Content-Security-Policy"))
        assertEquals("nosniff", script.header("X-Content-Type-Options"))
        assertTrue(script.header("Content-Type")?.contains("javascript") == true)
        val stylesheetPath =
            Regex("/internal/gui/assets/[a-zA-Z0-9_-]+\\.css")
                .find(page.bodyString())
                ?.value ?: error("Missing hashed CSS asset in built HTML")
        val stylesheet = api(Request(Method.GET, stylesheetPath))
        assertEquals(Status.OK, stylesheet.status)
        assertEquals(csp, stylesheet.header("Content-Security-Policy"))
        assertEquals("nosniff", stylesheet.header("X-Content-Type-Options"))
        assertTrue(stylesheet.header("Content-Type")?.contains("css") == true)
        assertEquals(Status.NOT_FOUND, api(Request(Method.GET, "/internal/gui/scripts.js")).status)
    }

    @Test
    fun `login status returns name from validated token without caching`() {
        val response = app(jwt("""{"name":"  Kari Nordmann  "}"""))(Request(Method.GET, "/internal/secrethello"))
        assertEquals(Status.OK, response.status)
        assertEquals("Kari Nordmann", response.bodyString())
        assertEquals("no-store", response.header("Cache-Control"))
        assertEquals("no-cache", response.header("Pragma"))
        assertEquals("text/plain; charset=utf-8", response.header("Content-Type"))
        assertEquals("nosniff", response.header("X-Content-Type-Options"))
    }

    @Test
    fun `login status hides name when unauthenticated and falls back for missing or invalid claim`() {
        val request = Request(Method.GET, "/internal/secrethello")
        val unauthenticated = app()(request)
        assertEquals(Status.UNAUTHORIZED, unauthenticated.status)
        assertEquals("no-store", unauthenticated.header("Cache-Control"))
        listOf("{}", """{"name":null}""", """{"name":42}""", """{"name":["Kari"]}""", """{"name":"  \t  "}""").forEach {
            assertEquals("Du", app(jwt(it))(request).bodyString())
        }
    }

    @Test
    fun `login status limits display name and serves HTML-looking text as plain text`() {
        val request = Request(Method.GET, "/internal/secrethello")
        val nameWithMarkup = "<img src=x onerror=alert(1)>"
        val markup = app(jwt("""{"name":"$nameWithMarkup"}"""))(request)
        assertEquals(nameWithMarkup, markup.bodyString())
        assertEquals("text/plain; charset=utf-8", markup.header("Content-Type"))
        val longName = "A".repeat(101)
        assertEquals("A".repeat(100), app(jwt("""{"name":"$longName"}"""))(request).bodyString())
        assertEquals("Kari Nordmann", app(jwt("""{"name":"Kari\n Nordmann"}"""))(request).bodyString())
    }

    @Test
    fun `both routes require validated user token`() {
        val api = app()
        listOf(Request(Method.GET, "/internal/tokenexchange2"), nameRequest("dev-gcp:platforce:saas-proxy")).forEach {
            val response = api(it)
            assertEquals(Status.UNAUTHORIZED, response.status)
            assertEquals("no-store", response.header("Cache-Control"))
        }
        val withoutIdent = app(jwt("""{"sub":"machine"}"""))
        assertEquals(Status.FORBIDDEN, withoutIdent(nameRequest("dev-gcp:platforce:saas-proxy")).status)
    }

    @Test
    fun `fixed audience remains independent from free text`() {
        val result = jwt("""{"sub":"result"}""")
        var audience = ""
        val api =
            app(
                jwt("""{"NAVident":"A123456"}"""),
                exchange = { _, target ->
                    audience = target
                    result
                },
            )
        val fixed = api(Request(Method.GET, "/internal/tokenexchange2"))
        assertEquals(Status.OK, fixed.status)
        assertEquals("d4b4792b-3321-45ad-aa24-848a029f8450", audience)
        assertEquals(result.encodedToken, fixed.bodyString())
        assertEquals("no-store", fixed.header("Cache-Control"))
        val named = api(nameRequest("dev-gcp:platforce:saas-proxy"))
        assertEquals(Status.OK, named.status)
        assertEquals("dev-gcp.platforce.saas-proxy", audience)
    }

    @Test
    fun `azure OBO sends dot-separated target as default scope`() {
        val result = jwt("""{"sub":"result"}""")
        var sentScope = ""
        val exchanger =
            AzureOboExchange(
                client = { request ->
                    val form = URLDecoder.decode(request.bodyString(), StandardCharsets.UTF_8)
                    sentScope = form.split('&').single { it.startsWith("scope=") }.substringAfter('=')
                    Response(Status.OK).body("""{"access_token":"${result.encodedToken}"}""")
                },
                endpoint = "https://login.example.test/token",
                clientId = "client-id",
                clientSecret = "test-secret",
            )
        val api = app(jwt("""{"NAVident":"A123456"}"""), exchange = { token, target -> exchanger.exchange(token, target) })
        assertEquals(Status.OK, api(nameRequest("dev-gcp:platforce:saas-proxy")).status)
        assertEquals("api://dev-gcp.platforce.saas-proxy/.default", sentScope)
    }

    @Test
    fun `name route rejects invalid scope and non dev before exchange`() {
        val api = app(jwt("""{"NAVident":"A123456"}"""))
        listOf(
            "",
            "prod-gcp:platforce:saas-proxy",
            "dev-gcp:platforce:saas-proxy/.default",
            "dev-gcp:platforce:saas-proxy offline_access",
            "dev-gcp:platforce:saas-proxy\n",
            "dev-gcp.platforce.saas-proxy",
            "dev-gcp:a:b:c",
            "dev-gcp:ns:app%2F",
            "dev-gcp:platforce:${"a".repeat(136)}",
            "dev-gcp::app",
        ).forEach {
            val response = api(nameRequest(it))
            assertEquals(Status.BAD_REQUEST, response.status)
            if (it.isNotEmpty()) {
                assertFalse(response.bodyString().contains(it))
            }
        }
        val nonDev = app(jwt("""{"NAVident":"A123456"}"""), dev = false)
        assertEquals(Status.NOT_FOUND, nonDev(nameRequest("dev-gcp:platforce:saas-proxy")).status)
        val crossSite = nameRequest("dev-gcp:platforce:saas-proxy", "https://attacker.example")
        assertEquals(Status.FORBIDDEN, api(crossSite).status)
    }

    @Test
    fun `azure errors never reach the browser`() {
        val api = app(jwt("""{"NAVident":"A123456"}"""), exchange = { _, _ -> error("sensitive downstream response") })
        val response = api(nameRequest("dev-gcp:platforce:saas-proxy"))
        assertEquals(Status.BAD_GATEWAY, response.status)
        assertFalse(response.bodyString().contains("sensitive"))
        assertEquals("no-store", response.header("Cache-Control"))
        assertTrue(response.bodyString().isNotBlank())
    }
}
