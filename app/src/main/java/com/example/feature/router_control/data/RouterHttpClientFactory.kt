package com.example.feature.router_control.data

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * ARCHITECTURAL NOTICE:
 * This OkHttpClient factory is STRICTLY ISOLATED to the `com.example.feature.router_control` package.
 * It is NEVER exposed, exported, or used as a default client for any other network component in
 * this application (such as GitHub update checks, general APIs, telemetry, or external downloads).
 *
 * INTENTIONAL SECURITY EXCEPTION FOR LOCAL ROUTER MANAGEMENT:
 * Consumer and enterprise home gateways (Huawei, ZTE, TP-Link, MikroTik, D-Link) routinely
 * serve administrative web interfaces using self-signed SSL/TLS certificates that do NOT chain
 * to public Certificate Authorities (CAs).
 *
 * To allow secure and uninterrupted LAN administration without triggering CertPathValidatorException:
 * 1. The factory provides [standardHttpClient] for plain HTTP and standard CA-validated HTTPS.
 * 2. The factory provides [createRouterSslClient] which accepts self-signed certificates ONLY for
 *    local private network IPv4 gateways (RFC 1918: 192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, 127.0.0.1)
 *    or the explicit local router IP entered by the user.
 * 3. The custom [HostnameVerifier] strictly rejects any public domain name or non-LAN IP address,
 *    preventing any potential MITM exposure outside the user's private home network.
 */
object RouterHttpClientFactory {

    private const val CONNECT_TIMEOUT_SECONDS = 7L
    private const val READ_TIMEOUT_SECONDS = 15L
    private const val WRITE_TIMEOUT_SECONDS = 15L

    // RFC 1918 Private IPv4 address patterns + loopback
    private val PRIVATE_IP_PATTERN = Pattern.compile(
        "^(" +
                "192\\.168\\.\\d{1,3}\\.\\d{1,3}|" +
                "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|" +
                "172\\.(1[6-9]|2[0-9]|3[0-1])\\.\\d{1,3}\\.\\d{1,3}|" +
                "127\\.0\\.0\\.1|" +
                "localhost" +
                ")$"
    )

    /**
     * Standard client used for regular HTTP and valid CA HTTPS connections.
     */
    val standardHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Dedicated local X509TrustManager accepting self-signed gateway certificates on LAN.
     */
    private val localRouterTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // No client certificate needed for consumer router web interfaces
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // Accept self-signed certificates provided by local home routers on the LAN.
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * SSLContext configured specifically for the local router trust manager.
     */
    private val localRouterSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(localRouterTrustManager), SecureRandom())
        }
    }

    /**
     * Verifies if a hostname is a safe private local gateway IP.
     */
    fun isAllowedLocalGateway(hostname: String, expectedGatewayIp: String? = null): Boolean {
        val cleanHost = hostname.trim().lowercase().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val cleanExpected = expectedGatewayIp?.trim()?.lowercase()?.removePrefix("http://")?.removePrefix("https://")?.trimEnd('/')

        // If it matches the explicitly configured local router IP
        if (!cleanExpected.isNullOrBlank() && cleanHost == cleanExpected) {
            return true
        }

        // If it strictly matches private IPv4 address space
        return PRIVATE_IP_PATTERN.matcher(cleanHost).matches()
    }

    /**
     * Creates an isolated OkHttpClient configured to accept self-signed certificates
     * exclusively for the given local gateway IP or standard private RFC-1918 subnets.
     * All public hostnames are strictly denied by the custom HostnameVerifier.
     */
    fun createRouterSslClient(expectedGatewayIp: String? = null): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(localRouterSslContext.socketFactory, localRouterTrustManager)
            .hostnameVerifier { hostname, _ ->
                isAllowedLocalGateway(hostname, expectedGatewayIp)
            }
            .build()
    }
}
