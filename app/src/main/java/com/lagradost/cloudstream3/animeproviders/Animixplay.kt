package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.JsonObject
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URI

class Animixplay : MainAPI() {
    override var mainUrl = "https://animixplay.to"
    override var name = "Animixplay"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String?): TvType {
            return when {
                t?.contains("TV") == true -> TvType.Anime
                t?.contains("Movie") == true -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val home = listOf(
            "$mainUrl/assets/s/featured.json" to "Sub",
            "$mainUrl/api/search" to "Dub",
            "$mainUrl/a/XsWgdGCnKJfNvDFAM28EV" to "All",
            "$mainUrl/assets/popular/popular.json" to "Popular",
            "$mainUrl/api/search" to "Movie",
        ).apmap { (url, name) ->
            val headers = when (name) {
                "Dub" -> mapOf("seasonaldub" to "3020-05-06 00:00:00")
                "All" -> mapOf("recent" to "3020-05-06 00:00:00")
                "Movie" -> mapOf("movie" to "99999999")
                else -> mapOf()
            }
            val item = when (name) {
                "Sub" -> {
                    tryParseJson<ArrayList<Anime>>(
                        app.get(
                            "$mainUrl/assets/s/featured.json",
                            referer = mainUrl
                        ).text
                    )
                }
                "Popular" -> {
                    app.get(
                        url,
                        referer = mainUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).parsedSafe<Result>()?.result
                }
                else -> {
                    app.post(
                        url,
                        data = headers,
                        referer = mainUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).parsedSafe<Result>()?.result
                }
            }?.mapNotNull {
                it.toSearchResponse()
            } ?: throw ErrorLoadingException("No media found")
            HomePageList(name, item)
        }.filter { it.list.isNotEmpty() }

        return HomePageResponse(home)

    }

    private fun Anime.toSearchResponse(): AnimeSearchResponse? {
        return newAnimeSearchResponse(
            title ?: return null,
            fixUrl(url ?: return null),
            TvType.TvSeries,
        ) {
            this.posterUrl = img ?: picture
            addDubStatus(
                isDub = title.contains("Dub"),
                episodes = Regex("EP\\s([0-9]+)/").find(
                    infotext ?: ""
                )?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()
            )
        }
    }

    override suspend fun quickSearch(query: String) = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.post(
            "https://cdn.animixplay.to/api/search",
            data = mapOf("qfast" to query, "root" to URI(mainUrl).host)
        ).parsedSafe<Search>()?.result?.let {
            Jsoup.parse(it).select("a").map { elem ->
                val href = elem.attr("href")
                val title = elem.select("p.name").text()
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = elem.select("img").attr("src")
                    addDubStatus(isDub = title.contains("Dub"))
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        val (fixUrl, malId) = if (url.contains("/anime/")) {
            listOf(url, Regex("anime/([0-9]+)/").find(url)?.groupValues?.get(1))
        } else {
            val malId = app.get(url).text.substringAfterLast("var malid = '").substringBefore("';")
            listOf("$mainUrl/anime/$malId", malId)
        }

        val anilistId = app.post(
            "https://graphql.anilist.co/", data = mapOf(
                "query" to "{Media(idMal:$malId,type:ANIME){id}}",
            )
        ).parsedSafe<DataAni>()?.data?.media?.id

        val res = app.get("$mainUrl/assets/mal/$malId.json").parsedSafe<AnimeDetail>()
            ?: throw ErrorLoadingException("Invalid json responses")

        val episode = app.post("$mainUrl/api/search", data = mapOf("recomended" to "$malId"))
            .parsedSafe<Data>()?.data?.first()?.items?.let {
//                it.first().url to it.last().url
                val epData = app.get(it.first().url ?: return@let null).document.select("div#epslistplace").text()
                val json = tryParseJson<JsonObject>(epData)
                val total = json?.get("eptotal")?.toString()?.replace("\"", "")?.toInt()
                val data = "test"
            }

        val recommendations = app.get("$mainUrl/assets/similar/$malId.json")
            .parsedSafe<RecResult>()?.recommendations?.mapNotNull { rec ->
                newAnimeSearchResponse(
                    rec.title ?: return@mapNotNull null,
                    "$mainUrl/${rec.malId}",
                    TvType.Anime
                ) {
                    this.posterUrl = rec.imageUrl
                    addDubStatus(dubExist = false, subExist = true)
                }
            }

        return newAnimeLoadResponse(
            res.title ?: return null,
            url,
            getType(res.type)
        ) {
            engName = res.title
            posterUrl = res.imageUrl
            this.year = res.aired?.from?.split("-")?.firstOrNull()?.toIntOrNull()
//            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(res.status)
            plot = res.synopsis
            this.tags = res.genres?.mapNotNull { it.name }
            this.recommendations = recommendations
            addMalId(malId?.toIntOrNull())
            addAniListId(anilistId?.toIntOrNull())
            addTrailer(res.trailerUrl)
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val iframe = app.get(data)
        val iframeDoc = iframe.document

        argamap({
            iframeDoc.select(".list-server-items > .linkserver")
                .forEach { element ->
                    val status = element.attr("data-status") ?: return@forEach
                    if (status != "1") return@forEach
                    val extractorData = element.attr("data-video") ?: return@forEach
                    loadExtractor(extractorData, iframe.url, subtitleCallback, callback)
                }
        }, {
            val iv = "9262859232435825"
            val secretKey = "93422192433952489752342908585752"
            val secretDecryptKey = "93422192433952489752342908585752"
            GogoanimeProvider.extractVidstream(
                iframe.url,
                this.name,
                callback,
                iv,
                secretKey,
                secretDecryptKey,
                isUsingAdaptiveKeys = false,
                isUsingAdaptiveData = true,
                iframeDocument = iframeDoc
            )
        })
        return true
    }


    private data class IdAni(
        @JsonProperty("id") val id: String? = null,
    )

    private data class MediaAni(
        @JsonProperty("Media") val media: IdAni? = null,
    )

    private data class DataAni(
        @JsonProperty("data") val data: MediaAni? = null,
    )

    private data class Items(
        @JsonProperty("url") val url: String? = null,
    )

    private data class Episode(
        @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
    )

    private data class Data(
        @JsonProperty("data") val data: ArrayList<Episode>? = arrayListOf(),
    )

    private data class Aired(
        @JsonProperty("from") val from: String? = null,
    )

    private data class Genres(
        @JsonProperty("name") val name: String? = null,
    )

    private data class RecResult(
        @JsonProperty("recommendations") val recommendations: ArrayList<Recommendations>? = arrayListOf(),
    )

    private data class Recommendations(
        @JsonProperty("mal_id") val malId: String? = null,
        @JsonProperty("image_url") val imageUrl: String? = null,
        @JsonProperty("title") val title: String? = null,
    )

    private data class AnimeDetail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("image_url") val imageUrl: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("aired") val aired: Aired? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("trailer_url") val trailerUrl: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
    )

    private data class Search(
        @JsonProperty("result") val result: String? = null,
    )

    private data class Result(
        @JsonProperty("result") val result: ArrayList<Anime> = arrayListOf(),
    )

    private data class Anime(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("picture") val picture: String? = null,
        @JsonProperty("infotext") val infotext: String? = null,
    )

}