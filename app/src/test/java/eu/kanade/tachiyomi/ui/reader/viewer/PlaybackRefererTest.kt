package eu.kanade.tachiyomi.ui.reader.viewer

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Referer rules the player applies per clip. */
class PlaybackRefererTest {

    @Test
    fun `123av playlist on its rotating cdn uses the embed origin`() {
        // Both hosts are served for the same clip; matching only one of them broke playback.
        PlaybackReferer.refererFor("123av.com", "jl77.piper-store.site") shouldBe
            PlaybackReferer.TT123AV_EMBED_ORIGIN
        PlaybackReferer.refererFor("123av.com", "jl77.calmquietmind.site") shouldBe
            PlaybackReferer.TT123AV_EMBED_ORIGIN
        PlaybackReferer.refererFor("www.123av.com", "jl12.example-cdn.site") shouldBe
            PlaybackReferer.TT123AV_EMBED_ORIGIN
    }

    @Test
    fun `other sources keep their own chapter origin`() {
        PlaybackReferer.refererFor("mrds.example", "cdn.example.net") shouldBe "https://mrds.example/"
        PlaybackReferer.refererFor("kanav.online", "xbv2608.com") shouldBe "https://kanav.online/"
        PlaybackReferer.refererFor("ttrgw.com", "webimg.rlkfus.cn") shouldBe "https://ttrgw.com/"
    }

    @Test
    fun `a rotating cdn on a non-123av source is not overridden`() {
        // The CDN shape alone must not trigger the 123AV rule.
        PlaybackReferer.refererFor("someother.site", "jl77.piper-store.site") shouldBe
            "https://someother.site/"
    }

    @Test
    fun `123av on a non-cdn host keeps the chapter origin`() {
        PlaybackReferer.refererFor("123av.com", "icdn.123av.me") shouldBe "https://123av.com/"
    }

    @Test
    fun `unknown chapter url yields no referer`() {
        PlaybackReferer.refererFor(null, "jl77.piper-store.site").shouldBeNull()
        PlaybackReferer.refererFor("", "jl77.piper-store.site").shouldBeNull()
    }
}
