package asia.guojuice.yezigotify.capabilities

enum class ServerType {
    GOTIFY,
    CHATZ,
    UNKNOWN
}

data class ServerCapabilities(
    val serverType: ServerType = ServerType.UNKNOWN,

    // ===== Chatz 扩展能力 =====
    val hasChannels: Boolean = false,
    val hasReadState: Boolean = false,
    val hasArchive: Boolean = false,
    val hasAggregation: Boolean = false,
    val hasTags: Boolean = false,
    val hasUnreadCounts: Boolean = false,
    val hasBackground: Boolean = false,
    val hasRoutes: Boolean = false,
    val hasDevices: Boolean = false,

    // ===== 元信息 =====
    val httpsPort: Int = 20443,
    val detectedAt: Long = 0L
) {
    val isChatz: Boolean get() = serverType == ServerType.CHATZ
    val isKnown: Boolean get() = serverType != ServerType.UNKNOWN

    companion object {
        fun forGotify(): ServerCapabilities = ServerCapabilities(
            serverType = ServerType.GOTIFY,
            detectedAt = System.currentTimeMillis()
        )

        fun forChatz(httpsPort: Int = 20443): ServerCapabilities = ServerCapabilities(
            serverType = ServerType.CHATZ,
            hasChannels = true,
            hasReadState = true,
            hasArchive = true,
            hasAggregation = true,
            hasTags = true,
            hasUnreadCounts = true,
            hasBackground = true,
            hasRoutes = true,
            hasDevices = true,
            httpsPort = httpsPort,
            detectedAt = System.currentTimeMillis()
        )
    }
}