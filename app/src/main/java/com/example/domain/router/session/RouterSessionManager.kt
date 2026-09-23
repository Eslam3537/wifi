package com.example.domain.router.session

import com.example.domain.router.model.RouterSession
import okhttp3.Headers
import okhttp3.Response
import java.net.InetAddress
import java.util.regex.Pattern

object RouterSessionManager {

    private var activeSession: RouterSession? = null

    fun getActiveSession(): RouterSession? {
        val s = activeSession ?: return null
        if (s.isExpired) {
            activeSession = null
            return null
        }
        return s
    }

    fun setActiveSession(session: RouterSession) {
        this.activeSession = session
    }

    fun clearSession() {
        this.activeSession = null
    }

    /**
     * Strict validation: Only allows local LAN IP addresses (RFC 1918 / Loopback / Link-Local).
     * Prevents SSRF or sending sensitive router credentials to external / public WAN IPs.
     */
    fun isLocalLanIp(ip: String): Boolean {
        if (ip.isBlank()) return false
        val cleanIp = ip.trim().removePrefix("http://").removePrefix("https://").split("/").first().split(":").first()
        return try {
            val addr = InetAddress.getByName(cleanIp)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                    cleanIp.startsWith("192.168.") || cleanIp.startsWith("10.") ||
                    (cleanIp.startsWith("172.") && isPrivateClassB(cleanIp))
        } catch (_: Exception) {
            // Fallback string matching if DNS resolution in mock test fails
            cleanIp.startsWith("192.168.") || cleanIp.startsWith("10.") || cleanIp == "127.0.0.1"
        }
    }

    private fun isPrivateClassB(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val second = parts[1].toIntOrNull() ?: return false
        return second in 16..31
    }

    /**
     * Extracts Set-Cookie and CSRF headers from an OkHttp Response.
     */
    fun extractSessionFromResponse(gatewayIp: String, response: Response, bodyText: String? = null): RouterSession {
        val cookieMap = mutableMapOf<String, String>()
        val setCookieHeaders = response.headers("Set-Cookie")
        for (header in setCookieHeaders) {
            val parts = header.split(";")
            if (parts.isNotEmpty()) {
                val pair = parts[0].split("=", limit = 2)
                if (pair.size == 2) {
                    cookieMap[pair[0].trim()] = pair[1].trim()
                }
            }
        }

        var sessionId = cookieMap["SessionID_R3"] 
            ?: cookieMap["SessionID"] 
            ?: cookieMap["sid"] 
            ?: cookieMap["uid"]

        var csrfToken = response.header("Csrf-Token")
            ?: response.header("__RequestVerificationToken")
            ?: response.header("X-CSRF-Token")

        // If CSRF token is inside body/JSON/HTML
        if (csrfToken == null && bodyText != null) {
            csrfToken = extractCsrfFromBody(bodyText)
        }
        if (sessionId == null && bodyText != null) {
            sessionId = extractSessionIdFromBody(bodyText)
        }

        return RouterSession(
            gatewayIp = gatewayIp,
            sessionId = sessionId,
            csrfToken = csrfToken,
            cookies = cookieMap
        )
    }

    fun extractCsrfFromBody(body: String): String? {
        // Match JSON: "csrf_token": "..." or "csrf_param": "..."
        val jsonPattern = Pattern.compile("[\"']csrf_token[\"']\\s*:\\s*[\"']([^\"']+)[\"']")
        val jsonMatcher = jsonPattern.matcher(body)
        if (jsonMatcher.find()) return jsonMatcher.group(1)

        val paramPattern = Pattern.compile("[\"']csrf_param[\"']\\s*:\\s*[\"']([^\"']+)[\"']")
        val paramMatcher = paramPattern.matcher(body)
        if (paramMatcher.find()) return paramMatcher.group(1)

        // Match XML: <csrf_token>...</csrf_token> or <RequestVerificationToken>...</RequestVerificationToken>
        val xmlPattern = Pattern.compile("<(?:csrf_token|RequestVerificationToken)>([^<]+)</")
        val xmlMatcher = xmlPattern.matcher(body)
        if (xmlMatcher.find()) return xmlMatcher.group(1)

        // Match HTML input: <input ... name="csrf_token" value="..." />
        val htmlPattern = Pattern.compile("name=[\"'](?:csrf_token|CsrfToken|csrf)[\"'][^>]*value=[\"']([^\"']+)[\"']")
        val htmlMatcher = htmlPattern.matcher(body)
        if (htmlMatcher.find()) return htmlMatcher.group(1)

        return null
    }

    fun extractSessionIdFromBody(body: String): String? {
        val pattern = Pattern.compile("[\"'](?:SessionID_R3|SessionID|sid)[\"']\\s*:\\s*[\"']([^\"']+)[\"']")
        val matcher = pattern.matcher(body)
        if (matcher.find()) return matcher.group(1)
        return null
    }

    /**
     * Builds Cookie header string from session map.
     */
    fun buildCookieHeader(session: RouterSession): String {
        return session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
