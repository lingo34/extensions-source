package eu.kanade.tachiyomi.extension.all.remoteapi

import android.content.SharedPreferences
import android.text.InputType
import android.util.Base64
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class RemoteApi : HttpSource(), ConfigurableSource {
    override val name = "Remote API"

    override val baseUrl: String by lazy {
        preferences.getString(PREF_BASE_URL, DEFAULT_BASE_URL)!!.removeSuffix("/")
    }

    override val lang = "all"

    // We'll keep this enabled and gracefully degrade if the server says otherwise.
    override val supportsLatest: Boolean
        get() = cachedCapabilities.get()?.supports?.latest ?: true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by lazy { Injekt.get<Json>() }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val cachedCapabilities: AtomicReference<CapabilitiesResponse?> = AtomicReference(null)

    private val pageSize: Int
        get() = preferences.getString(PREF_PAGE_SIZE, DEFAULT_PAGE_SIZE)!!.toIntOrNull()
            ?.coerceIn(1, 200) ?: DEFAULT_PAGE_SIZE.toInt()

    private val apiKey: String
        get() = preferences.getString(PREF_API_KEY, "")!!.trim()

    private val apiHeader: String
        get() = preferences.getString(PREF_API_HEADER, DEFAULT_API_HEADER)!!.trim().ifBlank { DEFAULT_API_HEADER }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        if (apiKey.isNotEmpty()) {
            add(apiHeader, apiKey)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(screen.editTextPreference(PREF_BASE_URL, "Server base URL", DEFAULT_BASE_URL, true))
        screen.addPreference(screen.editTextPreference(PREF_API_HEADER, "Auth header name", DEFAULT_API_HEADER, false))
        screen.addPreference(screen.editTextPreference(PREF_API_KEY, "Auth header value", "", true, true))
        screen.addPreference(screen.editTextPreference(PREF_PAGE_SIZE, "Page size", DEFAULT_PAGE_SIZE, false))
    }

    // region Preference helpers

    private fun PreferenceScreen.editTextPreference(
        key: String,
        title: String,
        default: String,
        restartRequired: Boolean,
        passwordField: Boolean = false,
    ): EditTextPreference {
        return EditTextPreference(context).apply {
            this.key = key
            this.title = title
            this.summary = preferences.getString(key, default)
            setDefaultValue(default)
            if (passwordField) {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            }
            setOnPreferenceChangeListener { _, newValue ->
                val newString = newValue as String
                val commit = preferences.edit().putString(key, newString).commit()
                summary = newString
                if (restartRequired) Toast.makeText(context, "Restart required to apply changes", Toast.LENGTH_LONG).show()
                commit
            }
        }
    }

    // endregion

    // region Call helpers

    private val baseHttpUrl: HttpUrl
        get() = runCatching { baseUrl.toHttpUrl() }.getOrElse {
            throw IOException("Invalid base URL '$baseUrl'. Please fix it in source settings.")
        }

    private fun apiUrl(path: String, block: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val cleanPath = path.removePrefix("/")
        val builder = baseHttpUrl.newBuilder().addPathSegments(cleanPath)
        return builder.apply(block).build()
    }

    private fun apiGet(path: String, block: HttpUrl.Builder.() -> Unit = {}): Request {
        return GET(apiUrl(path, block).toString(), headers, CacheControl.FORCE_NETWORK)
    }

    private inline fun <reified T> Response.parseJson(action: String): T {
        val bodyString = body.string()
        if (!isSuccessful) throw toHttpError(action, bodyString)
        return json.decodeFromString(bodyString)
    }

    private fun Response.toHttpError(action: String, rawBody: String): IOException {
        val apiMessage = runCatching { json.decodeFromString<ErrorEnvelope>(rawBody).message }
            .getOrNull()
        val text = apiMessage ?: "Server error $code while $action"
        return IOException(text)
    }

    private fun ensureCapabilities() {
        if (cachedCapabilities.get() != null) return

        val response = client.newCall(apiGet("v1/capabilities")).execute()
        response.use {
            val caps = it.parseJson<CapabilitiesResponse>("fetching capabilities")
            cachedCapabilities.set(caps)
            caps.defaults.pageSize?.let { serverSize ->
                val current = preferences.getString(PREF_PAGE_SIZE, DEFAULT_PAGE_SIZE)!!
                if (current == DEFAULT_PAGE_SIZE) {
                    preferences.edit().putString(PREF_PAGE_SIZE, serverSize.toString()).apply()
                }
            }
            caps.auth?.header
                ?.takeIf { it.isNotBlank() }
                ?.let { headerFromServer ->
                    val currentHeader = preferences.getString(PREF_API_HEADER, DEFAULT_API_HEADER)!!
                    if (currentHeader == DEFAULT_API_HEADER) {
                        preferences.edit().putString(PREF_API_HEADER, headerFromServer).apply()
                    }
                }
        }
    }

    private fun requireSupport(feature: String, supported: Boolean) {
        if (!supported) throw UnsupportedOperationException("Server disabled $feature")
    }

    private fun currentCaps(): CapabilitiesResponse {
        if (cachedCapabilities.get() == null) {
            runCatching { ensureCapabilities() }
        }
        return cachedCapabilities.get() ?: CapabilitiesResponse()
    }

    // endregion

    // region Popular / Latest / Search

    override fun popularMangaRequest(page: Int): Request {
        val caps = currentCaps()
        requireSupport("popular", caps.supports.popular)

        return apiGet("v1/manga/popular") {
            addQueryParameter("page", page.toString())
            addQueryParameter("page_size", pageSize.toString())
        }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        response.use {
            val result = it.parseJson<PagedResult<RemoteManga>>("loading popular")
            val mangas = result.items.map { manga -> manga.toSManga() }
            return MangasPage(mangas, result.hasNext)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val caps = currentCaps()
        requireSupport("latest", caps.supports.latest)

        return apiGet("v1/manga/latest") {
            addQueryParameter("page", page.toString())
            addQueryParameter("page_size", pageSize.toString())
        }
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        response.use {
            val result = it.parseJson<PagedResult<RemoteManga>>("loading latest")
            val mangas = result.items.map { manga -> manga.toSManga() }
            return MangasPage(mangas, result.hasNext)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val caps = currentCaps()
        requireSupport("search", caps.supports.searchEnabled)

        return apiGet("v1/manga/search") {
            addQueryParameter("page", page.toString())
            addQueryParameter("page_size", pageSize.toString())
            if (query.isNotBlank()) addQueryParameter("query", query)
            appendFilters(filters)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        response.use {
            val result = it.parseJson<PagedResult<RemoteManga>>("searching")
            val mangas = result.items.map { manga -> manga.toSManga() }
            return MangasPage(mangas, result.hasNext)
        }
    }

    // endregion

    // region Details / Chapters / Pages

    override fun mangaDetailsRequest(manga: SManga): Request {
        val path = manga.url.takeIf { it.startsWith("http") } ?: baseUrl + manga.url
        return GET(path, headers, CacheControl.FORCE_NETWORK)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        response.use {
            val payload = it.parseJson<MangaEnvelope>("loading manga")
            return payload.manga.toSManga().apply { initialized = true }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast('/')
        val caps = currentCaps()
        requireSupport("chapters", caps.supports.chapters)

        return apiGet("v1/manga/$id/chapters") {
            addQueryParameter("page_size", pageSize.toString())
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        response.use {
            val result = it.parseJson<PagedResult<RemoteChapter>>("loading chapters")
            return result.items.map { chapter -> chapter.toSChapter() }.sortedByDescending { ch -> ch.chapter_number }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val raw = chapter.url
        if (raw.startsWith("http")) {
            return GET(raw, headers, CacheControl.FORCE_NETWORK)
        }
        val path = raw.removePrefix(baseUrl)
        return apiGet(path) {}
    }

    override fun pageListParse(response: Response): List<Page> {
        response.use {
            val payload = it.parseJson<PageListResponse>("loading pages")
            return payload.pages.mapIndexed { idx, page ->
                val index = page.index ?: idx
                val packed = packImagePayload(ImagePayload(page.imageUrl, page.headers))
                Page(index, packed, page.imageUrl)
            }
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("imageUrlParse is unused")

    override fun imageRequest(page: Page): Request {
        val payload = unpackImagePayload(page.url)
        val targetUrl = payload?.imageUrl ?: page.imageUrl!!
        val headerBuilder = headersBuilder()
        payload?.headers?.forEach { (key, value) -> headerBuilder.add(key, value) }
        return GET(targetUrl, headerBuilder.build())
    }

    // endregion

    // region Filters

    override fun getFilterList(): FilterList {
        runCatching { ensureCapabilities() }
        val definitions = cachedCapabilities.get()?.filters.orEmpty()
        if (definitions.isEmpty()) return FilterList(Filter.Header("Filters will load after first request"))

        val list = mutableListOf<Filter<*>>()
        var lastSection: String? = null
        definitions.forEach { def ->
            if (!def.section.isNullOrBlank() && def.section != lastSection) {
                list += Filter.Header(def.section)
                lastSection = def.section
            }
            def.toFilter()?.let(list::add)
        }
        return FilterList(list)
    }

    private fun FilterDefinition.toFilter(): Filter<*>? {
        return when (type.lowercase()) {
            "text" -> TextFilter(key, label, default ?: "")
            "checkbox" -> CheckboxFilter(key, label, default == "true")
            "select" -> SelectFilter(key, label, options, default)
            "sort" -> SortFilter(key, label, options, default)
            else -> null
        }
    }

    private fun HttpUrl.Builder.appendFilters(filters: FilterList) {
        filters.forEach { filter ->
            when (filter) {
                is TextFilter -> if (filter.state.isNotBlank()) addQueryParameter("filter.${filter.key}", filter.state)
                is CheckboxFilter -> addQueryParameter("filter.${filter.key}", filter.state.toString())
                is SelectFilter -> {
                    val option = filter.options.getOrNull(filter.state)
                    if (option != null && option.value.isNotEmpty()) addQueryParameter("filter.${filter.key}", option.value)
                }
                is SortFilter -> {
                    val option = filter.options.getOrNull(filter.state?.index ?: 0)
                    if (option != null) {
                        addQueryParameter("sort", option.value)
                        addQueryParameter("order", if (filter.state?.ascending == false) "desc" else "asc")
                    }
                }
                else -> {}
            }
        }
    }

    private class TextFilter(val key: String, displayName: String, default: String) : Filter.Text(displayName, default)
    private class CheckboxFilter(val key: String, displayName: String, default: Boolean) : Filter.CheckBox(displayName, default)
    private class SelectFilter(
        val key: String,
        displayName: String,
        val options: List<FilterOption>,
        default: String?,
    ) : Filter.Select<String>(displayName, options.map { it.label }.toTypedArray()) {
        init {
            state = options.indexOfFirst { it.value == default }.takeIf { it >= 0 } ?: 0
        }
    }

    private class SortFilter(
        val key: String,
        displayName: String,
        val options: List<FilterOption>,
        default: String?,
    ) : Filter.Sort(displayName, options.map { it.label }.toTypedArray(), defaultSelection(options, default)) {
        companion object {
            private fun defaultSelection(options: List<FilterOption>, default: String?): Selection {
                val parts = default?.split(":") ?: emptyList()
                val index = options.indexOfFirst { it.value == parts.getOrNull(0) }.takeIf { it >= 0 } ?: 0
                val ascending = parts.getOrNull(1)?.lowercase() != "desc"
                return Selection(index, ascending)
            }
        }
    }

    // endregion

    // region Model mapping

    private fun RemoteManga.toSManga(): SManga = SManga.create().apply {
        url = "/v1/manga/$id"
        title = this@toSManga.title
        thumbnail_url = thumbnailUrl
        author = this@toSManga.author
        artist = this@toSManga.artist
        description = this@toSManga.description
        genre = tags.joinToString(", ")
        status = mapStatus(this@toSManga.status)
        initialized = true
    }

    private fun RemoteChapter.toSChapter(): SChapter = SChapter.create().apply {
        url = "/v1/chapters/$id/pages"
        name = this@toSChapter.name
        chapter_number = this@toSChapter.number?.toFloat() ?: -1f
        scanlator = this@toSChapter.scanlator
        date_upload = this@toSChapter.uploadedAt ?: 0L
    }

    private fun mapStatus(status: String?): Int = when (status?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled", "canceled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // endregion

    // region Image payload packing

    @Serializable
    private data class ImagePayload(val imageUrl: String, val headers: Map<String, String>?)

    private fun packImagePayload(payload: ImagePayload): String {
        if (payload.headers.isNullOrEmpty()) return payload.imageUrl
        val encoded = json.encodeToString(payload)
        val compact = Base64.encodeToString(encoded.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "$IMAGE_PREFIX$compact"
    }

    private fun unpackImagePayload(raw: String): ImagePayload? {
        if (!raw.startsWith(IMAGE_PREFIX)) return null
        val base64 = raw.removePrefix(IMAGE_PREFIX)
        val decoded = Base64.decode(base64, Base64.URL_SAFE or Base64.NO_WRAP)
        return json.decodeFromString(String(decoded))
    }

    // endregion

    companion object {
        private const val PREF_BASE_URL = "remoteapi_base_url"
        private const val PREF_API_KEY = "remoteapi_api_key"
        private const val PREF_API_HEADER = "remoteapi_api_header"
        private const val PREF_PAGE_SIZE = "remoteapi_page_size"

        private const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
        private const val DEFAULT_API_HEADER = "X-Api-Key"
        private const val DEFAULT_PAGE_SIZE = "40"

        private const val IMAGE_PREFIX = "remoteapi+"
    }
}
