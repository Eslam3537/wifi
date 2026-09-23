package com.example.domain.discovery

enum class NeighborState {
    REACHABLE,
    STALE,
    DELAY,
    PROBE,
    FAILED,
    INCOMPLETE,
    NOARP,
    PERMANENT,
    UNKNOWN;

    val isCurrentActive: Boolean
        get() = this == REACHABLE || this == DELAY || this == PROBE || this == PERMANENT || this == NOARP || this == STALE

    val isStrictlyFresh: Boolean
        get() = this == REACHABLE || this == DELAY || this == PROBE || this == PERMANENT || this == NOARP
}

data class NeighborEntry(
    val ip: String,
    val mac: String?,
    val state: NeighborState,
    val interfaceName: String?,
    val isFreshActive: Boolean
)

object NeighborTableParser {

    /**
     * Parses standard Linux `ip neigh show` command output.
     * Accurately extracts IP, MAC, network interface, and kernel neighbor state.
     */
    fun parseIpNeighOutput(output: String): List<NeighborEntry> {
        val result = mutableListOf<NeighborEntry>()
        val lines = output.lines()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) continue

            val tokens = line.split(Regex("\\s+"))
            val ip = tokens.getOrNull(0) ?: continue
            if (!NetworkUtils.isValidIpv4(ip)) continue

            val devIndex = tokens.indexOf("dev")
            val iface = if (devIndex != -1 && devIndex + 1 < tokens.size) tokens[devIndex + 1] else null

            val lladdrIndex = tokens.indexOf("lladdr")
            val mac = if (lladdrIndex != -1 && lladdrIndex + 1 < tokens.size) {
                val candidateMac = tokens[lladdrIndex + 1].trim().uppercase()
                if (candidateMac.contains(":") && candidateMac != "00:00:00:00:00:00") candidateMac else null
            } else null

            // Determine state from terminal tokens
            val state = when {
                tokens.any { it.equals("REACHABLE", ignoreCase = true) } -> NeighborState.REACHABLE
                tokens.any { it.equals("STALE", ignoreCase = true) } -> NeighborState.STALE
                tokens.any { it.equals("DELAY", ignoreCase = true) } -> NeighborState.DELAY
                tokens.any { it.equals("PROBE", ignoreCase = true) } -> NeighborState.PROBE
                tokens.any { it.equals("FAILED", ignoreCase = true) } -> NeighborState.FAILED
                tokens.any { it.equals("INCOMPLETE", ignoreCase = true) } -> NeighborState.INCOMPLETE
                tokens.any { it.equals("NOARP", ignoreCase = true) } -> NeighborState.NOARP
                tokens.any { it.equals("PERMANENT", ignoreCase = true) } -> NeighborState.PERMANENT
                else -> NeighborState.UNKNOWN
            }

            result.add(
                NeighborEntry(
                    ip = ip,
                    mac = mac,
                    state = state,
                    interfaceName = iface,
                    isFreshActive = state.isCurrentActive && mac != null
                )
            )
        }

        return result
    }

    /**
     * Parses `/proc/net/arp` table (older Android or fallback).
     * Flag 0x2 = ATF_COM (completed), 0x0 = incomplete.
     */
    fun parseProcNetArp(lines: List<String>): List<NeighborEntry> {
        val result = mutableListOf<NeighborEntry>()
        for (line in lines.drop(1)) {
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size >= 6) {
                val ip = tokens[0]
                val flags = tokens[2]
                val mac = tokens[3].uppercase()
                val dev = tokens[5]

                if (NetworkUtils.isValidIpv4(ip) && mac != "00:00:00:00:00:00" && mac.contains(":")) {
                    val isComplete = flags == "0x2" || flags == "2"
                    result.add(
                        NeighborEntry(
                            ip = ip,
                            mac = mac,
                            state = if (isComplete) NeighborState.STALE else NeighborState.INCOMPLETE,
                            interfaceName = dev,
                            isFreshActive = isComplete
                        )
                    )
                }
            }
        }
        return result
    }
}
