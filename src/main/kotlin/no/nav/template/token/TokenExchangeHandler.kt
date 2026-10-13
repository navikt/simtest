package no.nav.template.token

import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.template.env
import no.nav.template.env_AZURE_APP_CLIENT_ID
import no.nav.template.env_AZURE_APP_CLIENT_SECRET
import no.nav.template.env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT
import okhttp3.OkHttpClient
import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.body.toBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MissingUserIdentityException : RuntimeException("Tokenveksling krever innlogging med Nav-bruker")

class TokenExchangeFailedException : RuntimeException("Kunne ikke hente token")

/** Input must come from TokenValidator.firstValidToken, not from decoding an arbitrary JWT. */
class AzureOboExchange(
    private val client: HttpHandler,
    private val endpoint: String,
    private val clientId: String,
    private val clientSecret: String,
) {
    fun exchange(
        jwtIn: JwtToken,
        targetAlias: String,
        scope: String = ".default",
    ): JwtToken {
        if (!jwtIn.isNavOBOToken()) throw MissingUserIdentityException()

        val request =
            Request(Method.POST, endpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(
                    listOf(
                        "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                        "assertion" to jwtIn.encodedToken,
                        "client_id" to clientId,
                        "scope" to "api://$targetAlias/$scope",
                        "client_secret" to clientSecret,
                        "requested_token_use" to "on_behalf_of",
                        "claims" to """{"access_token":{"groups":{"essential":true},"roles":{"essential":true}}}""",
                    ).toBody(),
                )

        // Do not include exceptions from the HTTP client or the IdP response in logs or responses:
        // they may contain the assertion, client secret, or returned token.
        val response =
            try {
                client(request)
            } catch (_: Exception) {
                throw TokenExchangeFailedException()
            }
        if (response.status.code !in 200..299) throw TokenExchangeFailedException()
        val encoded =
            try {
                JSONObject(response.bodyString()).getString("access_token")
            } catch (_: Exception) {
                throw TokenExchangeFailedException()
            }
        if (encoded.isBlank()) throw TokenExchangeFailedException()
        return try {
            JwtToken(encoded)
        } catch (_: Exception) {
            throw TokenExchangeFailedException()
        }
    }
}

object TokenExchangeHandler {
    private val exchanger by lazy {
        val httpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        AzureOboExchange(
            OkHttp(httpClient),
            env(env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT),
            env(env_AZURE_APP_CLIENT_ID),
            env(env_AZURE_APP_CLIENT_SECRET),
        )
    }

    fun exchange(
        jwtIn: JwtToken,
        targetAlias: String,
        scope: String = ".default",
    ): JwtToken = exchanger.exchange(jwtIn, targetAlias, scope)
}
