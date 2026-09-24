package com.example.domain.router.client

import com.example.domain.router.model.DetectedRouter
import com.example.domain.router.model.RouterManufacturer

object RouterClientFactory {

    private val huaweiHG630V2Client = HuaweiHG630V2Client()
    private val genericUpnpClient = GenericUpnpRouterClient()

    fun createClient(detectedRouter: DetectedRouter): RouterClient {
        return when (detectedRouter.manufacturer) {
            RouterManufacturer.HUAWEI -> huaweiHG630V2Client
            else -> if (detectedRouter.modelName.contains("HG630", ignoreCase = true) ||
                detectedRouter.modelName.contains("Huawei", ignoreCase = true)) {
                huaweiHG630V2Client
            } else {
                genericUpnpClient
            }
        }
    }

    fun getClientForGateway(isHuawei: Boolean = true): RouterClient {
        return if (isHuawei) huaweiHG630V2Client else genericUpnpClient
    }

    fun getHuaweiClient(): HuaweiHG630V2Client = huaweiHG630V2Client
}
