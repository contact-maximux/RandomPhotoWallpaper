package com.example.randomphotowallpaper

import android.content.Context
import org.json.JSONArray

data class WallpaperQuote(
    val text: String,
    val author: String = "",
    val language: String = "",
    val category: String = ""
)

object QuoteRepository {

    @Volatile
    private var cachedQuotes: List<WallpaperQuote>? = null

    fun getRandomQuote(context: Context): WallpaperQuote {
        val quotes = cachedQuotes ?: synchronized(this) {
            cachedQuotes ?: loadQuotes(context).also {
                cachedQuotes = it
            }
        }

        return quotes.randomOrNull() ?: WallpaperQuote(
            text = "हर दिन एक नई शुरुआत है।",
            author = ""
        )
    }

    private fun loadQuotes(context: Context): List<WallpaperQuote> {
        return try {
            val json = context.assets
                .open("quotes.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            val array = JSONArray(json)
            val result = mutableListOf<WallpaperQuote>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val quote = item.optString("text").trim()

                if (quote.isNotEmpty()) {
                    result.add(
                        WallpaperQuote(
                            text = quote,
                            author = item.optString("author").trim()
                                .takeUnless { it == "null" } ?: "",
                            language = item.optString("language"),
                            category = item.optString("category")
                        )
                    )
                }
            }

            result.ifEmpty { fallbackQuotes }
        } catch (_: Exception) {
            fallbackQuotes
        }
    }

    private val fallbackQuotes = listOf(
        WallpaperQuote("हर दिन एक नई शुरुआत है।", language = "hi"),
        WallpaperQuote("Small steps create big changes.", language = "en"),
        WallpaperQuote("Bas chalte raho, raasta mil jayega.", language = "hinglish")
    )
}
