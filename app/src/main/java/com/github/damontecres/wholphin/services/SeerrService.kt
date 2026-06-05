package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.model.CreditCast
import com.github.damontecres.wholphin.api.seerr.model.CreditCrew
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.api.seerr.model.MovieResult
import com.github.damontecres.wholphin.api.seerr.model.RequestPostRequest
import com.github.damontecres.wholphin.api.seerr.model.SearchGet200ResponseResultsInner
import com.github.damontecres.wholphin.api.seerr.model.TvDetails
import com.github.damontecres.wholphin.api.seerr.model.TvResult
import com.github.damontecres.wholphin.api.seerrproxy.SeerrProxyClient
import com.github.damontecres.wholphin.api.seerrproxy.SeerrProxyException
import com.github.damontecres.wholphin.api.seerrproxy.SeerrProxyRequest
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.toLocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

typealias SeerrSearchResult = SearchGet200ResponseResultsInner

/**
 * Main access for the current Seerr server (if any)
 *
 * Exposes a [SeerrApiClient] for queries
 */
@Singleton
class SeerrService
    @Inject
    constructor(
        private val seerApi: SeerrApi,
        private val seerrServerRepository: SeerrServerRepository,
        private val imageUrlService: ImageUrlService,
        private val seerrProxyClient: SeerrProxyClient,
    ) {
        val api: SeerrApiClient
            get() =
                if (seerApi.active) {
                    seerApi.api
                } else {
                    (
                        seerrServerRepository.requestProxyConnection.value
                            as? SeerrRequestProxyConnectionStatus.Available
                    )?.api ?: seerApi.api
                }

        val active get() = seerrServerRepository.active
        val requestProxyActive get() = seerrServerRepository.requestProxyActive

        suspend fun search(
            query: String,
            page: Int = 1,
        ): List<SeerrSearchResult> =
            api.searchApi
                .searchGet(query = query, page = page)
                .results
                .orEmpty()

        suspend fun discoverTv(page: Int = 1): List<DiscoverItem> =
            api.searchApi
                .discoverTvGet(page = page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        suspend fun discoverMovies(page: Int = 1): List<DiscoverItem> =
            api.searchApi
                .discoverMoviesGet(page = page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        suspend fun trending(page: Int = 1): List<DiscoverItem> =
            api.searchApi
                .discoverTrendingGet(page = page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        suspend fun upcomingMovies(page: Int = 1): List<DiscoverItem> =
            api.searchApi
                .discoverMoviesUpcomingGet(page = page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        suspend fun upcomingTv(page: Int = 1): List<DiscoverItem> =
            api.searchApi
                .discoverTvUpcomingGet(page = page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        /**
         * Get [DiscoverItem]s similar to the JF items such as movies, series, or people
         *
         * If Seerr integration is not active, this short circuits to return null
         *
         * @return the discovered items or null if no Seerr server configured
         */
        suspend fun similar(item: BaseItem): List<DiscoverItem>? =
            if (active.first()) {
                item.data.providerIds
                    ?.get("Tmdb")
                    ?.toIntOrNull()
                    ?.let {
                        when (item.type) {
                            BaseItemKind.MOVIE -> {
                                api.moviesApi
                                    .movieMovieIdSimilarGet(movieId = it)
                                    .results
                                    ?.map { createDiscoverItem(it) }
                            }

                            BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE -> {
                                api.tvApi
                                    .tvTvIdSimilarGet(tvId = it)
                                    .results
                                    ?.map { createDiscoverItem(it) }
                            }

                            BaseItemKind.PERSON -> {
                                api.personApi
                                    .personPersonIdCombinedCreditsGet(personId = it)
                                    .let { credits ->
                                        val cast =
                                            credits.cast
                                                ?.take(25)
                                                ?.map { createDiscoverItem(it) }
                                                .orEmpty()
                                        val crew =
                                            credits.crew
                                                ?.take(25)
                                                ?.map { createDiscoverItem(it) }
                                                .orEmpty()
                                        cast + crew
                                    }
                            }

                            else -> {
                                null
                            }
                        }
                    }.orEmpty()
            } else {
                null
            }

        suspend fun getTvSeries(item: BaseItem): TvDetails? =
            if (active.first()) {
                item.data.providerIds
                    ?.get("Tmdb")
                    ?.toIntOrNull()
                    ?.let { tvId ->
                        api.tvApi.tvTvIdGet(tvId = tvId)
                    }
            } else {
                null
            }

        suspend fun requestMovie(
            mediaId: Int,
            is4k: Boolean,
        ) {
            val proxyRequest =
                SeerrProxyRequest(
                    mediaType = "movie",
                    mediaId = mediaId,
                    is4k = is4k,
                )
            if (tryRequestViaProxy(proxyRequest)) {
                return
            }
            api.requestApi.requestPost(
                RequestPostRequest(
                    is4k = is4k,
                    mediaId = mediaId,
                    mediaType = RequestPostRequest.MediaType.MOVIE,
                ),
            )
        }

        suspend fun requestTv(
            mediaId: Int,
            seasons: List<Int>,
            is4k: Boolean,
        ) {
            val proxyRequest =
                SeerrProxyRequest(
                    mediaType = "tv",
                    mediaId = mediaId,
                    seasons = JsonArray(seasons.map(::JsonPrimitive)),
                    is4k = is4k,
                )
            if (tryRequestViaProxy(proxyRequest)) {
                return
            }
            api.requestApi.requestPost(
                RequestPostRequest(
                    is4k = is4k,
                    mediaId = mediaId,
                    mediaType = RequestPostRequest.MediaType.TV,
                    seasons = seasons,
                ),
            )
        }

        private suspend fun tryRequestViaProxy(request: SeerrProxyRequest): Boolean {
            val proxy =
                seerrServerRepository.requestProxyConnection.first()
                    as? SeerrRequestProxyConnectionStatus.Available ?: return false
            try {
                seerrProxyClient.createRequest(proxy.jellyfinServerUrl, request)
                return true
            } catch (ex: SeerrProxyException) {
                if (!ex.canFallbackToDirectSeerr()) {
                    throw ex
                }
                Timber.w(ex, "Seerr Proxy unavailable, falling back to direct Seerr")
                seerrServerRepository.clearRequestProxy()
                return false
            }
        }

        private suspend fun createImageUrl(
            imageType: ImageType,
            path: String?,
            mediaInfo: MediaInfo?,
        ): String? {
            if (mediaInfo != null) {
                val itemId =
                    if (mediaInfo.jellyfinMediaId.isNotNullOrBlank()) {
                        mediaInfo.jellyfinMediaId.toUUIDOrNull()
                    } else if (mediaInfo.jellyfinMediaId4k.isNotNullOrBlank()) {
                        mediaInfo.jellyfinMediaId4k.toUUIDOrNull()
                    } else {
                        null
                    }
                if (itemId != null) {
                    return imageUrlService.getItemImageUrl(
                        itemId = itemId,
                        imageType = imageType,
                    )
                }
            }
            val current = seerrServerRepository.current.firstOrNull()
            if (current == null &&
                seerrServerRepository.requestProxyConnection.value !is SeerrRequestProxyConnectionStatus.Available
            ) {
                return null
            }
            val cacheImages = current?.serverConfig?.cacheImages == true
            val base =
                current
                    ?.takeIf { cacheImages }
                    ?.server
                    ?.url
                    ?.removeSuffix("/")
                    ?.plus("/imageproxy/tmdb")
                    ?: "https://image.tmdb.org"
            val prefix =
                when (imageType) {
                    ImageType.PRIMARY -> "/t/p/w500"
                    ImageType.BACKDROP -> "/t/p/w1920_and_h1080_multi_faces"
                    else -> throw IllegalArgumentException("Image type not supported: $imageType")
                }
            return "${base}${prefix}$path"
        }

        suspend fun createDiscoverItem(item: Any): DiscoverItem =
            when (item) {
                is MovieResult -> createDiscoverItem(item)
                is MovieDetails -> createDiscoverItem(item)
                is TvResult -> createDiscoverItem(item)
                is TvDetails -> createDiscoverItem(item)
                is SeerrSearchResult -> createDiscoverItem(item)
                is CreditCast -> createDiscoverItem(item)
                is CreditCrew -> createDiscoverItem(item)
                else -> throw IllegalArgumentException("Unsupported type ${item::class.qualifiedName}")
            }

        suspend fun createDiscoverItem(movie: MovieResult): DiscoverItem =
            DiscoverItem(
                id = movie.id,
                type = SeerrItemType.MOVIE,
                title = movie.title,
                subtitle = null,
                overview = movie.overview,
                availability =
                    SeerrAvailability.from(movie.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(movie.releaseDate),
                posterUrl = createImageUrl(ImageType.PRIMARY, movie.posterPath, movie.mediaInfo),
                backDropUrl = createImageUrl(ImageType.BACKDROP, movie.backdropPath, movie.mediaInfo),
                jellyfinItemId = movie.mediaInfo?.jellyfinMediaId?.toUUIDOrNull(),
            )

        suspend fun createDiscoverItem(movie: MovieDetails): DiscoverItem =
            DiscoverItem(
                id = movie.id ?: -1,
                type = SeerrItemType.MOVIE,
                title = movie.title,
                subtitle = null,
                overview = movie.overview,
                availability =
                    SeerrAvailability.from(movie.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(movie.releaseDate),
                posterUrl = createImageUrl(ImageType.PRIMARY, movie.posterPath, movie.mediaInfo),
                backDropUrl = createImageUrl(ImageType.BACKDROP, movie.backdropPath, movie.mediaInfo),
                jellyfinItemId = movie.mediaInfo?.jellyfinMediaId?.toUUIDOrNull(),
            )

        suspend fun createDiscoverItem(tv: TvResult): DiscoverItem =
            DiscoverItem(
                id = tv.id!!,
                type = SeerrItemType.TV,
                title = tv.name,
                subtitle = null,
                overview = tv.overview,
                availability =
                    SeerrAvailability.from(tv.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(tv.firstAirDate),
                posterUrl = createImageUrl(ImageType.PRIMARY, tv.posterPath, tv.mediaInfo),
                backDropUrl = createImageUrl(ImageType.BACKDROP, tv.backdropPath, tv.mediaInfo),
                jellyfinItemId = tv.mediaInfo?.jellyfinMediaId?.toUUIDOrNull(),
            )

        suspend fun createDiscoverItem(tv: TvDetails): DiscoverItem =
            DiscoverItem(
                id = tv.id!!,
                type = SeerrItemType.TV,
                title = tv.name,
                subtitle = null,
                overview = tv.overview,
                availability =
                    SeerrAvailability.from(tv.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(tv.firstAirDate),
                posterUrl = createImageUrl(ImageType.PRIMARY, tv.posterPath, tv.mediaInfo),
                backDropUrl = createImageUrl(ImageType.BACKDROP, tv.backdropPath, tv.mediaInfo),
                jellyfinItemId = tv.mediaInfo?.jellyfinMediaId?.toUUIDOrNull(),
            )

        suspend fun createDiscoverItem(search: SeerrSearchResult): DiscoverItem =
            DiscoverItem(
                id = search.id,
                type = SeerrItemType.fromString(search.mediaType),
                title = search.title ?: search.name,
                subtitle = null,
                overview = search.overview,
                availability =
                    SeerrAvailability.from(search.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(search.releaseDate ?: search.firstAirDate),
                posterUrl = createImageUrl(ImageType.PRIMARY, search.posterPath, search.mediaInfo),
                backDropUrl = createImageUrl(ImageType.BACKDROP, search.backdropPath, search.mediaInfo),
                jellyfinItemId = search.mediaInfo?.jellyfinMediaId?.toUUIDOrNull(),
            )

        suspend fun createDiscoverItem(credit: CreditCast): DiscoverItem =
            DiscoverItem(
                id = credit.id!!,
                type = SeerrItemType.fromString(credit.mediaType, SeerrItemType.PERSON),
                title = credit.name ?: credit.title,
                subtitle = credit.character,
                overview = credit.overview,
                availability =
                    SeerrAvailability.from(credit.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(credit.firstAirDate),
                posterUrl =
                    createImageUrl(
                        ImageType.PRIMARY,
                        credit.posterPath ?: credit.profilePath,
                        credit.mediaInfo,
                    ),
                backDropUrl =
                    createImageUrl(
                        ImageType.BACKDROP,
                        credit.backdropPath,
                        credit.mediaInfo,
                    ),
                jellyfinItemId = credit.mediaInfo?.jellyfinMediaId?.toUUIDOrNull(),
            )

        suspend fun createDiscoverItem(credit: CreditCrew): DiscoverItem =
            DiscoverItem(
                id = credit.id!!,
                type = SeerrItemType.fromString(credit.mediaType, SeerrItemType.PERSON),
                title = credit.name ?: credit.title,
                subtitle = credit.job,
                overview = credit.overview,
                availability =
                    SeerrAvailability.from(credit.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(credit.firstAirDate),
                posterUrl =
                    createImageUrl(
                        ImageType.PRIMARY,
                        credit.posterPath ?: credit.profilePath,
                        credit.mediaInfo,
                    ),
                backDropUrl =
                    createImageUrl(
                        ImageType.BACKDROP,
                        credit.backdropPath,
                        credit.mediaInfo,
                    ),
                jellyfinItemId = credit.mediaInfo?.jellyfinMediaId?.toUUIDOrNull(),
            )
    }

private fun SeerrProxyException.canFallbackToDirectSeerr() =
    statusCode == 401 || statusCode == 403 || statusCode == 404 || statusCode == 502 || statusCode == 503
