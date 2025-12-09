package eu.kanade.tachiyomi.extension.all.remoteapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CapabilitiesResponse(
    val name: String = "Unknown Remote API",
    val version: String? = null,
    val supports: SupportFlags = SupportFlags(),
    val filters: List<FilterDefinition> = emptyList(),
    val auth: AuthSpec? = null,
    val defaults: DefaultValues = DefaultValues(),
)

@Serializable
data class SupportFlags(
    val popular: Boolean = true,
    val latest: Boolean = false,
    @SerialName("search") val searchEnabled: Boolean = true,
    @SerialName("manga_details") val mangaDetails: Boolean = true,
    @SerialName("chapters") val chapters: Boolean = true,
    @SerialName("pages") val pages: Boolean = true,
)

@Serializable
data class DefaultValues(
    @SerialName("page_size") val pageSize: Int? = null,
)

@Serializable
data class AuthSpec(
    val header: String = "X-Api-Key",
    val type: String = "apiKey",
    val scheme: String = "plain",
)

@Serializable
data class FilterDefinition(
    val key: String,
    val label: String,
    val type: String,
    val options: List<FilterOption> = emptyList(),
    val default: String? = null,
    val section: String? = null,
)

@Serializable
data class FilterOption(
    val value: String,
    val label: String,
)

@Serializable
data class PagedResult<T>(
    val items: List<T> = emptyList(),
    @SerialName("has_next") val hasNext: Boolean = false,
    val total: Int? = null,
)

@Serializable
data class MangaEnvelope(
    val manga: RemoteManga,
    val chapters: List<RemoteChapter>? = null,
)

@Serializable
data class RemoteManga(
    val id: String,
    val title: String,
    @SerialName("alt_titles") val altTitles: List<String> = emptyList(),
    val url: String? = null,
    @SerialName("thumbnail") val thumbnailUrl: String? = null,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val tags: List<String> = emptyList(),
    val lang: String? = null,
    val nsfw: Boolean? = null,
)

@Serializable
data class RemoteChapter(
    val id: String,
    val name: String,
    val url: String? = null,
    @SerialName("number") val number: Double? = null,
    @SerialName("volume") val volume: String? = null,
    val scanlator: String? = null,
    @SerialName("uploaded") val uploadedAt: Long? = null,
)

@Serializable
data class PageListResponse(
    val pages: List<RemotePage> = emptyList(),
)

@Serializable
data class RemotePage(
    val index: Int? = null,
    @SerialName("image_url") val imageUrl: String,
    @SerialName("page_url") val pageUrl: String? = null,
    val headers: Map<String, String>? = null,
)

@Serializable
data class ErrorEnvelope(
    val error: String? = null,
    val message: String? = null,
)
