package com.example.feature.router_control.strategy

import com.example.feature.router_control.model.RouterVendor

object RouterStrategyRegistry {

    /**
     * List of registered router vendor strategies.
     * Order matters: specific vendor strategies take precedence over GenericRouterStrategy.
     *
     * TODO: Add more router vendor strategies here (e.g. D-Link, Netgear, Tenda, Cisco, Technicolor, AVM Fritz!Box)
     * by creating an implementation of RouterStrategy and registering it in this list.
     */
    private val strategies: List<RouterStrategy> = listOf(
        TpLinkRouterStrategy(),
        HuaweiRouterStrategy(),
        ZteRouterStrategy(),
        MikrotikRouterStrategy(),
        // TODO: DLinkRouterStrategy(),
        // TODO: NetgearRouterStrategy(),
        // TODO: TendaRouterStrategy(),
        // TODO: CiscoRouterStrategy(),
        GenericRouterStrategy() // Universal fallback at the end
    )

    /**
     * Finds the matching strategy based on HTML content and response headers.
     */
    fun findStrategy(
        html: String,
        headers: Map<String, List<String>>,
        serverHeader: String = ""
    ): RouterStrategy {
        for (strategy in strategies) {
            if (strategy !is GenericRouterStrategy && strategy.matches(html, headers, serverHeader)) {
                return strategy
            }
        }
        return strategies.last { it is GenericRouterStrategy }
    }

    /**
     * Finds strategy by explicit vendor selection.
     */
    fun getStrategyByVendor(vendor: RouterVendor): RouterStrategy {
        return strategies.firstOrNull { it.vendor == vendor }
            ?: strategies.last { it is GenericRouterStrategy }
    }
}
