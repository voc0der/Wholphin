package com.github.damontecres.wholphin.api.seerrproxy

import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.infrastructure.Serializer
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class SeerrProxyClient
    @Inject
    constructor(
        @param:AuthOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private val json = Serializer.kotlinxSerializationJson

        suspend fun status(jellyfinBaseUrl: String): SeerrProxyStatusResponse? {
            val request =
                Request
                    .Builder()
                    .url(jellyfinBaseUrl.pluginUrl("Status"))
                    .get()
                    .build()

            return okHttpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    return@use null
                }
                response.body.string().let { json.decodeFromString<SeerrProxyStatusResponse>(it) }
            }
        }

        suspend fun createRequest(
            jellyfinBaseUrl: String,
            request: SeerrProxyRequest,
        ) {
            val body =
                json
                    .encodeToString(request)
                    .toRequestBody(JSON_MEDIA_TYPE)
            val httpRequest =
                Request
                    .Builder()
                    .url(jellyfinBaseUrl.pluginUrl("Request"))
                    .post(body)
                    .build()

            okHttpClient.newCall(httpRequest).await().use { response ->
                if (!response.isSuccessful) {
                    throw SeerrProxyException(
                        statusCode = response.code,
                        responseBody = response.body.string(),
                        message = response.message,
                    )
                }
            }
        }

        fun createApiClient(jellyfinBaseUrl: String): SeerrApiClient =
            SeerrApiClient(
                baseUrl = jellyfinBaseUrl.pluginApiUrl(),
                apiKey = null,
                okHttpClient = okHttpClient,
            )

        private fun String.pluginUrl(action: String) =
            toHttpUrl()
                .newBuilder()
                .addPathSegments("Plugins/SeerrProxy")
                .addPathSegment(action)
                .build()

        private fun String.pluginApiUrl() =
            toHttpUrl()
                .newBuilder()
                .addPathSegments("Plugins/SeerrProxy/Api")
                .build()
                .toString()

        private suspend fun Call.await(): Response =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancel() }
                enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            continuation.resumeWithException(e)
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            continuation.resume(response)
                        }
                    },
                )
            }

        private companion object {
            val JSON_MEDIA_TYPE = "application/json".toMediaType()
        }
    }

val SeerrProxyStatusResponse.isAvailable: Boolean
    get() = enabled && configured && linked == true && seerrReachable != false

class SeerrProxyException(
    val statusCode: Int,
    val responseBody: String?,
    message: String?,
) : RuntimeException(message ?: responseBody ?: "Seerr Proxy request failed")

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SeerrProxyStatusResponse(
    @SerialName("enabled")
    @JsonNames("Enabled")
    val enabled: Boolean = false,
    @SerialName("configured")
    @JsonNames("Configured")
    val configured: Boolean = false,
    @SerialName("jellyfinUserId")
    @JsonNames("JellyfinUserId")
    val jellyfinUserId: String? = null,
    @SerialName("linked")
    @JsonNames("Linked")
    val linked: Boolean? = null,
    @SerialName("seerrUserId")
    @JsonNames("SeerrUserId")
    val seerrUserId: Int? = null,
    @SerialName("displayName")
    @JsonNames("DisplayName")
    val displayName: String? = null,
    @SerialName("seerrReachable")
    @JsonNames("SeerrReachable")
    val seerrReachable: Boolean? = null,
    @SerialName("mappingError")
    @JsonNames("MappingError")
    val mappingError: String? = null,
)

@Serializable
data class SeerrProxyRequest(
    @SerialName("mediaType")
    val mediaType: String,
    @SerialName("mediaId")
    val mediaId: Int? = null,
    @SerialName("tmdbId")
    val tmdbId: Int? = null,
    @SerialName("tvdbId")
    val tvdbId: Int? = null,
    @SerialName("seasons")
    val seasons: JsonElement? = null,
    @SerialName("is4k")
    val is4k: Boolean? = null,
    @SerialName("serverId")
    val serverId: Int? = null,
    @SerialName("profileId")
    val profileId: Int? = null,
    @SerialName("rootFolder")
    val rootFolder: String? = null,
    @SerialName("languageProfileId")
    val languageProfileId: Int? = null,
    @SerialName("tags")
    val tags: List<Int>? = null,
)
