package com.ultra.assistant.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

class BrowserController(context: Context) {
    private val appContext = context.applicationContext

    fun openUrl(rawUrl: String): Result<String> {
        val normalized = normalizeSafeHttpUrl(rawUrl)
            ?: return Result.failure(IllegalArgumentException("Only http:// or https:// URLs are allowed."))
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            normalized
        }
    }

    fun searchWeb(query: String): Result<String> {
        val clean = query.trim()
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("Search query cannot be empty."))
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(clean, "UTF-8")
        return openUrl(url)
    }

    fun searchYouTube(query: String): Result<String> {
        val clean = query.trim()
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("YouTube search query cannot be empty."))
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val webUrl = "https://www.youtube.com/results?search_query=$encoded"
        val ytPackage = "com.google.android.youtube"
        val appIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(ytPackage)
            putExtra("query", clean)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val appSearchRes = runCatching {
            if (appIntent.resolveActivity(appContext.packageManager) != null) {
                appContext.startActivity(appIntent)
                webUrl
            } else {
                null
            }
        }.getOrNull()
        if (appSearchRes != null) return Result.success(appSearchRes)

        val ytViewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
            setPackage(ytPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            if (ytViewIntent.resolveActivity(appContext.packageManager) != null) {
                appContext.startActivity(ytViewIntent)
                webUrl
            } else {
                openUrl(webUrl).getOrThrow()
            }
        }
    }

    fun searchYouTubeMusic(query: String): Result<String> {
        val clean = query.trim()
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("Music query cannot be empty."))
        val url = "https://music.youtube.com/search?q=" + URLEncoder.encode(clean, "UTF-8")
        return openUrl(url)
    }

    private fun normalizeSafeHttpUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        val candidate = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else if (!trimmed.contains(":")) {
            "https://$trimmed"
        } else {
            return null // Reject javascript:, file:, content:, intent:, etc.
        }
        val uri = Uri.parse(candidate)
        val scheme = uri.scheme?.lowercase()
        return if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
            candidate
        } else {
            null
        }
    }
}
