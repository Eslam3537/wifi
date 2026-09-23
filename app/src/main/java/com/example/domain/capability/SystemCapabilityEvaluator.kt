package com.example.domain.capability

import android.content.Context
import com.example.domain.discovery.NetworkDiscoveryEngine
import com.example.model.RouterCapability
import com.example.model.SystemCapabilityReport

object SystemCapabilityEvaluator {

    suspend fun evaluate(
        context: Context,
        routerCapability: RouterCapability?,
        networkInfo: NetworkDiscoveryEngine.NetworkInterfaceInfo?
    ): SystemCapabilityReport {
        return CapabilityEngine.auditAllCapabilities(context, routerCapability, networkInfo).systemReport
    }
}
