package com.shadow.app

import android.content.Context
import com.shadow.bot.*
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun authenticatedApi(context: Context, store: StateStore, session: Session) =
    HttpProductApi.from(session, session.oidc?.let { RefreshingBearerTokens(store, session) { raw -> freshTokens(context, raw) } })

private suspend fun freshTokens(context: Context, raw: String): FreshBearer {
    val state = runCatching { AuthState.jsonDeserialize(raw) }.getOrElse { throw ApiFailure(401, "请重新登录统一账号。") }
    val service = AuthorizationService(context.applicationContext, strictAuthConfiguration())
    try {
        return suspendCancellableCoroutine { continuation ->
            state.performActionWithFreshTokens(service) { token, _, error ->
                if (!continuation.isActive) return@performActionWithFreshTokens
                if (token == null || error != null) {
                    val permanent = error?.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR && error.error !in setOf("server_error", "temporarily_unavailable")
                    continuation.resumeWithException(if (permanent) ApiFailure(401, "统一登录已失效，请重新登录；待发记录已保留。")
                        else java.io.IOException("统一认证暂不可用，请稍后重试。"))
                } else continuation.resume(FreshBearer(token, state.jsonSerializeString()))
            }
        }
    } finally { service.dispose() }
}
