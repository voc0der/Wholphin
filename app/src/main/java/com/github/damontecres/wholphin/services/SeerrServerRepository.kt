package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.model.AuthJellyfinPostRequest
import com.github.damontecres.wholphin.api.seerr.model.AuthLocalPostRequest
import com.github.damontecres.wholphin.api.seerr.model.PublicSettings
import com.github.damontecres.wholphin.api.seerr.model.User
import com.github.damontecres.wholphin.api.seerrproxy.SeerrProxyClient
import com.github.damontecres.wholphin.api.seerrproxy.isAvailable
import com.github.damontecres.wholphin.data.SeerrServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.SeerrAuthMethod
import com.github.damontecres.wholphin.data.model.SeerrPermission
import com.github.damontecres.wholphin.data.model.SeerrServer
import com.github.damontecres.wholphin.data.model.SeerrUser
import com.github.damontecres.wholphin.data.model.hasPermission
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.ui.setup.seerr.createSeerrApiUrl
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Manages saves/loading Seerr servers from the local DB. Also will update the current [SeerrApi] as needed.
 */
@Singleton
class SeerrServerRepository
    @Inject
    constructor(
        private val seerrApi: SeerrApi,
        private val seerrServerDao: SeerrServerDao,
        private val serverRepository: ServerRepository,
        private val seerrProxyClient: SeerrProxyClient,
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private val _connection =
            MutableStateFlow<SeerrConnectionStatus>(SeerrConnectionStatus.NotConfigured)
        val connection: StateFlow<SeerrConnectionStatus> = _connection

        val current: Flow<CurrentSeerr?> =
            _connection.map { (it as? SeerrConnectionStatus.Success)?.current }
        val currentServer: Flow<SeerrServer?> =
            connection.map { (it as? SeerrConnectionStatus.Success)?.current?.server }
        val currentUser: Flow<SeerrUser?> =
            connection.map { (it as? SeerrConnectionStatus.Success)?.current?.user }
        val currentUserConfig: Flow<SeerrUserConfig?> =
            current.map { it?.config }
        val currentServerConfig: Flow<PublicSettings?> =
            current.map { it?.serverConfig }
        val currentUserId: Flow<Int?> = currentUserConfig.map { it?.id }
        val request4kMovieEnabled: Flow<Boolean> =
            combine(currentUserConfig, currentServerConfig) { config, serverConfig ->
                (serverConfig?.movie4kEnabled ?: false) &&
                    config.hasPermission(SeerrPermission.REQUEST_4K_MOVIE)
            }
        val request4kTvEnabled: Flow<Boolean> =
            combine(currentUserConfig, currentServerConfig) { config, serverConfig ->
                (serverConfig?.series4kEnabled ?: false) &&
                    config.hasPermission(SeerrPermission.REQUEST_4K_TV)
            }

        /**
         * Whether Seerr integration is currently active of not
         */
        val active: Flow<Boolean> =
            connection.map { connection ->
                connection is SeerrConnectionStatus.Success && seerrApi.active
            }

        fun clear() {
            _connection.update { SeerrConnectionStatus.NotConfigured }
            seerrApi.update("", null)
        }

        suspend fun discoverAndChangePluginProxy(): Boolean {
            val probe = probeCurrentJellyfinPluginProxy() ?: return false
            val jellyfinUser = serverRepository.currentUser ?: return false
            var server = seerrServerDao.getServer(probe.apiUrl)
            if (server == null) {
                seerrServerDao.addServer(SeerrServer(url = probe.apiUrl))
                server = seerrServerDao.getServer(probe.apiUrl)
            }
            server?.server?.let { seerrServer ->
                val user =
                    SeerrUser(
                        jellyfinUserRowId = jellyfinUser.rowId,
                        serverId = seerrServer.id,
                        authMethod = SeerrAuthMethod.JELLYFIN_PLUGIN_PROXY,
                        username = null,
                        password = null,
                        credential = null,
                    )
                seerrServerDao.addUser(user)
                seerrApi.update(seerrServer.url, null, useJellyfinAuth = true)
                _connection.update {
                    SeerrConnectionStatus.Success(
                        CurrentSeerr(seerrServer, user, probe.userConfig, probe.serverConfig),
                    )
                }
                Timber.i("Using Seerr Proxy transport for %s", probe.jellyfinServerUrl)
                return true
            }
            return false
        }

        private suspend fun probeCurrentJellyfinPluginProxy(): SeerrPluginProxyProbe? {
            val jellyfinServerUrl = serverRepository.currentServer?.url ?: return null
            val status =
                try {
                    seerrProxyClient.status(jellyfinServerUrl)
                } catch (ex: Exception) {
                    Timber.d(ex, "Seerr Proxy is not available at %s", jellyfinServerUrl)
                    null
                }
            if (status?.isAvailable != true) {
                return null
            }

            val api = seerrProxyClient.createApiClient(jellyfinServerUrl)
            val userConfig =
                try {
                    api.usersApi.authMeGet()
                } catch (ex: Exception) {
                    Timber.w(ex, "Seerr Proxy API user config is not available at %s", jellyfinServerUrl)
                    return null
                }
            val serverConfig =
                try {
                    api.settingsApi.settingsPublicGet()
                } catch (ex: Exception) {
                    Timber.w(ex, "Seerr Proxy API public settings are not available at %s", jellyfinServerUrl)
                    return null
                }

            return SeerrPluginProxyProbe(
                jellyfinServerUrl = jellyfinServerUrl,
                apiUrl = seerrProxyClient.createApiUrl(jellyfinServerUrl),
                userConfig = userConfig,
                serverConfig = serverConfig,
            )
        }

        fun error(
            server: SeerrServer,
            user: SeerrUser,
            exception: Exception,
        ) {
            _connection.update { SeerrConnectionStatus.Error(server, user, exception) }
            seerrApi.update("", null)
        }

        suspend fun set(
            server: SeerrServer,
            user: SeerrUser,
            userConfig: SeerrUserConfig,
        ) {
            val publicSettings = seerrApi.api.settingsApi.settingsPublicGet()
            _connection.update {
                SeerrConnectionStatus.Success(
                    CurrentSeerr(server, user, userConfig, publicSettings),
                )
            }
        }

        suspend fun addAndChangeServer(
            url: String,
            apiKey: String,
        ) {
            var server = seerrServerDao.getServer(url)
            if (server == null) {
                seerrServerDao.addServer(SeerrServer(url = url))
                server = seerrServerDao.getServer(url)
            }
            server?.server?.let { server ->
                serverRepository.currentUser?.let { jellyfinUser ->
                    // TODO test api key
                    val user =
                        SeerrUser(
                            jellyfinUserRowId = jellyfinUser.rowId,
                            serverId = server.id,
                            authMethod = SeerrAuthMethod.API_KEY,
                            username = null,
                            password = null,
                            credential = apiKey,
                        )
                    seerrServerDao.addUser(user)

                    seerrApi.update(server.url, apiKey)
                    val userConfig = seerrApi.api.usersApi.authMeGet()
                    set(server, user, userConfig)
                }
            }
        }

        suspend fun addAndChangeServer(
            url: String,
            authMethod: SeerrAuthMethod,
            username: String,
            password: String,
        ) {
            if (authMethod == SeerrAuthMethod.JELLYFIN_PLUGIN_PROXY) {
                throw IllegalArgumentException("Jellyfin plugin proxy is discovered from the current Jellyfin server.")
            }

            var server = seerrServerDao.getServer(url)
            if (server == null) {
                seerrServerDao.addServer(SeerrServer(url = url))
                server = seerrServerDao.getServer(url)
            }
            server?.server?.let { server ->
                serverRepository.currentUser?.let { jellyfinUser ->
                    // TODO Need to update server early so that cookies are saved
                    seerrApi.update(server.url, null)
                    val userConfig = seerrLogin(seerrApi.api, authMethod, username, password)

                    val user =
                        SeerrUser(
                            jellyfinUserRowId = jellyfinUser.rowId,
                            serverId = server.id,
                            authMethod = authMethod,
                            username = username,
                            password = password,
                            credential = null,
                        )
                    seerrServerDao.addUser(user)
                    set(server, user, userConfig)
                }
            }
        }

        suspend fun testConnection(
            authMethod: SeerrAuthMethod,
            url: String,
            username: String?,
            passwordOrApiKey: String,
        ): LoadingState {
            val apiKey = passwordOrApiKey.takeIf { authMethod == SeerrAuthMethod.API_KEY }
            val api =
                SeerrApiClient(
                    createSeerrApiUrl(url),
                    apiKey,
                    okHttpClient
                        .newBuilder()
                        .connectTimeout(2.seconds)
                        .readTimeout(6.seconds)
                        .build(),
                )
            seerrLogin(api, authMethod, username, passwordOrApiKey)
            return LoadingState.Success
        }

        suspend fun removeServerForCurrentUser(): Boolean {
            val user =
                when (val conn = connection.first()) {
                    SeerrConnectionStatus.NotConfigured -> return false
                    is SeerrConnectionStatus.Error -> conn.user
                    is SeerrConnectionStatus.Success -> conn.current.user
                }
            val rows = seerrServerDao.deleteUser(user)
            clear()
            return rows > 0
        }
    }

/**
 * A [SeerrUser] config
 */
typealias SeerrUserConfig = User

sealed interface SeerrConnectionStatus {
    data object NotConfigured : SeerrConnectionStatus

    data class Error(
        val server: SeerrServer,
        val user: SeerrUser,
        val ex: Exception,
    ) : SeerrConnectionStatus

    data class Success(
        val current: CurrentSeerr,
    ) : SeerrConnectionStatus
}

private data class SeerrPluginProxyProbe(
    val jellyfinServerUrl: String,
    val apiUrl: String,
    val userConfig: SeerrUserConfig,
    val serverConfig: PublicSettings,
)

data class CurrentSeerr(
    val server: SeerrServer,
    val user: SeerrUser,
    val config: SeerrUserConfig,
    val serverConfig: PublicSettings,
) {
    val request4kMovieEnabled: Boolean
        get() =
            (serverConfig.movie4kEnabled ?: false) &&
                config.hasPermission(SeerrPermission.REQUEST_4K_MOVIE)

    val request4kTvEnabled: Boolean
        get() =
            (serverConfig.series4kEnabled ?: false) &&
                config.hasPermission(SeerrPermission.REQUEST_4K_TV)
}

suspend fun seerrLogin(
    client: SeerrApiClient,
    authMethod: SeerrAuthMethod,
    username: String?,
    password: String?,
): User =
    when (authMethod) {
        SeerrAuthMethod.LOCAL -> {
            client.authApi.authLocalPost(
                AuthLocalPostRequest(
                    email = username ?: "",
                    password = password ?: "",
                ),
            )
            client.usersApi.authMeGet()
        }

        SeerrAuthMethod.JELLYFIN -> {
            client.authApi.authJellyfinPost(
                AuthJellyfinPostRequest(
                    username = username ?: "",
                    password = password ?: "",
                ),
            )
            client.usersApi.authMeGet()
        }

        SeerrAuthMethod.API_KEY -> {
            client.usersApi.authMeGet()
        }

        SeerrAuthMethod.JELLYFIN_PLUGIN_PROXY -> {
            client.usersApi.authMeGet()
        }
    }

fun CurrentSeerr?.imageUrlBuilder(
    imageType: ImageType,
    path: String?,
): String? {
    if (this == null) return null
    val cacheImages =
        serverConfig.cacheImages == true &&
            user.authMethod != SeerrAuthMethod.JELLYFIN_PLUGIN_PROXY
    val base =
        if (cacheImages) {
            server.url.removeSuffix("/") + "/imageproxy/tmdb"
        } else {
            "https://image.tmdb.org"
        }
    val prefix =
        when (imageType) {
            ImageType.PRIMARY -> "/t/p/w500"
            ImageType.BACKDROP -> "/t/p/w1920_and_h1080_multi_faces"
            else -> throw IllegalArgumentException("Image type not supported: $imageType")
        }
    return "${base}${prefix}$path"
}
