package com.lagradost.cloudstream3.animeproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class TurkanimeProvider : MainAPI() {
    override var mainUrl = "https://www.turkanime.co"
    override var name = "Turkanime"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("TV") -> TvType.Anime
                t.contains("Movie") -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/ajax/yenieklenenanime&sayfa=" to "Yeni Eklenen Bölümler",
        "$mainUrl/ajax/yenieklenenanime2022&sayfa=" to "Yeni Eklenen Bölümler 2022",
        "$mainUrl/ajax/yenieklenenseriler&sayfa=" to "Yeni Eklenen Animeler",
        "$mainUrl/ajax/rankagore&sayfa=" to "Popüler Animeler",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(
            request.data + page, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).document
        val home = document.select("div.col-md-6.col-sm-6.col-xs-12").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            "$mainUrl/anime/${uri.substringAfter("/video/").replace(Regex("-[0-9]+-bolum"), "")}"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(this.selectFirst("a")!!.attr("href"))
        val title = this.selectFirst("a")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val episode = this.selectFirst("a")?.attr("title")?.let {
            Regex("([0-9]+).\\s?Bölüm").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post("$mainUrl/arama", data = mapOf("arama" to query)).document

        return document.select("div.col-md-6.col-sm-6.col-xs-12").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val id = document.selectFirst("div.oylama")?.attr("data-id")
        val title = document.selectFirst("div#detayPaylas div.panel-title")!!.text().trim()
        val poster = fixUrlNull(document.select("div.imaj > img").attr("data-src"))
        val tags = document.select("div#detayPaylas tbody tr td b:contains(Anime Türü)")
            .parents()[1].select("td:last-child a").map { it.text() }
        val year = document.select("div#detayPaylas tbody tr td b:contains(Başlama Tarihi)")
            .parents()[1].select("td:last-child a").text().trim().toIntOrNull()
        val type =
            getType(document.select("div#detayPaylas tbody tr:first-child td:nth-child(3)").text())
        val description = document.select("div#detayPaylas tbody tr:last-child p").text().trim()
        val trailer = app.get(
            "$mainUrl/ajax/fragman&animeId=$id", headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).document.select("a").map { it.attr("href") }

        val episodes = app.get(
            "$mainUrl/ajax/bolumler&animeId=$id", headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).document.select("ul.menum > li").map {
            val name = it.select("a:last-child").attr("title")
            val link = fixUrl(it.select("a:last-child").attr("href"))
            Episode(link, name)
        }

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            this.tags = tags
            addTrailer(trailer)
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val sources = document.select(".mobius > .mirror > option").mapNotNull {
            fixUrl(Jsoup.parse(base64Decode(it.attr("value"))).select("iframe").attr("src"))
        }

        sources.apmap {
            loadExtractor(it, data, subtitleCallback, callback)
        }

        return true
    }
}