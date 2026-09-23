package com.example

import com.example.domain.router.client.HuaweiHG630V2Client
import com.example.domain.router.model.RouterCredentials
import com.example.domain.router.model.RouterSession
import com.example.domain.router.session.RouterSessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HuaweiHG630V2ClientTest {

    private lateinit var client: HuaweiHG630V2Client

    @Before
    fun setup() {
        client = HuaweiHG630V2Client()
        RouterSessionManager.clearSession()
    }

    @Test
    fun testLocalLanIpValidation() {
        assertTrue(RouterSessionManager.isLocalLanIp("192.168.1.1"))
        assertTrue(RouterSessionManager.isLocalLanIp("192.168.0.1"))
        assertTrue(RouterSessionManager.isLocalLanIp("10.0.0.1"))
        assertTrue(RouterSessionManager.isLocalLanIp("172.16.0.1"))
        assertTrue(RouterSessionManager.isLocalLanIp("127.0.0.1"))

        // Public IPs / Malicious endpoints must be strictly rejected
        assertFalse(RouterSessionManager.isLocalLanIp("8.8.8.8"))
        assertFalse(RouterSessionManager.isLocalLanIp("1.1.1.1"))
        assertFalse(RouterSessionManager.isLocalLanIp("203.0.113.195"))
    }

    @Test
    fun testCsrfExtractionFromBody() {
        val jsonBody = """{"error":0,"csrf_param":"token_abc_123","csrf_token":"token_abc_123"}"""
        assertEquals("token_abc_123", RouterSessionManager.extractCsrfFromBody(jsonBody))

        val xmlBody = """<response><csrf_token>token_xml_999</csrf_token></response>"""
        assertEquals("token_xml_999", RouterSessionManager.extractCsrfFromBody(xmlBody))

        val htmlBody = """<html><input type="hidden" name="csrf_token" value="token_html_456"/></html>"""
        assertEquals("token_html_456", RouterSessionManager.extractCsrfFromBody(htmlBody))
    }

    @Test
    fun testDeviceInfoParsing() {
        val jsonPayload = """
            {
                "ModelName": "HG630 V2",
                "SoftwareVersion": "V100R001C192B020",
                "HardwareVersion": "VER.A",
                "SerialNumber": "21530327667SZ4001234",
                "UpTime": 172800,
                "DeviceName": "HomeGateway"
            }
        """.trimIndent()

        val info = client.parseDeviceInfo(jsonPayload)
        assertEquals("HG630 V2", info.model)
        assertEquals("V100R001C192B020", info.firmwareVersion)
        assertEquals("VER.A", info.hardwareVersion)
        assertEquals("21530327667SZ4001234", info.serialNumber)
        assertEquals(172800L, info.uptimeSeconds)
        assertTrue(info.formattedUptime.contains("2d"))
    }

    @Test
    fun testWanInfoParsing() {
        val jsonWan = """
            {
                "ConnectionStatus": "Connected",
                "ConnectionType": "PPPoE_Routed",
                "ExternalIPAddress": "197.35.40.12",
                "SubnetMask": "255.255.255.255",
                "DefaultGateway": "197.35.40.1",
                "DNSServers": "163.121.128.134,163.121.128.135",
                "MACAddress": "F8:E7:1E:A1:B2:C3"
            }
        """.trimIndent()

        val wan = client.parseWanInfo(jsonWan)
        assertEquals("Connected", wan.status)
        assertEquals("197.35.40.12", wan.ip)
        assertEquals("163.121.128.134", wan.primaryDns)
        assertEquals("163.121.128.135", wan.secondaryDns)
        assertEquals("F8:E7:1E:A1:B2:C3", wan.mac)
    }

    @Test
    fun testWifiInfoParsing() {
        val jsonWifi = """
            {
                "WlanEnable": 1,
                "SSID": "WE_Home_5GHz",
                "Channel": "6",
                "BeaconType": "WPA2-PSK",
                "Standard": "802.11b/g/n"
            }
        """.trimIndent()

        val wifi = client.parseWifiInfo(jsonWifi)
        assertTrue(wifi.enabled)
        assertEquals("WE_Home_5GHz", wifi.ssid)
        assertEquals("6", wifi.channel)
        assertEquals("WPA2-PSK", wifi.securityMode)
    }

    @Test
    fun testConnectedDevicesParsing() {
        val jsonDevices = """
            {
                "Host": [
                    {
                        "HostName": "iPhone-15-Pro",
                        "IPAddress": "192.168.1.15",
                        "MACAddress": "74:8D:08:11:22:33",
                        "InterfaceType": "802.11b/g/n",
                        "Active": 1,
                        "LeaseTimeRemaining": "84200"
                    },
                    {
                        "HostName": "Smart-TV-LivingRoom",
                        "IPAddress": "192.168.1.20",
                        "MACAddress": "B8:27:EB:44:55:66",
                        "InterfaceType": "Ethernet",
                        "Active": 1,
                        "LeaseTimeRemaining": "86000"
                    }
                ]
            }
        """.trimIndent()

        val devices = client.parseConnectedDevices(jsonDevices)
        assertEquals(2, devices.size)

        val phone = devices[0]
        assertEquals("iPhone-15-Pro", phone.name)
        assertEquals("192.168.1.15", phone.ip)
        assertEquals("74:8D:08:11:22:33", phone.mac)
        assertEquals("Wi-Fi (2.4 GHz)", phone.connectionType)
        assertTrue(phone.isOnline)

        val tv = devices[1]
        assertEquals("Smart-TV-LivingRoom", tv.name)
        assertEquals("192.168.1.20", tv.ip)
        assertEquals("Ethernet (LAN)", tv.connectionType)
    }

    @Test
    fun testMissingFieldsFallbackToNotAvailable() {
        val emptyJson = "{}"
        val info = client.parseDeviceInfo(emptyJson)
        val wan = client.parseWanInfo(emptyJson)

        assertEquals("Not available", info.firmwareVersion)
        assertEquals("Not available", info.serialNumber)
        assertEquals("Not available", wan.primaryDns)
        assertEquals("Not available", wan.secondaryDns)
    }

    @Test
    fun testSessionExpirationCheck() {
        val expiredSession = RouterSession(
            gatewayIp = "192.168.1.1",
            createdAt = System.currentTimeMillis() - 2000,
            expiresAt = System.currentTimeMillis() - 1000
        )
        assertTrue(expiredSession.isExpired)

        val validSession = RouterSession(
            gatewayIp = "192.168.1.1",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60000
        )
        assertFalse(validSession.isExpired)
    }

    @Test
    fun testSecurityRejectsExternalIpOnLogin() = runBlocking {
        val result = client.login("8.8.8.8", RouterCredentials("admin", "admin", "8.8.8.8"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
