package com.example

import com.example.feature.router_control.model.RouterConnectedDevice
import com.example.feature.router_control.model.RouterCredentials
import com.example.feature.router_control.model.RouterStatusInfo
import com.example.feature.router_control.model.RouterUiState
import com.example.feature.router_control.model.RouterVendor
import com.example.feature.router_control.strategy.HuaweiRouterStrategy
import com.example.feature.router_control.strategy.RouterStrategyRegistry
import com.example.feature.router_control.strategy.TpLinkRouterStrategy
import com.example.feature.router_control.strategy.ZteRouterStrategy
import org.junit.Assert.*
import org.junit.Test

class RouterControlFeatureTest {

    @Test
    fun testTpLinkVendorDetection() {
        val tpLinkHtml = "<html><head><title>TP-LINK Wireless Router WR840N</title></head><body><h1>TP-Link</h1></body></html>"
        val strategy = RouterStrategyRegistry.findStrategy(tpLinkHtml, emptyMap(), "TP-LINK Web Server")
        assertEquals(RouterVendor.TP_LINK, strategy.vendor)
    }

    @Test
    fun testHuaweiVendorDetection() {
        val huaweiHtml = "<html><head><title>Huawei Home Gateway HG630</title></head><body>EchoLife</body></html>"
        val strategy = RouterStrategyRegistry.findStrategy(huaweiHtml, emptyMap(), "HuaweiHomeGateway")
        assertEquals(RouterVendor.HUAWEI, strategy.vendor)
    }

    @Test
    fun testZteVendorDetection() {
        val zteHtml = "<html><head><title>ZXHN H168N</title></head><body><script src='getpage.gch'></script></body></html>"
        val strategy = RouterStrategyRegistry.findStrategy(zteHtml, emptyMap(), "ZTE-Webs")
        assertEquals(RouterVendor.ZTE, strategy.vendor)
    }

    @Test
    fun testGenericFallbackWhenUnknownVendor() {
        val unknownHtml = "<html><head><title>Unknown Mini Router</title></head><body>Welcome</body></html>"
        val strategy = RouterStrategyRegistry.findStrategy(unknownHtml, emptyMap(), "Simple-Server")
        assertEquals(RouterVendor.GENERIC, strategy.vendor)
    }

    @Test
    fun testRouterCredentialsDefaults() {
        val creds = RouterCredentials()
        assertEquals("192.168.1.1", creds.gatewayIp)
        assertEquals("admin", creds.username)
        assertEquals("", creds.password)
        assertTrue(creds.remember)
    }

    @Test
    fun testRouterUiStateHierarchy() {
        val idle = RouterUiState.Idle
        assertNotNull(idle)

        val loading = RouterUiState.Loading("Checking...")
        assertEquals("Checking...", loading.message)

        val err = RouterUiState.Error("Wi-Fi not connected", isWifiDisconnected = true)
        assertTrue(err.isWifiDisconnected)

        val connected = RouterUiState.Connected(
            status = RouterStatusInfo(
                isConnected = true,
                vendor = RouterVendor.TP_LINK,
                modelName = "WR840N",
                gatewayIp = "192.168.1.1",
                wifiSsid = "MyNet",
                wifiEnabled = true,
                connectedDevicesCount = 1
            ),
            devices = listOf(
                RouterConnectedDevice(
                    ip = "192.168.1.100",
                    mac = "AA:BB:CC:DD:EE:FF",
                    hostname = "Android Phone",
                    isBlocked = false,
                    connectionType = "Wi-Fi"
                )
            )
        )
        assertEquals(1, connected.devices.size)
        assertEquals("MyNet", connected.status.wifiSsid)
    }
}
