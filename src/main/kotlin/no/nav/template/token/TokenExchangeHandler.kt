package no.nav.template.token

import mu.KotlinLogging
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.template.currentDateTime
import no.nav.template.env
import no.nav.template.env_AZURE_APP_CLIENT_ID
import no.nav.template.env_AZURE_APP_CLIENT_SECRET
import no.nav.template.env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.http4k.client.ApacheClient
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.body.toBody
import org.json.JSONObject
import java.io.File
import java.lang.Exception
import javax.naming.AuthenticationException

object TokenExchangeHandler {
    /**
     * A handler for azure on-behalf-of exchange flow.
     * @see [v2_oauth2_on_behalf_of_flow](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-on-behalf-of-flow)
     *
     * Exchanges an azure on-behalf-of-token with audience to this app for one with audience to salesforce. Caches the result
     */
    /**
     * A handler for azure on-behalf-of exchange flow.
     * @see [v2_oauth2_on_behalf_of_flow](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-on-behalf-of-flow)
     *
     * Exchanges an azure on-behalf-of-token with audience to this app for one with audience to salesforce. Caches the result
     */

    private val log = KotlinLogging.logger { }

    private val clientId: String = env(env_AZURE_APP_CLIENT_ID)
    private val clientSecret: String = env(env_AZURE_APP_CLIENT_SECRET)

    private val azureTokenEndPoint: String = env(env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT)

    private val azureConnectionManager =
        PoolingHttpClientConnectionManager().apply {
            maxTotal = 10
            defaultMaxPerRoute = 5
        }

    private val azureHttpClient: CloseableHttpClient =
        HttpClients.custom()
            .setConnectionManager(azureConnectionManager)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectTimeout(5000)
                    .setSocketTimeout(5000)
                    .setConnectionRequestTimeout(3000)
                    .setRedirectsEnabled(false)
                    .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                    .build()
            ).build()

    val clientAzure = ApacheClient(azureHttpClient)

    fun isOBOToken(jwt: JwtToken) = jwt.jwtTokenClaims.get("NAVident") != null

    // target alias example: cluster.namespace.app
    fun exchange(
        jwtIn: JwtToken,
        targetAlias: String,
        scope: String = ".default"
    ): JwtToken {
        if (!isOBOToken(jwtIn)) return acquireServiceToken(targetAlias, scope)
        val key = targetAlias + jwtIn.tokenAsString

        log.info { "Exchange obo token for $targetAlias" }

        val req =
            Request(Method.POST, azureTokenEndPoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(
                    listOf(
                        "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                        "assertion" to jwtIn.tokenAsString,
                        "client_id" to clientId,
                        "scope" to "api://$targetAlias/$scope",
                        "client_secret" to clientSecret,
                        "requested_token_use" to "on_behalf_of",
                        "claims" to """{
                        "access_token": {
                            "groups": {
                                "essential": true
                            },
                            "roles": {
                                "essential": true
                            }
                         }
                    }"""
                    ).toBody()
                )
        val res = clientCallWithRetries(req)

        val jwtEncoded = res.extractAccessToken(targetAlias, "obo", req)
        val jwt = JwtToken(jwtEncoded)

        return jwt
    }

    fun acquireServiceToken(
        targetAlias: String,
        scope: String
    ): JwtToken {
        log.info { "Acquiring service token for $targetAlias" }
        val m2mscope = if (scope == "defaultaccess") ".default" else scope
        val req =
            Request(Method.POST, azureTokenEndPoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(
                    listOf(
                        "client_id" to clientId,
                        "scope" to "api://$targetAlias/$m2mscope",
                        "client_secret" to clientSecret,
                        "grant_type" to "client_credentials"
                    ).toBody()
                )

        val res = clientCallWithRetries(req)

        val jwtEncoded = res.extractAccessToken(targetAlias, "m2m", req)
        val jwt = JwtToken(jwtEncoded)

        return jwt
    }

    fun clientCallWithRetries(
        request: Request,
        maxRetries: Int = 3,
        delayMillis: Long = 100
    ): Response {
        var attempt = 0
        var lastException: Exception? = null

        while (attempt < maxRetries) {
            try {
                attempt++
                val response = clientAzure(request)
                if (response.status.code == 504) {
                    log.warn { "Time out on attempt $attempt. Retrying..." }
                } else {
                    return response
                }
            } catch (e: Exception) {
                lastException = e
                log.error { "Unexpected error on attempt $attempt: ${e.message}. Retrying in ${delayMillis}ms..." }
                Thread.sleep(delayMillis)
            }
        }

        throw lastException ?: RuntimeException("Failed to execute action after $maxRetries attempts.")
    }

    private fun Response.extractAccessToken(
        alias: String,
        tokenType: String,
        request: Request
    ): String {
        try {
            return JSONObject(this.bodyString()).get("access_token").toString()
        } catch (e: Exception) {
            File("/tmp/failedExtractAccessToken-$alias-$tokenType").writeText(
                "$currentDateTime\n\nREQUEST:\n" + request.toMessage() + "\n\nRESPONSE:\n" + this.toMessage()
            )
            log.error { "Failed to fetch $tokenType access token for $alias - ${this.bodyString()} " }
            throw AuthenticationException("Failed to fetch $tokenType access token for $alias - ${this.bodyString()}")
        }
    }
}
