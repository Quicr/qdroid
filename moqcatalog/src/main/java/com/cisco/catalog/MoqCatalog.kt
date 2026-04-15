package com.cisco.catalog

/**
 * Main facade class for working with MSF (MOQT Streaming Format) catalogs.
 *
 * This class provides a convenient interface to parse, manage, and query MSF catalogs
 * as defined in the IETF MOQT Streaming Format specification.
 *
 * Features:
 * - Lenient JSON parsing that ignores unknown fields
 * - Version-based catalog refresh logic
 * - Support for delta updates
 * - Track management and querying
 *
 * Usage example:
 * ```
 * val moqCatalog = MoqCatalog()
 *
 * // Parse and update catalog
 * val result = moqCatalog.updateCatalog(jsonString)
 * if (result.isSuccess) {
 *     val updateResult = result.getOrNull()
 *     println("Catalog updated: ${updateResult?.totalTracks} tracks")
 * }
 *
 * // Query tracks
 * val tracks = moqCatalog.getTracks()
 * val videoTrack = moqCatalog.getTrack("video")
 * ```
 */
class MoqCatalog {
    private val catalogManager = MsfCatalogManager()
    private val publisherId: String = "0XCA1A109" // Publisher ID for catalog subscription

    // Build catalog track name in safe form: cisco.2ewebex.2ecom-nab-v1-publisher_<id>--catalog
    val catalogTrackSafeForm =
        "cisco.2ewebex.2ecom-nab-v1-catalog-publisher_${publisherId}--catalog"

    // Convert from safe form to URL format
    val catalogTrackUrl = MoqNameUtils.safeFormToUrl(catalogTrackSafeForm)
    /**
     * Updates the catalog with new JSON data.
     *
     * This method will:
     * - Parse the JSON string leniently (ignoring unknown fields)
     * - Check if the version has changed and trigger a refresh if needed
     * - Apply delta updates if the catalog is a delta update
     * - Apply full catalog updates otherwise
     *
     * @param jsonString The JSON string representing the catalog
     * @return Result containing CatalogUpdateResult on success, or an exception on failure
     */
    fun updateCatalog(jsonString: String): Result<CatalogUpdateResult> {
        return catalogManager.updateCatalog(jsonString)
    }

    /**
     * Gets the current catalog.
     *
     * @return The current MsfCatalog, or null if no catalog has been loaded
     */
    fun getCurrentCatalog(): MsfCatalog? {
        return catalogManager.getCurrentCatalog()
    }

    /**
     * Gets all tracks in the catalog.
     *
     * @return List of all MsfTrack objects
     */
    fun getTracks(): List<MsfTrack> {
        return catalogManager.getTracks()
    }

    /**
     * Gets a specific track by name and optional namespace.
     *
     * @param name The track name
     * @param namespace Optional namespace for the track
     * @return The MsfTrack if found, null otherwise
     */
    fun getTrack(name: String, namespace: String? = null): MsfTrack? {
        return catalogManager.getTrack(name, namespace)
    }

    /**
     * Clears the current catalog and all tracks.
     */
    fun clear() {
        catalogManager.clear()
    }

    companion object {
        /**
         * Parses a JSON string into an MsfCatalog without managing state.
         *
         * This is a utility method for one-off parsing without tracking versions
         * or managing catalog state.
         *
         * @param jsonString The JSON string to parse
         * @return Result containing MsfCatalog on success, or an exception on failure
         */
        fun parse(jsonString: String): Result<MsfCatalog> {
            return MsfCatalogParser.parse(jsonString)
        }

        /**
         * Serializes an MsfCatalog to JSON string.
         *
         * @param catalog The catalog to serialize
         * @return Result containing JSON string on success, or an exception on failure
         */
        fun serialize(catalog: MsfCatalog): Result<String> {
            return MsfCatalogParser.serialize(catalog)
        }
    }
}