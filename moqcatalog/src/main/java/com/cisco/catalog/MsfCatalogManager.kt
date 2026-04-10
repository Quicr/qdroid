package com.cisco.catalog

class MsfCatalogManager {
    private var currentCatalog: MsfCatalog? = null
    private val tracksMap = mutableMapOf<String, MsfTrack>()

    fun updateCatalog(jsonString: String): Result<CatalogUpdateResult> {
        val parseResult = MsfCatalogParser.parse(jsonString)

        if (parseResult.isFailure) {
            return Result.failure(parseResult.exceptionOrNull()!!)
        }

        val newCatalog = parseResult.getOrNull()!!

        val currentVersion = currentCatalog?.version
        val newVersion = newCatalog.version

        val shouldRefresh = shouldRefreshCatalog(currentVersion, newVersion)

        return if (newCatalog.isDeltaUpdate()) {
            processDeltaUpdate(newCatalog, shouldRefresh)
        } else {
            processFullCatalog(newCatalog, shouldRefresh)
        }
    }

    private fun shouldRefreshCatalog(currentVersion: Int?, newVersion: Int): Boolean {
        if (currentVersion == null) {
            return true
        }
        return newVersion != currentVersion
    }

    private fun processFullCatalog(catalog: MsfCatalog, shouldRefresh: Boolean): Result<CatalogUpdateResult> {
        return try {
            if (shouldRefresh) {
                tracksMap.clear()
            }

            catalog.getAllTracks().forEach { track ->
                val trackKey = getTrackKey(track)
                tracksMap[trackKey] = track
            }

            currentCatalog = catalog

            Result.success(
                CatalogUpdateResult(
                    refreshed = shouldRefresh,
                    isDelta = false,
                    tracksAdded = catalog.getAllTracks().size,
                    tracksRemoved = if (shouldRefresh) tracksMap.size - catalog.getAllTracks().size else 0,
                    totalTracks = tracksMap.size,
                    version = catalog.version,
                    isComplete = catalog.isCompleteCatalog()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun processDeltaUpdate(catalog: MsfCatalog, shouldRefresh: Boolean): Result<CatalogUpdateResult> {
        return try {
            if (shouldRefresh) {
                return Result.failure(IllegalStateException("Cannot apply delta update with version change"))
            }

            var addedCount = 0
            var removedCount = 0

            catalog.getTracksToAdd().forEach { track ->
                val trackKey = getTrackKey(track)
                tracksMap[trackKey] = track
                addedCount++
            }

            catalog.getTracksToRemove().forEach { track ->
                val trackKey = getTrackKey(track)
                if (tracksMap.remove(trackKey) != null) {
                    removedCount++
                }
            }

            catalog.getTracksToClone().forEach { track ->
                val parentKey = track.parentName?.let { parentName ->
                    val namespace = track.namespace ?: currentCatalog?.tracks?.firstOrNull()?.namespace
                    createTrackKey(namespace, parentName)
                }

                val parentTrack = parentKey?.let { tracksMap[it] }
                if (parentTrack != null) {
                    val clonedTrack = parentTrack.copy(
                        name = track.name,
                        namespace = track.namespace ?: parentTrack.namespace,
                    ).mergeWith(track)

                    val trackKey = getTrackKey(clonedTrack)
                    tracksMap[trackKey] = clonedTrack
                    addedCount++
                }
            }

            currentCatalog = catalog

            Result.success(
                CatalogUpdateResult(
                    refreshed = false,
                    isDelta = true,
                    tracksAdded = addedCount,
                    tracksRemoved = removedCount,
                    totalTracks = tracksMap.size,
                    version = catalog.version,
                    isComplete = catalog.isCompleteCatalog()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getTrackKey(track: MsfTrack): String {
        return createTrackKey(track.namespace, track.name)
    }

    private fun createTrackKey(namespace: String?, name: String): String {
        return if (namespace != null) {
            "$namespace/$name"
        } else {
            name
        }
    }

    fun getCurrentCatalog(): MsfCatalog? = currentCatalog

    fun getTracks(): List<MsfTrack> = tracksMap.values.toList()

    fun getTrack(name: String, namespace: String? = null): MsfTrack? {
        val key = createTrackKey(namespace, name)
        return tracksMap[key]
    }

    fun clear() {
        currentCatalog = null
        tracksMap.clear()
    }
}

data class CatalogUpdateResult(
    val refreshed: Boolean,
    val isDelta: Boolean,
    val tracksAdded: Int,
    val tracksRemoved: Int,
    val totalTracks: Int,
    val version: Int,
    val isComplete: Boolean
)

private fun MsfTrack.mergeWith(update: MsfTrack): MsfTrack {
    return MsfTrack(
        name = update.name,
        packaging = update.packaging,
        namespace = update.namespace ?: this.namespace,
        eventType = update.eventType ?: this.eventType,
        isLive = update.isLive ?: this.isLive,
        targetLatency = update.targetLatency ?: this.targetLatency,
        role = update.role ?: this.role,
        label = update.label ?: this.label,
        renderGroup = update.renderGroup ?: this.renderGroup,
        altGroup = update.altGroup ?: this.altGroup,
        initData = update.initData ?: this.initData,
        depends = update.depends ?: this.depends,
        template = update.template ?: this.template,
        temporalId = update.temporalId ?: this.temporalId,
        spatialId = update.spatialId ?: this.spatialId,
        codec = update.codec ?: this.codec,
        mimeType = update.mimeType ?: this.mimeType,
        framerate = update.framerate ?: this.framerate,
        timescale = update.timescale ?: this.timescale,
        bitrate = update.bitrate ?: this.bitrate,
        width = update.width ?: this.width,
        height = update.height ?: this.height,
        samplerate = update.samplerate ?: this.samplerate,
        channelConfig = update.channelConfig ?: this.channelConfig,
        displayWidth = update.displayWidth ?: this.displayWidth,
        displayHeight = update.displayHeight ?: this.displayHeight,
        lang = update.lang ?: this.lang,
        trackDuration = update.trackDuration ?: this.trackDuration,
        encryptionScheme = update.encryptionScheme ?: this.encryptionScheme,
        cipherSuite = update.cipherSuite ?: this.cipherSuite,
        keyId = update.keyId ?: this.keyId,
        trackBaseKey = update.trackBaseKey ?: this.trackBaseKey,
        parentName = update.parentName ?: this.parentName
    )
}
