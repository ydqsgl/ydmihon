package eu.kanade.tachiyomi.ui.reader.viewer

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Referer rules the player applies per clip. */
class PlaybackRefererTest {

    @Test
    fun `every 123av cdn host uses the embed origin`() {
        // Observed in the wild for different clips and regions. Pinning one hostname is what broke
        // playback before, so all known shapes are covered here.
        listOf(
            "jl77.piper-store.site",
            "jl77.calmquietmind.site",
            "e625.highpurevibe.site",
            "some-future-host.example",
        ).forEach { host ->
            PlaybackReferer.refererFor("123av.com", host) shouldBe
                PlaybackReferer.TT123AV_EMBED_ORIGIN
        }
    }

    @Test
    fun `123av catalogue hosts keep the catalogue origin`() {
        // The site itself and its image CDN are not the clip CDN.
        PlaybackReferer.refererFor("123av.com", "123av.com") shouldBe "https://123av.com/"
        PlaybackReferer.refererFor("123av.com", "icdn.123av.me") shouldBe "https://123av.com/"
        PlaybackReferer.refererFor("123av.com", null) shouldBe "https://123av.com/"
    }

    @Test
    fun `other sources keep their own chapter origin`() {
        PlaybackReferer.refererFor("mrds.example", "cdn.example.net") shouldBe "https://mrds.example/"
        PlaybackReferer.refererFor("kanav.online", "xbv2608.com") shouldBe "https://kanav.online/"
        PlaybackReferer.refererFor("ttrgw.com", "webimg.rlkfus.cn") shouldBe "https://ttrgw.com/"
    }

    @Test
    fun `a 123av style cdn on another source is not overridden`() {
        // The CDN host alone must not trigger the 123AV rule.
        PlaybackReferer.refererFor("someother.site", "e625.highpurevibe.site") shouldBe
            "https://someother.site/"
    }

    @Test
    fun `unknown chapter url yields no referer`() {
        PlaybackReferer.refererFor(null, "jl77.piper-store.site").shouldBeNull()
        PlaybackReferer.refererFor("", "jl77.piper-store.site").shouldBeNull()
    }

    @Test
    fun `embed host is recognised`() {
        PlaybackReferer.isEmbedHost("sadie-shop.site") shouldBe true
        PlaybackReferer.isEmbedHost("piper-store.site") shouldBe false
        PlaybackReferer.isEmbedHost(null) shouldBe false
    }
}
