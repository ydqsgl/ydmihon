package eu.kanade.tachiyomi.extension.zh.chigua

import eu.kanade.tachiyomi.source.model.Filter

/**
 * Content categories exposed by 51吃瓜网.
 *
 * The site's `wpcz` (今日吃瓜) feed plays the same role as 今日看料's `dy`, so it is the default
 * browse category and the first entry here.
 */
class CategoryFilter : Filter.Select<String>("分类", LABELS) {

    /** Slug for the selected category, or null when "全部" is selected. */
    val slug: String?
        get() = if (state <= 0 || state >= SLUGS.size) null else SLUGS[state]
}

private val SLUGS = arrayOf(
    "",
    "wpcz",
    "rdsj",
    "mrdg",
    "bkdg",
    "mrds",
    "whhl",
    "whhj",
    "qghj",
    "cbdj",
    "51djc",
    "ysyl",
    "gcjq",
    "thjx",
    "xsxy",
    "snsn",
    "whmx",
    "hwcg",
    "rrcg",
    "ldcg",
    "jpll",
    "sjb",
    "qubk",
    "dcbq",
    "zzs",
    "cgxw",
    "yczq",
    "lldd",
    "51hd",
)

private val LABELS = arrayOf(
    "全部",
    "今日吃瓜",
    "热门大瓜",
    "吃瓜榜单",
    "必看大瓜",
    "每日大赛",
    "网红黑料",
    "网黄合集",
    "瓜友点播",
    "AI成人短剧",
    "51剧场",
    "成人视频",
    "国产视频",
    "探花精选",
    "学生校园",
    "骚男骚女",
    "明星爆料",
    "海外吃瓜",
    "人人吃瓜",
    "领导干部",
    "软萌甜妹",
    "竞技吃瓜",
    "吃瓜看戏",
    "擦边撩骚",
    "性爱技巧",
    "吃瓜新闻",
    "原创博主",
    "伦理道德",
    "往期活动",
)
