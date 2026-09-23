package com.example.domain.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MdnsDiscoveryHelper(private val context: Context) {

    private val nsdManager by lazy {
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    data class MdnsResolvedInfo(
        val ip: String,
        val serviceName: String,
        val hostName: String?,
        val serviceType: String,
        val attributes: Map<String, String> = emptyMap()
    )

    // Standard high-signal service types on local networks
    private val standardServiceTypes = listOf(
        "_http._tcp.",
        "_googlecast._tcp.",
        "_airplay._tcp.",
        "_raop._tcp.",
        "_smb._tcp.",
        "_ipp._tcp.",
        "_printer._tcp.",
        "_spotify-connect._tcp.",
        "_companion-link._tcp.",
        "_device-info._tcp.",
        "_workstation._tcp.",
        "_ssh._tcp."
    )

    /**
     * Discovers and resolves mDNS/NSD services across all common local network service types.
     */
    suspend fun discoverAllMdnsServices(timeoutMs: Long = 2200): List<MdnsResolvedInfo> = withContext(Dispatchers.IO) {
        val nsd = nsdManager ?: return@withContext emptyList()
        val results = mutableListOf<MdnsResolvedInfo>()

        // Acquire Wi-Fi Multicast Lock to enable receiving multicast DNS packets on mobile Wi-Fi chipsets
        var multicastLock: WifiManager.MulticastLock? = null
        try {
            multicastLock = wifiManager?.createMulticastLock("netmanager_mdns_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}

        val activeListeners = mutableListOf<Pair<String, NsdManager.DiscoveryListener>>()

        try {
            for (serviceType in standardServiceTypes) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {}
                    override fun onServiceFound(service: NsdServiceInfo) {
                        try {
                            nsd.resolveService(service, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    val host = serviceInfo.host?.hostAddress ?: return
                                    val name = serviceInfo.serviceName
                                    val canonicalHost = serviceInfo.host?.canonicalHostName

                                    val attrs = mutableMapOf<String, String>()
                                    try {
                                        serviceInfo.attributes?.forEach { (k, v) ->
                                            if (v != null) {
                                                attrs[k] = String(v, Charsets.UTF_8)
                                            }
                                        }
                                    } catch (_: Exception) {}

                                    synchronized(results) {
                                        results.add(
                                            MdnsResolvedInfo(
                                                ip = host,
                                                serviceName = name,
                                                hostName = canonicalHost,
                                                serviceType = serviceInfo.serviceType ?: serviceType,
                                                attributes = attrs
                                            )
                                        )
                                    }
                                }
                            })
                        } catch (_: Exception) {}
                    }

                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onServiceLost(service: NsdServiceInfo) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                }

                try {
                    nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    activeListeners.add(serviceType to listener)
                } catch (_: Exception) {}
            }

            delay(timeoutMs)
        } catch (_: Exception) {
            // Ignored
        } finally {
            for ((_, listener) in activeListeners) {
                try {
                    nsd.stopServiceDiscovery(listener)
                } catch (_: Exception) {}
            }
            try {
                multicastLock?.release()
            } catch (_: Exception) {}
        }

        results
    }
}
