package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.setup.seerr.createSeerrApiUrl
import okhttp3.OkHttpClient

/**
 * Wrapper for [SeerrApiClient]. In most cases, you should use [SeerrService] instead.
 */
class SeerrApi(
    private val standardOkHttpClient: OkHttpClient,
    private val authOkHttpClient: OkHttpClient,
) {
    var api: SeerrApiClient =
        SeerrApiClient(
            baseUrl = "",
            apiKey = null,
            okHttpClient = standardOkHttpClient,
        )
        private set

    val active: Boolean get() = api.baseUrl.isNotNullOrBlank() && BuildConfig.DISCOVER_ENABLED

    fun update(
        baseUrl: String,
        apiKey: String?,
        useJellyfinAuth: Boolean = false,
    ) {
        api =
            SeerrApiClient(
                baseUrl = if (useJellyfinAuth) baseUrl else createSeerrApiUrl(baseUrl),
                apiKey = apiKey,
                okHttpClient = if (useJellyfinAuth) authOkHttpClient else standardOkHttpClient,
            )
    }
}
