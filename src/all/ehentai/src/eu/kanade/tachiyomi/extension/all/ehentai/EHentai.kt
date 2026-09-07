package eu.kanade.tachiyomi.extension.all.ehentai

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.CookieManager
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Filter.CheckBox
import eu.kanade.tachiyomi.source.model.Filter.Select
import eu.kanade.tachiyomi.source.model.Filter.Text
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

@Source
abstract class EHentai :
    HttpSource(),
    ConfigurableSource {

    private val ehLang: String = when (lang) {
        "ja" -> "japanese"
        "en" -> "english"
        "zh" -> "chinese"
        "nl" -> "dutch"
        "fr" -> "french"
        "de" -> "german"
        "hu" -> "hungarian"
        "it" -> "italian"
        "ko" -> "korean"
        "pl" -> "polish"
        "pt-BR" -> "portuguese"
        "ru" -> "russian"
        "es" -> "spanish"
        "th" -> "thai"
        "vi" -> "vietnamese"
        "none" -> "n/a"
        "other" -> "other"
        else -> ""
    }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }

    /**
     * Domain whose cookie jar holds the login cookies. The login form on
     * e-hentai.org posts to forums.e-hentai.org, and the resulting
     * ipb_member_id / ipb_pass_hash cookies are set on the .e-hentai.org
     * domain, so they are visible from any of these URLs.
     */
    private val ehCookieUrls = arrayOf(
        "https://e-hentai.org",
        "https://forums.e-hentai.org",
        "https://exhentai.org",
    )

    /** exhentai.org is the only domain where the igneous cookie ever exists */
    private val exCookieUrl = "https://exhentai.org"

    /**
     * "mystery" is the sad-panda rejection value exhentai hands out for
     * invalid sessions; it must never be treated as a real login.
     */
    private fun String?.asIgneous(): String? = this
        ?.takeIf { it.isNotBlank() && it != "mystery" }

    private fun getMemberId(): String = getCookieFromWebviews("ipb_member_id", *ehCookieUrls)
        ?: preferences.getString(MEMBER_ID_PREF_KEY, MEMBER_ID_PREF_DEFAULT_VALUE).orEmpty()

    private fun getPassHash(): String = getCookieFromWebviews("ipb_pass_hash", *ehCookieUrls)
        ?: preferences.getString(PASS_HASH_PREF_KEY, PASS_HASH_PREF_DEFAULT_VALUE).orEmpty()

    /**
     * Current igneous cookie. Falls back to the stored preference, which is
     * both the manual override field and the cache for the value fetched by
     * the automatic ExHentai sign-in.
     */
    private fun getIgneous(): String = (getCookieFromWebviews("igneous", exCookieUrl) ?: preferences.getString(IGNEOUS_PREF_KEY, IGNEOUS_PREF_DEFAULT_VALUE))
        .asIgneous()
        ?: ""

    /** true when the user has an e-hentai account session to work with */
    private fun hasLoginCookies(): Boolean = getMemberId().isNotEmpty() && getPassHash().isNotEmpty()

    override val baseUrl: String
        get() = when {
            System.getenv("CI") == "true" -> "https://e-hentai.org"
            !getForceEhPref() && hasLoginCookies() -> "https://exhentai.org"
            else -> "https://e-hentai.org"
        }

    override val supportsLatest = true

    private var lastMangaId = ""

    // true if lang is a "natural human language"
    private fun isLangNatural(): Boolean = lang !in listOf("none", "other")

    private fun genericMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangaElements = doc.select("table.itg td.glname")
            .let { elements ->
                if (isLangNatural() && getEnforceLanguagePref()) {
                    elements.filter { element ->
                        // only accept elements with a language tag matching ehLang or without a language tag
                        // could make this stricter and not accept elements without a language tag, possibly add a sharedpreference for it
                        element.select("div[title^=language]").firstOrNull()?.let { it.text() == ehLang } ?: true
                    }
                } else {
                    elements
                }
            }
        val parsedMangas: MutableList<SManga> = mutableListOf()
        for (i in mangaElements.indices) {
            val manga = mangaElements[i].let {
                SManga.create().apply {
                    // Get title
                    it.selectFirst("a")?.apply {
                        title = this.select(".glink").text()
                        url = ExGalleryMetadata.normalizeUrl(attr("href"))
                        if (i == mangaElements.lastIndex) {
                            lastMangaId = ExGalleryMetadata.galleryId(attr("href"))
                        }
                    }
                    // Get image
                    it.parent()?.select(".glthumb img")?.first().apply {
                        thumbnail_url = this?.attr("data-src")?.nullIfBlank()
                            ?: this?.attr("src")
                    }
                }
            }
            parsedMangas.add(manga)
        }

        // Add to page if required
        val hasNextPage = doc.select("a#unext[href]").hasText()

        return MangasPage(parsedMangas, hasNextPage)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.just(
        listOf(
            SChapter.create().apply {
                url = manga.url
                name = "Chapter"
                chapter_number = 1f
            },
        ),
    )

    override fun fetchPageList(chapter: SChapter) = fetchChapterPage(chapter, "$baseUrl/${chapter.url}").map {
        it.mapIndexed { i, s ->
            Page(i, s)
        }
    }!!

    /**
     * Recursively fetch chapter pages
     */
    private fun fetchChapterPage(
        chapter: SChapter,
        np: String,
        pastUrls: List<String> = emptyList(),
    ): Observable<List<String>> {
        val urls = ArrayList(pastUrls)
        return chapterPageCall(np).flatMap {
            val jsoup = it.asJsoup()
            urls += parseChapterPage(jsoup)
            nextPageUrl(jsoup)?.let { string ->
                fetchChapterPage(chapter, string, urls)
            } ?: Observable.just(urls)
        }
    }

    private fun parseChapterPage(response: Element) = with(response) {
        select("#gdt a").map {
            it.attr("href")
        }
    }

    private fun chapterPageCall(np: String) = client.newCall(chapterPageRequest(np)).asObservableSuccess()
    private fun chapterPageRequest(np: String) = exGet(np, null, headers)

    private fun nextPageUrl(element: Element) = element.select("a[onclick=return false]").last()?.let {
        if (it.text() == ">") it.attr("href") else null
    }

    private fun languageTag(enforceLanguageFilter: Boolean = false): String = if (enforceLanguageFilter || getEnforceLanguagePref()) "language:$ehLang" else ""

    override fun popularMangaRequest(page: Int) = if (isLangNatural()) {
        exGet("$baseUrl/?f_search=${languageTag()}&f_srdd=5&f_sr=on", page)
    } else {
        latestUpdatesRequest(page)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val enforceLanguageFilter = filters.find { it is EnforceLanguageFilter }?.state == true
        var modifiedQuery = when {
            !isLangNatural() -> query
            query.isBlank() -> languageTag(enforceLanguageFilter)
            else -> languageTag(enforceLanguageFilter).let { if (it.isNotEmpty()) "$query,$it" else query }
        }
        filters.filterIsInstance<TextFilter>().forEach { filter ->
            if (filter.state.isNotEmpty()) {
                val splitted = filter.state.split(",").filter(String::isNotBlank)
                splitted.forEach { tag ->
                    val trimmed = tag.trim().lowercase()
                    val tagName = trimmed.removePrefix("-")
                    val isExclude = trimmed.startsWith('-')
                    modifiedQuery += if (isExclude) {
                        " -${filter.type}:\"$tagName\""
                    } else {
                        " ${filter.type}:\"$tagName\""
                    }
                }
            }
        }
        val baseSearchUrl = "$baseUrl$QUERY_PREFIX&f_search=${URLEncoder.encode(modifiedQuery, "UTF-8")}"
        val uri = Uri.parse(baseSearchUrl).buildUpon()
        // when attempting to search with no genres selected, will auto select all genres
        filters.filterIsInstance<GenreGroup>().firstOrNull()?.state?.let {
            // variable to check if any genres are selected
            val check = it.any { option -> option.state } // or it.any(GenreOption::state)
            // if no genres are selected by the user set all genres to on
            if (!check) {
                for (i in it) {
                    i.state = true
                }
            }
        }

        filters.forEach {
            if (it is UriFilter) it.addToUri(uri)
        }

        if (uri.toString().contains("f_spf") || uri.toString().contains("f_spt")) {
            if (page > 1) uri.appendQueryParameter("from", lastMangaId)
        }

        return exGet(uri.toString(), page)
    }

    override fun latestUpdatesRequest(page: Int) = exGet(baseUrl, page)

    override fun popularMangaParse(response: Response) = genericMangaParse(response)
    override fun searchMangaParse(response: Response) = genericMangaParse(response)
    override fun latestUpdatesParse(response: Response) = genericMangaParse(response)

    private fun exGet(url: String, page: Int? = null, additionalHeaders: Headers? = null, cache: Boolean = true): Request {
        // pages no longer exist, if app attempts to go to the first page after a request, do not include the page append
        val pageIndex = if (page == 1) null else page
        return GET(
            pageIndex?.let {
                addParam(url, "next", lastMangaId)
            } ?: url,
            additionalHeaders?.let { header ->
                val headers = headers.newBuilder()
                header.toMultimap().forEach { (t, u) ->
                    u.forEach {
                        headers.add(t, it)
                    }
                }
                headers.build()
            } ?: headers,

        ).let {
            if (!cache) {
                it.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()
            } else {
                it
            }
        }
    }

    /**
     * Parse gallery page to metadata model
     */
    @SuppressLint("DefaultLocale")
    override fun mangaDetailsParse(response: Response) = with(response.asJsoup()) {
        with(ExGalleryMetadata()) {
            url = response.request.url.encodedPath
            title = select("#gn").text().nullIfBlank()?.trim()

            altTitle = select("#gj").text().nullIfBlank()?.trim()

            // Thumbnail is set as background of element in style attribute
            thumbnailUrl = select("#gd1 div").attr("style").nullIfBlank()?.let {
                it.substring(it.indexOf('(') + 1 until it.lastIndexOf(')'))
            }
            category = select("#gdc div").text().nullIfBlank()?.trim()?.lowercase()

            uploader = select("#gdn").text().nullIfBlank()?.trim()

            // Parse the table
            select("#gdd tr").forEach {
                it.select(".gdt1")
                    .text()
                    .nullIfBlank()
                    ?.trim()
                    ?.let { left ->
                        it.select(".gdt2")
                            .text()
                            .nullIfBlank()
                            ?.trim()
                            ?.let { right ->
                                ignore {
                                    when (
                                        left.removeSuffix(":")
                                            .lowercase()
                                    ) {
                                        "posted" -> datePosted = EX_DATE_FORMAT.parse(right)?.time ?: 0

                                        "visible" -> visible = right.nullIfBlank()

                                        "language" -> {
                                            language = right.removeSuffix(TR_SUFFIX).trim().nullIfBlank()
                                            translated = right.endsWith(TR_SUFFIX, true)
                                        }

                                        "file size" -> size = parseHumanReadableByteCount(right)?.toLong()

                                        "length" -> length = right.removeSuffix("pages").trim().nullIfBlank()?.toInt()

                                        "favorited" -> favorites = right.removeSuffix("times").trim().nullIfBlank()?.toInt()
                                    }
                                }
                            }
                    }
            }

            // Parse ratings
            ignore {
                averageRating = select("#rating_label")
                    .text()
                    .removePrefix("Average:")
                    .trim()
                    .nullIfBlank()
                    ?.toDouble()
                ratingCount = select("#rating_count")
                    .text()
                    .trim()
                    .nullIfBlank()
                    ?.toInt()
            }

            // Parse tags
            tags.clear()
            select("#taglist tr").forEach {
                val namespace = it.select(".tc").text().removeSuffix(":")
                val currentTags = it.select("div").map { element ->
                    Tag(
                        element.text().trim(),
                        element.hasClass("gtl"),
                    )
                }
                tags[namespace] = currentTags
            }

            // Copy metadata to manga
            SManga.create().apply {
                copyTo(this)
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }
    }

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/g/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = "/g/$id/"
        return MangasPage(listOf(details), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (query.startsWith("https://")) {
        val url = query.toHttpUrl()
        if (url.pathSegments.size < 3) {
            throw Exception("Unsupported url")
        }
        val id = url.pathSegments[1]
        val key = url.pathSegments[2]
        fetchSearchManga(page, "${PREFIX_ID_SEARCH}$id/$key", filters)
    } else if (query.startsWith(PREFIX_ID_SEARCH)) {
        val id = query.removePrefix(PREFIX_ID_SEARCH)
        client.newCall(searchMangaByIdRequest(id))
            .asObservableSuccess()
            .map { response -> searchMangaByIdParse(response, id) }
    } else {
        super.fetchSearchManga(page, query, filters)
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    override fun pageListParse(response: Response) = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String = imageUrlParse(response, true)

    private fun imageUrlParse(response: Response, isGetBakImageUrl: Boolean): String {
        val doc = response.asJsoup()
        val imgUrl = doc.select("#img").attr("abs:src")
        // from https://github.com/Miuzarte/EHentai-go/blob/dd9a24adb13300c028c35f53b9eff31b51966def/query.go#L695
        val nlValue = Regex("nl\\('(.+?)'\\)").find(doc.selectFirst("#loadfail")?.attr("onclick").orEmpty())?.groupValues?.get(1)

        // from https://github.com/ccloli/E-Hentai-Downloader/blob/c51e1118def7541b5fbb224f7e512e170f4b9d5e/src/main.js#L2444
        if (getOriginalImagePref()) {
            val originalUrl = doc.selectFirst("a[href*=/fullimg/]")?.attr("abs:href")
            if (!originalUrl.isNullOrEmpty()) {
                return originalUrl.toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("nl", nlValue)
                    .build()
                    .toString()
            }
        }

        if (!isGetBakImageUrl) {
            return imgUrl
        }

        if (nlValue.isNullOrEmpty()) return imgUrl
        val bakUrl = response.request.url.newBuilder()
            .addQueryParameter("nl", nlValue)
            .toString()
        return "$imgUrl#$bakUrl"
    }

    /**
     * Builds the Cookie header with values that are read live from the
     * WebView cookie store / preferences, so a login performed through the
     * WebView takes effect immediately instead of after an app restart.
     *
     * Only non-empty values are included: sending empty ipb/igneous cookies
     * makes exhentai.org skip the sign-in redirect and reply with the sad
     * panda page straight away.
     */
    private fun buildCookiesHeader(igneousOverride: String? = null): String {
        val cookies = mutableMapOf<String, String>()

        // Setup settings
        val settings = mutableListOf<String>()

        // Do not show popular right now pane as we can't parse it
        settings += "prn_n"

        // Exclude every other language except the one we have selected
        settings += "xl_" + languageMappings.filter { it.first != ehLang }
            .flatMap { it.second }
            .joinToString("x")

        cookies["uconfig"] = buildSettings(settings)

        // Bypass "Offensive For Everyone" content warning
        cookies["nw"] = "1"

        getMemberId().takeIf { it.isNotEmpty() }?.let { cookies["ipb_member_id"] = it }

        getPassHash().takeIf { it.isNotEmpty() }?.let { cookies["ipb_pass_hash"] = it }

        val igneous = igneousOverride ?: getIgneous()
        if (igneous.isNotEmpty()) {
            cookies["igneous"] = igneous
        }

        return buildCookies(cookies)
    }

    // Headers
    override fun headersBuilder() = super.headersBuilder().add("Cookie", buildCookiesHeader())

    private fun buildSettings(settings: List<String?>) = settings.filterNotNull().joinToString(separator = "-")

    private fun buildCookies(cookies: Map<String, String>) = cookies.entries.joinToString(separator = "; ", postfix = ";") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    /**
     * Client used exclusively for the ExHentai sign-in handshake. Redirects
     * are followed manually so the right cookies can be attached to each
     * hop of the chain (the main client has no cookie jar at all).
     */
    private val ssoClient by lazy {
        network.client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** epoch millis of the last sign-in attempt, to avoid hammering exhentai */
    private val lastSignInAttempt = AtomicLong(0)

    private val signInLock = Any()

    /**
     * Completes the ExHentai login over the same SSO bounce a browser uses:
     *
     *   exhentai.org -> forums.e-hentai.org/remoteapi.php?ex=<token>
     *               (validates ipb_member_id / ipb_pass_hash)
     *             -> exhentai.org/?poni=<token> -> Set-Cookie: igneous=<real>
     *
     * Returns the igneous value on success, or null when the account has no
     * ExHentai access / is not logged in to e-hentai.
     */
    private fun signInToExHentai(memberId: String, passHash: String): String? {
        val ipbCookies = "ipb_member_id=$memberId; ipb_pass_hash=$passHash"
        val baseHeaders = headers.newBuilder().apply { removeAll("Cookie") }.build()

        fun call(url: String, cookie: String? = null): Response {
            val request = Request.Builder()
                .url(url)
                .headers(baseHeaders)
                .apply { cookie?.let { header("Cookie", it) } }
                .build()
            return ssoClient.newCall(request).execute()
        }

        fun igneousFrom(response: Response): String? = response.headers.values("Set-Cookie")
            .firstNotNullOfOrNull { header ->
                header.split(";").firstOrNull { it.trim().startsWith("igneous=") }
            }
            ?.substringAfter("igneous=")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "mystery" }

        // 1. hit exhentai.org with no cookies to obtain the SSO bounce URL
        call("https://exhentai.org/").use { first ->
            val bounce = first.headers["Location"]
                ?.takeIf { first.code in 300..399 }
                ?.takeIf { it.contains("forums.e-hentai.org/remoteapi.php") }
                ?: return null

            // 2. let the forums validate the e-hentai login cookies
            call(bounce, ipbCookies).use { forums ->
                val back = forums.headers["Location"]
                    ?.takeIf { forums.code in 300..399 }
                    ?: return null

                // not logged in, or the account has no ExHentai access
                if (Uri.parse(back).getQueryParameter("poni") == "no") return null

                // 3. land back on exhentai.org, which grants the igneous
                // cookie (possibly over one extra redirect)
                var url = back
                repeat(4) {
                    call(url, ipbCookies).use { landing ->
                        igneousFrom(landing)?.let { return it }

                        url = landing.headers["Location"]
                            ?.takeIf { landing.code in 300..399 }
                            ?: return null
                    }
                }
            }
        }
        return null
    }

    /**
     * Makes sure an igneous value is available when browsing exhentai.org.
     * Runs the SSO sign-in at most once every [SIGN_IN_COOLDOWN_MS]; the
     * obtained value is persisted and also shared with the WebView cookie
     * store so "Open in WebView" stays logged in as well.
     */
    private fun ensureExHentaiSignIn(force: Boolean = false): String {
        val memberId = getMemberId()
        val passHash = getPassHash()
        if (memberId.isEmpty() || passHash.isEmpty()) return ""

        getIgneous().takeIf { it.isNotEmpty() }?.let { return it }

        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastSignInAttempt.get() < SIGN_IN_COOLDOWN_MS) return ""
        }

        synchronized(signInLock) {
            // another thread may have finished the sign-in while we waited
            getIgneous().takeIf { it.isNotEmpty() }?.let { return it }
            lastSignInAttempt.set(System.currentTimeMillis())

            val igneous = runCatching { signInToExHentai(memberId, passHash) }.getOrNull()
            if (igneous != null) {
                preferences.edit().putString(IGNEOUS_PREF_KEY, igneous).apply()

                // share the ExHentai session with the WebView cookie store
                runCatching {
                    webViewCookieManager.setAcceptCookie(true)
                    webViewCookieManager.setCookie(exCookieUrl, "igneous=$igneous")
                    webViewCookieManager.setCookie(exCookieUrl, "ipb_member_id=$memberId")
                    webViewCookieManager.setCookie(exCookieUrl, "ipb_pass_hash=$passHash")
                    CookieManager.getInstance().flush()
                }
                return igneous
            }
        }
        return ""
    }

    @Suppress("SameParameterValue")
    private fun addParam(url: String, param: String, value: String) = Uri.parse(url)
        .buildUpon()
        .appendQueryParameter(param, value)
        .toString()

    override val client = network.client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .addInterceptor { chain ->
            val request = chain.request()
            val result = runCatching { chain.proceed(request) }
            val bakUrl = request.url.fragment
                ?: return@addInterceptor result.getOrThrow()

            if (result.isFailure || result.getOrNull()?.isSuccessful != true) {
                result.getOrNull()?.close()
                val newRequest = GET(bakUrl, headers)
                val newImageUrl = imageUrlParse(chain.proceed(newRequest), false)
                val newImageRequest = request.newBuilder()
                    .url(newImageUrl)
                    .build()

                chain.proceed(newImageRequest)
            } else {
                result.getOrThrow()
            }
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host

            // No session cookies should leak to unrelated image hosts
            val cookieHeader = if (host.endsWith("e-hentai.org") || host.endsWith("exhentai.org")) {
                // completing the ExHentai sign-in is only relevant on exhentai
                val igneous = if (host == "exhentai.org") {
                    ensureExHentaiSignIn()
                } else {
                    getIgneous()
                }
                buildCookiesHeader(igneous.takeIf { it.isNotEmpty() })
            } else {
                null
            }

            val newReq = request.newBuilder()
                .removeHeader("Cookie")
                .apply {
                    cookieHeader?.let { addHeader("Cookie", it) }
                }
                .build()

            val response = chain.proceed(newReq)

            harvestIgneous(response)

            if (host == "exhentai.org" && isSadPanda(response)) {
                response.close()

                val sentIgneous = cookieHeader?.contains("igneous=") == true
                if (sentIgneous) {
                    // the stored igneous went stale while the e-hentai login
                    // may still be fine: drop it, sign in again, retry once
                    clearStoredIgneous()
                    val freshIgneous = ensureExHentaiSignIn(force = true)

                    if (freshIgneous.isNotEmpty()) {
                        val retryReq = request.newBuilder()
                            .removeHeader("Cookie")
                            .addHeader("Cookie", buildCookiesHeader(freshIgneous))
                            .build()

                        val retryResponse = chain.proceed(retryReq)
                        harvestIgneous(retryResponse)

                        if (!isSadPanda(retryResponse)) {
                            return@addInterceptor retryResponse
                        }
                        retryResponse.close()
                    }
                }

                throw Exception(sadPandaMessage)
            }

            response
        }.build()

    /** exhentai.org's rejection marker for invalid sessions */
    private fun isSadPanda(response: Response): Boolean =
        response.headers.values("Set-Cookie").any { it.trim().startsWith("igneous=mystery") }

    private fun clearStoredIgneous() {
        preferences.edit().putString(IGNEOUS_PREF_KEY, IGNEOUS_PREF_DEFAULT_VALUE).apply()
        runCatching {
            webViewCookieManager.setCookie(exCookieUrl, "igneous=; Max-Age=0; Path=/")
        }
    }

    /**
     * Keeps the stored igneous fresh: exhentai rotates the value over time
     * and hands out the current one in Set-Cookie on every response.
     */
    private fun harvestIgneous(response: Response) {
        if (!response.request.url.host.endsWith("exhentai.org")) return

        response.headers.values("Set-Cookie")
            .firstOrNull { it.trim().startsWith("igneous=") }
            ?.substringAfter("igneous=")
            ?.substringBefore(";")
            ?.trim()
            ?.asIgneous()
            ?.let { fresh ->
                if (fresh != preferences.getString(IGNEOUS_PREF_KEY, IGNEOUS_PREF_DEFAULT_VALUE)) {
                    preferences.edit().putString(IGNEOUS_PREF_KEY, fresh).apply()
                    runCatching {
                        webViewCookieManager.setCookie(exCookieUrl, "igneous=$fresh")
                    }
                }
            }
    }

    /**
     * exhentai.org answers 200 with an (almost) empty body and
     * `Set-Cookie: igneous=mystery` when the session is rejected — the
     * infamous sad panda. This surfaces a readable error instead of an
     * empty "no results" list.
     */
    private val sadPandaMessage =
        "ExHentai rejected this login (sad panda)." + "\n\n" +
            "1. Open this source in WebView and make sure you are logged in on e-hentai.org" + "\n" +
            "2. Come back and browse again — the ExHentai sign-in completes automatically" + "\n" +
            "3. If it still fails, your account may not have ExHentai access;" + "\n" +
            "   enable 'Force e-hentai' in the extension settings to keep using e-hentai.org"

    // Filters
    override fun getFilterList() = FilterList(
        EnforceLanguageFilter(getEnforceLanguagePref()),
        Favorites(),
        Watched(),
        GenreGroup(),
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        Filter.Header("Use 'Female Tags' or 'Male Tags' for specific categories. 'Tags' searches all categories."),
        TextFilter("Tags", "tag"),
        TextFilter("Female Tags", "female"),
        TextFilter("Male Tags", "male"),
        AdvancedGroup(),
    )

    internal open class TextFilter(name: String, val type: String, val specific: String = "") : Text(name)

    class Watched :
        CheckBox("Watched List"),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendPath("watched")
            }
        }
    }

    class Favorites :
        CheckBox("Favorites"),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendPath("favorites.php")
            }
        }
    }

    class GenreOption(name: String, private val genreId: String) :
        CheckBox(name, false),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            builder.appendQueryParameter("f_$genreId", if (state) "1" else "0")
        }
    }

    class GenreGroup :
        UriGroup<GenreOption>(
            "Genres",
            listOf(
                GenreOption("Dōjinshi", "doujinshi"),
                GenreOption("Manga", "manga"),
                GenreOption("Artist CG", "artistcg"),
                GenreOption("Game CG", "gamecg"),
                GenreOption("Western", "western"),
                GenreOption("Non-H", "non-h"),
                GenreOption("Image Set", "imageset"),
                GenreOption("Cosplay", "cosplay"),
                GenreOption("Asian Porn", "asianporn"),
                GenreOption("Misc", "misc"),
            ),
        )

    class AdvancedOption(name: String, private val param: String, defValue: Boolean = false) :
        CheckBox(name, defValue),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendQueryParameter(param, "on")
            }
        }
    }

    open class PageOption(name: String, private val queryKey: String) :
        Text(name),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state.isNotBlank()) {
                if (builder.build().getQueryParameters("f_sp").isEmpty()) {
                    builder.appendQueryParameter("f_sp", "on")
                }

                builder.appendQueryParameter(queryKey, state.trim())
            }
        }
    }

    class MinPagesOption : PageOption("Minimum Pages", "f_spf")
    class MaxPagesOption : PageOption("Maximum Pages", "f_spt")

    class RatingOption :
        Select<String>(
            "Minimum Rating",
            arrayOf(
                "Any",
                "2 stars",
                "3 stars",
                "4 stars",
                "5 stars",
            ),
        ),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state > 0) {
                builder.appendQueryParameter("f_srdd", (state + 1).toString())
                builder.appendQueryParameter("f_sr", "on")
            }
        }
    }

    // Explicit type arg for listOf() to workaround this: KT-16570
    class AdvancedGroup :
        UriGroup<Filter<*>>(
            "Advanced Options",
            listOf(
                AdvancedOption("Search Gallery Name", "f_sname", true),
                AdvancedOption("Search Gallery Tags", "f_stags", true),
                AdvancedOption("Search Gallery Description", "f_sdesc"),
                AdvancedOption("Search Torrent Filenames", "f_storr"),
                AdvancedOption("Only Show Galleries With Torrents", "f_sto"),
                AdvancedOption("Search Low-Power Tags", "f_sdt1"),
                AdvancedOption("Search Downvoted Tags", "f_sdt2"),
                AdvancedOption("Show Expunged Galleries", "f_sh"),
                RatingOption(),
                MinPagesOption(),
                MaxPagesOption(),
            ),
        )

    private class EnforceLanguageFilter(default: Boolean) : CheckBox("Enforce language", default)

    // map languages to their internal ids
    private val languageMappings = listOf(
        Pair("japanese", listOf("0", "1024", "2048")),
        Pair("english", listOf("1", "1025", "2049")),
        Pair("chinese", listOf("10", "1034", "2058")),
        Pair("dutch", listOf("20", "1044", "2068")),
        Pair("french", listOf("30", "1054", "2078")),
        Pair("german", listOf("40", "1064", "2088")),
        Pair("hungarian", listOf("50", "1074", "2098")),
        Pair("italian", listOf("60", "1084", "2108")),
        Pair("korean", listOf("70", "1094", "2118")),
        Pair("polish", listOf("80", "1104", "2128")),
        Pair("portuguese", listOf("90", "1114", "2138")),
        Pair("russian", listOf("100", "1124", "2148")),
        Pair("spanish", listOf("110", "1134", "2158")),
        Pair("thai", listOf("120", "1144", "2168")),
        Pair("vietnamese", listOf("130", "1154", "2178")),
        Pair("n/a", listOf("254", "1278", "2302")),
        Pair("other", listOf("255", "1279", "2303")),
    )

    companion object {
        const val QUERY_PREFIX = "?f_apply=Apply+Filter"
        const val PREFIX_ID_SEARCH = "id:"
        const val TR_SUFFIX = "TR"

        // Preferences vals
        private const val ENFORCE_LANGUAGE_PREF_KEY = "ENFORCE_LANGUAGE"
        private const val ENFORCE_LANGUAGE_PREF_TITLE = "Enforce Language"
        private const val ENFORCE_LANGUAGE_PREF_SUMMARY = "If checked, forces browsing of manga matching a language tag"
        private const val ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE = false

        private const val ORIGINAL_IMAGE_PREF_KEY = "ORIGINAL_IMAGE"
        private const val ORIGINAL_IMAGE_PREF_TITLE = "Original Image"
        private const val ORIGINAL_IMAGE_PREF_SUMMARY = "If checked, if your account has permission, it will use the original image and the image enhancement process will be slower"
        private const val ORIGINAL_IMAGE_PREF_DEFAULT_VALUE = false

        private const val MEMBER_ID_PREF_KEY = "MEMBER_ID"
        private const val MEMBER_ID_PREF_TITLE = "ipb_member_id"
        private const val MEMBER_ID_PREF_SUMMARY = "Leave empty to pick the login up from the WebView automatically.\nOnly needed as a manual override."
        private const val MEMBER_ID_PREF_DEFAULT_VALUE = ""

        private const val PASS_HASH_PREF_KEY = "PASS_HASH"
        private const val PASS_HASH_PREF_TITLE = "ipb_pass_hash"
        private const val PASS_HASH_PREF_SUMMARY = "Leave empty to pick the login up from the WebView automatically.\nOnly needed as a manual override."
        private const val PASS_HASH_PREF_DEFAULT_VALUE = ""

        private const val IGNEOUS_PREF_KEY = "IGNEOUS"
        private const val IGNEOUS_PREF_TITLE = "igneous"
        private const val IGNEOUS_PREF_SUMMARY = "ExHentai session cookie. Filled in automatically after logging in on e-hentai.org through the WebView; a manual value is only needed if that fails."
        private const val IGNEOUS_PREF_DEFAULT_VALUE = ""

        private const val FORCE_EH = "FORCE_EH"
        private const val FORCE_EH_TITLE = "Force e-hentai"
        private const val FORCE_EH_SUMMARY = "Browse e-hentai.org only. Uncheck to use exhentai.org when logged in (the ExHentai sign-in completes automatically)"
        private const val FORCE_EH_DEFAULT_VALUE = false

        /** minimum time between automatic ExHentai sign-in attempts */
        private const val SIGN_IN_COOLDOWN_MS = 60_000L
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val forceEhPref = CheckBoxPreference(screen.context).apply {
            key = FORCE_EH
            title = FORCE_EH_TITLE
            summary = FORCE_EH_SUMMARY
            setDefaultValue(FORCE_EH_DEFAULT_VALUE)
        }

        val enforceLanguagePref = CheckBoxPreference(screen.context).apply {
            key = "${ENFORCE_LANGUAGE_PREF_KEY}_$lang"
            title = ENFORCE_LANGUAGE_PREF_TITLE
            summary = ENFORCE_LANGUAGE_PREF_SUMMARY
            setDefaultValue(ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE)
        }

        val originalImagePref = CheckBoxPreference(screen.context).apply {
            key = "${ORIGINAL_IMAGE_PREF_KEY}_$lang"
            title = ORIGINAL_IMAGE_PREF_TITLE
            summary = ORIGINAL_IMAGE_PREF_SUMMARY
            setDefaultValue(ORIGINAL_IMAGE_PREF_DEFAULT_VALUE)
        }

        val memberIdPref = EditTextPreference(screen.context).apply {
            key = MEMBER_ID_PREF_KEY
            title = MEMBER_ID_PREF_TITLE
            summary = MEMBER_ID_PREF_SUMMARY

            setDefaultValue(MEMBER_ID_PREF_DEFAULT_VALUE)
        }

        val passHashPref = EditTextPreference(screen.context).apply {
            key = PASS_HASH_PREF_KEY
            title = PASS_HASH_PREF_TITLE
            summary = PASS_HASH_PREF_SUMMARY

            setDefaultValue(PASS_HASH_PREF_DEFAULT_VALUE)
        }

        val igneousPref = EditTextPreference(screen.context).apply {
            key = IGNEOUS_PREF_KEY
            title = IGNEOUS_PREF_TITLE
            summary = IGNEOUS_PREF_SUMMARY

            setDefaultValue(IGNEOUS_PREF_DEFAULT_VALUE)
        }

        screen.addPreference(forceEhPref)
        screen.addPreference(memberIdPref)
        screen.addPreference(passHashPref)
        screen.addPreference(igneousPref)
        screen.addPreference(originalImagePref)
        screen.addPreference(enforceLanguagePref)
    }

    private fun getEnforceLanguagePref(): Boolean = preferences.getBoolean("${ENFORCE_LANGUAGE_PREF_KEY}_$lang", ENFORCE_LANGUAGE_PREF_DEFAULT_VALUE)

    private fun getOriginalImagePref(): Boolean = preferences.getBoolean("${ORIGINAL_IMAGE_PREF_KEY}_$lang", ORIGINAL_IMAGE_PREF_DEFAULT_VALUE)

    /**
     * Reads a cookie from the WebView cookie store, trying each URL in
     * order and returning the first non-empty value found. Returns null when
     * the cookie is nowhere to be found, so callers can fall back to the
     * manually-entered preference.
     */
    private fun getCookieFromWebviews(name: String, vararg urls: String): String? {
        for (url in urls) {
            val jar = runCatching { webViewCookieManager.getCookie(url) }.getOrNull() ?: continue

            jar.split(";").forEach { raw ->
                val cookie = raw.trim()
                if (cookie.startsWith("$name=")) {
                    val value = cookie.substringAfter("=", "").trim()
                    if (value.isNotEmpty()) return value
                }
            }
        }
        return null
    }

    private fun getForceEhPref(): Boolean = preferences.getBoolean(FORCE_EH, FORCE_EH_DEFAULT_VALUE)
}
