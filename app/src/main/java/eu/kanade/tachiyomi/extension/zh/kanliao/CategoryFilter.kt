package eu.kanade.tachiyomi.extension.zh.kanliao

import eu.kanade.tachiyomi.source.model.Filter

class CategoryFilter : Filter.Select<String>(
    "分类",
    arrayOf("全部", "dy", "ks", "douyu", "hy", "hj", "tt", "wh", "asmr", "xb", "xsp", "rdgz"),
)
