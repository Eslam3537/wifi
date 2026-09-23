package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.compatibility.CompatibilityScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompatibilityScannerTest {

    @Test
    fun testAuditDeviceReturnsValidStructure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = CompatibilityScanner.auditDevice(context)

        assertNotNull(result)
        assertNotNull(result.targetDevice)
        assertNotNull(result.osVersion)
        assertTrue(result.apiLevel > 0)
    }
}
