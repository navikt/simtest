package no.nav.template

import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.template.token.TokenValidator
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        assertEquals("dev-gcp:platforce:saas-proxy", audience)
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
