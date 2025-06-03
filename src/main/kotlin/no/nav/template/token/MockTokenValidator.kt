package no.nav.template.token

import no.nav.security.token.support.core.jwt.JwtToken
import org.http4k.core.Request
import java.util.Optional

class MockTokenValidator : TokenValidator {
    override fun firstValidToken(request: Request): Optional<JwtToken> {
        TODO("Not yet implemented")
    }
}
