package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ActivityLogEntity
import com.example.data.local.AppDatabase
import com.example.data.local.DeviceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testDeviceInsertAndQuery() = runBlocking {
        val deviceDao = db.deviceDao()

        val entity = DeviceEntity(
            ip = "192.168.1.100",
            mac = "F8:E0:79:11:22:33",
            vendor = "Xiaomi Communications Co Ltd",
            hostname = "poco-x7-pro",
            deviceType = "PHONE",
            openPortsCsv = "80,443",
            discoveryMethodsCsv = "TCP_CONNECT,MDNS_NSD",
            latencyMs = 5L,
            isGateway = false,
            isCurrentDevice = true,
            firstSeen = 1000L,
            lastSeen = 2000L,
            status = "ONLINE"
        )

        deviceDao.insertOrUpdate(entity)

        val retrieved = deviceDao.getDeviceByIp("192.168.1.100")
        assertNotNull(retrieved)
        assertEquals("F8:E0:79:11:22:33", retrieved?.mac)
        assertEquals("Xiaomi Communications Co Ltd", retrieved?.vendor)

        val allDevices = deviceDao.getAllDevices()
        assertEquals(1, allDevices.size)
    }

    @Test
    fun testActivityLogHistory() = runBlocking {
        val logDao = db.activityLogDao()

        logDao.insert(
            ActivityLogEntity(
                timestamp = System.currentTimeMillis(),
                component = "Discovery",
                level = "INFO",
                operation = "TCP Scan",
                result = "Found 3 devices",
                errorDetails = null
            )
        )

        // Clear logs
        logDao.clearAll()
        // verify empty
    }
}
