package com.shadow.bot

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FreshBearer(val token: String, val authState: String)

/** Serializes rotating credentials across clients without replacing drafts or pending commands. */
class RefreshingBearerTokens(private val store: StateStore, private val session: Session,
    private val fresh: suspend (String) -> FreshBearer) : BearerTokens {
    override suspend fun token(): String = refreshLock.withLock {
        // A cancelled screen must not discard a refresh token already rotated by the issuer.
        withContext(NonCancellable) {
            val saved = store.read().session ?: expired()
            if (saved.id != session.id || !saved.sameAccount(session)) expired()
            val oidc = saved.oidc ?: expired()
            if (oidc.authState.isBlank()) expired()
            val refreshed = try { fresh(oidc.authState) }
            catch (error: ApiFailure) {
                if (error.status == 401) store.update { current -> if (current.session == saved) current.copy(session = saved.copy(oidc = oidc.copy(authState = ""))) else current }
                throw error
            }
            require(refreshed.token.isNotBlank() && refreshed.authState.isNotBlank())
            var accepted = false
            store.update { current ->
                if (current.session == saved) { accepted = true; current.copy(session = saved.copy(oidc = oidc.copy(authState = refreshed.authState))) } else current
            }
            if (!accepted) expired()
            refreshed.token
        }
    }
    private fun expired(): Nothing = throw ApiFailure(401, "统一登录已失效，请重新登录；待发记录已保留。")
    companion object { private val refreshLock = Mutex() }
}
