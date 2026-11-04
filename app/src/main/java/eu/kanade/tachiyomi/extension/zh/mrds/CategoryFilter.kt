package eu.kanade.tachiyomi.extension.zh.mrds

import eu.kanade.tachiyomi.source.model.Filter

/**
 * Content categories of 每日大赛.
 *
 * `mrds` ("每日大赛") is the site's main feed, so it is both the default browse category and the
 * first entry here.
 */
class CategoryFilter : Filter.Select<String>("分类", LABELS) {

    /** Slug for the selected category, or null when "全部" is selected. */
    val slug: String?
        get() = if (state <= 0 || state >= SLUGS.size) null else SLUGS[state]
}

private val SLUGS = arrayOf(
    "",
    "mrds",
    "ztds",
    "rstt",
    "xazd",
    "blyp",
    "fctg",
    "mhds",
    "lqdp",
    "jdsj",
    "mxwh",
    "smdh",
    "dypd",
    "mtds",
    "ysds",
    "czds",
    "hjds",
    "tgds",
    "omjp",
    "qwcs",
    "aijc",
)

private val LABELS = arrayOf(
    "全部",
    "每日大赛",
    "主题大赛",
    "热搜吃瓜",
    "校园学生",
    "必撸大赛",
    "反差泄密",
    "网红黑料",
    "猎奇重口",
    "AV看片",
    "明星大赛",
    "动漫之家",
    "影视国漫",
    "cos写真",
    "声控ASMR",
    "寸止挑战",
    "混剪PMV",
    "原创投稿",
    "欧美精品",
    "全网参赛",
    "AI剧场",
)
