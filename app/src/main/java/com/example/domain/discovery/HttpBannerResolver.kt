package com.example.domain.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object HttpBannerResolver {

    data class HttpIdentity(
        val title: String? = null,
        val serverHeader: String? = null
    )

    /**
     * Probes open HTTP/HTTPS ports to read title and server banners for router/IoT identification.
     */
    suspend fun probeHttpIdentity(ip: String, openPorts: List<Int>): HttpIdentity? = withContext(Dispatchers.IO) {
        val webPorts = openPorts.filter { it in listOf(80, 8080, 443, 8008, 5000, 3000) }
        if (webPorts.isEmpty()) return@withContext null

        for (port in webPorts) {
            val scheme = if (port == 443) "https" else "http"
            val targetUrl = "$scheme://$ip:$port/"
            var conn: HttpURLConnection? = null
            try {
                val url = URL(targetUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 400
                conn.readTimeout = 400
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; NetManager Pro)")

                val serverHeader = conn.getHeaderField("Server")
                val stream = if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
                var title: String? = null

                if (stream != null) {
                    val reader = BufferedReader(InputStreamReader(stream))
                    val buffer = StringBuilder()
                    var line: String? = reader.readLine()
                    var linesRead = 0
                    while (line != null && linesRead < 30) {
                        buffer.append(line).append(" ")
                        if (line.contains("</title>", ignoreCase = true) || line.contains("</head>", ignoreCase = true)) {
                            break
                        }
                        line = reader.readLine()
                        linesRead++
                    }
                    reader.close()

                    val titleRegex = Regex("(?i)<title>(.*?)</title>")
                    val match = titleRegex.find(buffer.toString())
                    title = match?.groupValues?.getOrNull(1)?.trim()
                }

                val cleanTitle = cleanHtmlTitle(title)
                if (!cleanTitle.isNullOrBlank() || !serverHeader.isNullOrBlank()) {
                    return@withContext HttpIdentity(
                        title = cleanTitle,
                        serverHeader = serverHeader?.trim()
                    )
                }
            } catch (_: Exception) {
                // Next port
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        null
    }

    private fun cleanHtmlTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.replace(Regex("(?i)&nbsp;"), " ")
            .replace(Regex("(?i)&amp;"), "&")
            .replace(Regex("(?i)&lt;"), "<")
            .replace(Regex("(?i)&gt;"), ">")
            .replace(Regex("(?i)&quot;"), "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (clean.equals("404 Not Found", ignoreCase = true) ||
            clean.equals("403 Forbidden", ignoreCase = true) ||
            clean.equals("500 Internal Server Error", ignoreCase = true) ||
            clean.equals("Welcome", ignoreCase = true) ||
            clean.equals("Document", ignoreCase = true) ||
            clean.equals("Home", ignoreCase = true)
        ) {
            return null
        }
        return if (clean.length in 2..80) clean else null
    }
}
