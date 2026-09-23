package com.example.domain.router.client

object RouterClientFactory {

    private val huaweiHG630V2Client = HuaweiHG630V2Client()
    private val genericUpnpClient = GenericUpnpRouterClient()

    fun getClientForGateway(isHuawei: Boolean = true): RouterClient {
        return if (isHuawei) {
            huaweiHG630V2Client
        } else {
            genericUpnpClient
        }
    }

    fun getHuaweiClient(): HuaweiHG630V2Client = huaweiHG630V2Client
}
