package com.lagradost.cloudstream3.liveproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class OnetwothreeTv : MainAPI() {
    override var mainUrl = "http://123tv.live"
    override var name = "123tv"
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Live
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = ArrayList<HomePageList>()
        listOf(
            Pair("$mainUrl/category/united-states-usa/", "United States (USA)"),
            Pair("$mainUrl/top-streams/", "Top Streams"),
            Pair("$mainUrl/latest-streams/", "Latest Streams")
        ).apmap {
            val home =
                app.get(it.first).document.select("div.videos-latest-list.row div.col-md-3.col-sm-6")
                    .mapNotNull { item ->
                        item.toSearchResult()
                    }
            if (home.isNotEmpty()) homePageList.add(HomePageList(it.second, home, true))
        }
        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): LiveSearchResponse? {
        return LiveSearchResponse(
            this.selectFirst("div.video-title h4")?.text() ?: return null,
            fixUrl(this.selectFirst("a")!!.attr("href")),
            this@OnetwothreeTv.name,
            TvType.Live,
            fixUrlNull(this.selectFirst("img")?.attr("src")),
        )

    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/?s=$query"
        ).document.select("div.videos-latest-list.row div.col-md-3.col-sm-6").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return LiveStreamLoadResponse(
            document.selectFirst("div.video-big-title h1")?.text() ?: return null,
            url,
            this.name,
            document.select("div.embed-responsive iframe").attr("src"),
            fixUrlNull(document.selectFirst("meta[name=\"twitter:image\"]")?.attr("content")),
            plot = document.select("div.watch-video-description p").text() ?: return null
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        app.get(
            data,
            referer = "http://123tv.live/"
        ).document.select("script").find { it.data().contains("var player=") }?.data()?.substringAfter("source:'")?.substringBefore("',")?.let { link ->
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    referer = "http://azureedge.xyz/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = mapOf("Origin" to "http://azureedge.xyz")
                )
            )
        }

        return true

    }
}