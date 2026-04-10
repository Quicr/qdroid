package com.cisco.catalog

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MoqNameUtilsTest {

    @Test
    fun testSpecificationExample() {
        // Test the exact example from the specification
        // example.2enet-team2-project_x--report
        // Namespace tuples: (example.net, team2, project_x)
        // Track name: report

        val safeForm = MoqNameUtils.createSafeForm(
            "example.net", "team2", "project_x",
            trackName = "report"
        )

        assertEquals("example.2enet-team2-project_x--report", safeForm)

        // Parse it back
        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(3, namespaces.size)
        assertEquals("example.net", namespaces[0])
        assertEquals("team2", namespaces[1])
        assertEquals("project_x", namespaces[2])
        assertEquals("report", trackName)
    }

    @Test
    fun testBasicEncoding() {
        // Test that allowed characters (a-z, A-Z, 0-9, _) are not encoded
        val safeForm = MoqNameUtils.createSafeForm(
            "abc", "XYZ", "123", "test_name",
            trackName = "track_1"
        )

        assertEquals("abc-XYZ-123-test_name--track_1", safeForm)

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(4, namespaces.size)
        assertEquals("abc", namespaces[0])
        assertEquals("XYZ", namespaces[1])
        assertEquals("123", namespaces[2])
        assertEquals("test_name", namespaces[3])
        assertEquals("track_1", trackName)
    }

    @Test
    fun testSpecialCharacterEncoding() {
        // Test that special characters are encoded as .XX
        // Period (.) is 0x2e
        // Space is 0x20
        // At (@) is 0x40
        // Slash (/) is 0x2f

        val tuples = listOf(
            "example.net".toByteArray(),
            "hello world".toByteArray(),
            "test@example".toByteArray(),
            "path/to".toByteArray()
        )
        val trackName = "report!".toByteArray()

        val safeForm = MoqNameUtils.toSafeForm(tuples, trackName)

        // Verify encoding:
        // . (0x2e) -> .2e
        // space (0x20) -> .20
        // @ (0x40) -> .40
        // / (0x2f) -> .2f
        // ! (0x21) -> .21
        assertTrue(safeForm.contains(".2e"))  // period
        assertTrue(safeForm.contains(".20"))  // space
        assertTrue(safeForm.contains(".40"))  // @
        assertTrue(safeForm.contains(".2f"))  // /
        assertTrue(safeForm.contains(".21"))  // !

        // Verify round-trip
        val (parsedTuples, parsedTrackName) = MoqNameUtils.fromSafeForm(safeForm)
        assertEquals(4, parsedTuples.size)
        assertEquals("example.net", String(parsedTuples[0]))
        assertEquals("hello world", String(parsedTuples[1]))
        assertEquals("test@example", String(parsedTuples[2]))
        assertEquals("path/to", String(parsedTuples[3]))
        assertEquals("report!", String(parsedTrackName))
    }

    @Test
    fun testEmptyNamespace() {
        // Track name only, no namespace
        val safeForm = MoqNameUtils.createSafeForm(trackName = "track1")

        assertEquals("track1", safeForm)

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(0, namespaces.size)
        assertEquals("track1", trackName)
    }

    @Test
    fun testSingleNamespace() {
        // Single namespace tuple
        val safeForm = MoqNameUtils.createSafeForm("namespace1", trackName = "track1")

        assertEquals("namespace1--track1", safeForm)

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(1, namespaces.size)
        assertEquals("namespace1", namespaces[0])
        assertEquals("track1", trackName)
    }

    @Test
    fun testMultipleNamespaces() {
        // Multiple namespace tuples
        val safeForm = MoqNameUtils.createSafeForm(
            "ns1", "ns2", "ns3",
            trackName = "track1"
        )

        assertEquals("ns1-ns2-ns3--track1", safeForm)

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(3, namespaces.size)
        assertEquals("ns1", namespaces[0])
        assertEquals("ns2", namespaces[1])
        assertEquals("ns3", namespaces[2])
        assertEquals("track1", trackName)
    }

    @Test
    fun testHyphenInName() {
        // Test that hyphens in names are encoded (0x2d)
        val safeForm = MoqNameUtils.createSafeForm(
            "name-with-hyphens",
            trackName = "track-1"
        )

        // Hyphens (0x2d) should be encoded as .2d to avoid confusion with separators
        assertTrue(safeForm.contains(".2d"))

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(1, namespaces.size)
        assertEquals("name-with-hyphens", namespaces[0])
        assertEquals("track-1", trackName)
    }

    @Test
    fun testDoubleHyphenInName() {
        // Test that double hyphens in names are properly encoded
        val safeForm = MoqNameUtils.createSafeForm(
            "name--with--double",
            trackName = "track"
        )

        // Double hyphens should be encoded to avoid confusion with namespace-track separator
        assertTrue(safeForm.contains(".2d.2d"))

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(1, namespaces.size)
        assertEquals("name--with--double", namespaces[0])
        assertEquals("track", trackName)
    }

    @Test
    fun testUtf8Characters() {
        // Test with UTF-8 multi-byte characters
        val safeForm = MoqNameUtils.createSafeForm(
            "hello", "世界",
            trackName = "emoji😀"
        )

        // UTF-8 characters should be encoded as multiple .XX sequences
        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(2, namespaces.size)
        assertEquals("hello", namespaces[0])
        assertEquals("世界", namespaces[1])
        assertEquals("emoji😀", trackName)
    }

    @Test
    fun testRoundTripConversion() {
        // Test round-trip conversion with various characters
        val original = listOf(
            "example.com".toByteArray(),
            "conference_2024".toByteArray(),
            "video-hd".toByteArray()
        )
        val originalTrack = "camera_1".toByteArray()

        val safeForm = MoqNameUtils.toSafeForm(original, originalTrack)
        val (parsed, parsedTrack) = MoqNameUtils.fromSafeForm(safeForm)

        assertEquals(original.size, parsed.size)
        for (i in original.indices) {
            assertEquals(String(original[i]), String(parsed[i]))
        }
        assertEquals(String(originalTrack), String(parsedTrack))
    }

    @Test
    fun testSafeFormToUrl() {
        val safeForm = "example.2enet-team2-project_x--report"

        val urlForm = MoqNameUtils.safeFormToUrl(safeForm)

        // URL form should use forward slashes as separators
        // Expected: example.net/team2/project_x/report
        assertEquals("example.net/team2/project_x/report", urlForm)

        // Round-trip back to safe form
        val backToSafe = MoqNameUtils.urlToSafeForm(urlForm)
        assertEquals(safeForm, backToSafe)
    }

    @Test
    fun testCatalogTrackConversion() {
        // Test the specific example from the catalog track subscription
        val safeForm = "cisco.2ewebex.2ecom-nab-v1-publisher_211919113--catalog"

        val urlForm = MoqNameUtils.safeFormToUrl(safeForm)

        // Expected: cisco.webex.com/nab/v1/publisher_211919113/catalog
        assertEquals("cisco.webex.com/nab/v1/publisher_211919113/catalog", urlForm)

        // Verify round-trip
        val backToSafe = MoqNameUtils.urlToSafeForm(urlForm)
        assertEquals(safeForm, backToSafe)

        // Verify parsed components
        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(4, namespaces.size)
        assertEquals("cisco.webex.com", namespaces[0])
        assertEquals("nab", namespaces[1])
        assertEquals("v1", namespaces[2])
        assertEquals("publisher_211919113", namespaces[3])
        assertEquals("catalog", trackName)
    }

    @Test
    fun testUrlToSafeForm() {
        // Test converting URL format to safe form
        val urlForm = "example.net/team2/report"

        val safeForm = MoqNameUtils.urlToSafeForm(urlForm)

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(2, namespaces.size)
        assertEquals("example.net", namespaces[0])
        assertEquals("team2", namespaces[1])
        assertEquals("report", trackName)
    }

    @Test
    fun testUrlFormatWithSpecialChars() {
        // Test URL format conversion with special characters
        val original = MoqNameUtils.createSafeForm(
            "hello world", "test@example",
            trackName = "resource"
        )

        val urlForm = MoqNameUtils.safeFormToUrl(original)
        val backToSafe = MoqNameUtils.urlToSafeForm(urlForm)

        assertEquals(original, backToSafe)

        // Verify the data is correct
        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(backToSafe)
        assertEquals(2, namespaces.size)
        assertEquals("hello world", namespaces[0])
        assertEquals("test@example", namespaces[1])
        assertEquals("resource", trackName)
    }

    @Test
    fun testIsValidSafeForm() {
        // Valid safe forms
        assertTrue(MoqNameUtils.isValidSafeForm("track1"))
        assertTrue(MoqNameUtils.isValidSafeForm("ns1--track1"))
        assertTrue(MoqNameUtils.isValidSafeForm("ns1-ns2--track1"))
        assertTrue(MoqNameUtils.isValidSafeForm("example.2enet-team2-project_x--report"))
        assertTrue(MoqNameUtils.isValidSafeForm("ns1-.2e--track"))  // Valid: ns1 and encoded period

        // Invalid safe forms
        assertFalse(MoqNameUtils.isValidSafeForm(""))
        assertFalse(MoqNameUtils.isValidSafeForm("ns1--"))  // Empty track name
        assertFalse(MoqNameUtils.isValidSafeForm("ns1-test@--track"))  // @ is not allowed unencoded
    }

    @Test
    fun testInvalidSafeForm_Empty() {
        assertFailsWith<IllegalArgumentException> {
            MoqNameUtils.fromSafeForm("")
        }
    }

    @Test
    fun testInvalidSafeForm_EmptyTrackName() {
        assertFailsWith<IllegalArgumentException> {
            MoqNameUtils.fromSafeForm("namespace--")
        }
    }

    @Test
    fun testInvalidSafeForm_IncompleteHexSequence() {
        assertFailsWith<IllegalArgumentException> {
            MoqNameUtils.fromSafeForm("test.2--track")
        }
    }

    @Test
    fun testInvalidSafeForm_InvalidHexCharacters() {
        assertFailsWith<IllegalArgumentException> {
            MoqNameUtils.fromSafeForm("test.GG--track")
        }
    }

    @Test
    fun testInvalidSafeForm_DisallowedCharacter() {
        // Characters not in a-z, A-Z, 0-9, _ should be encoded
        assertFailsWith<IllegalArgumentException> {
            MoqNameUtils.fromSafeForm("test@example--track")
        }
    }

    @Test
    fun testBinaryInterface() {
        // Test the binary (ByteArray) interface directly
        val namespaceTuples = listOf(
            byteArrayOf(0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65, 0x2e, 0x6e, 0x65, 0x74), // "example.net"
            byteArrayOf(0x74, 0x65, 0x61, 0x6d, 0x32), // "team2"
            byteArrayOf(0x70, 0x72, 0x6f, 0x6a, 0x65, 0x63, 0x74, 0x5f, 0x78) // "project_x"
        )
        val trackName = byteArrayOf(0x72, 0x65, 0x70, 0x6f, 0x72, 0x74) // "report"

        val safeForm = MoqNameUtils.toSafeForm(namespaceTuples, trackName)
        assertEquals("example.2enet-team2-project_x--report", safeForm)

        val (parsedTuples, parsedTrack) = MoqNameUtils.fromSafeForm(safeForm)
        assertEquals(namespaceTuples.size, parsedTuples.size)
        for (i in namespaceTuples.indices) {
            assertTrue(namespaceTuples[i].contentEquals(parsedTuples[i]))
        }
        assertTrue(trackName.contentEquals(parsedTrack))
    }

    @Test
    fun testAllBytesEncoding() {
        // Test that all non-allowed bytes are properly encoded
        val testBytes = byteArrayOf(
            0x00, 0x1F, 0x20, 0x21, 0x2E, // Control chars, space, !, .
            0x30, 0x39, // 0, 9 (allowed)
            0x41, 0x5A, // A, Z (allowed)
            0x5F, // _ (allowed)
            0x61, 0x7A, // a, z (allowed)
            0x7F.toByte(), 0x80.toByte(), 0xFF.toByte() // DEL, high bytes
        )

        val tuples = listOf(testBytes)
        val trackName = byteArrayOf(0x74, 0x65, 0x73, 0x74) // "test"

        val safeForm = MoqNameUtils.toSafeForm(tuples, trackName)

        // Verify round-trip
        val (parsedTuples, parsedTrack) = MoqNameUtils.fromSafeForm(safeForm)
        assertEquals(1, parsedTuples.size)
        assertTrue(testBytes.contentEquals(parsedTuples[0]))
        assertEquals("test", String(parsedTrack))

        // Verify allowed chars are not encoded
        assertTrue(safeForm.contains("09")) // digits not encoded
        assertTrue(safeForm.contains("AZ")) // uppercase not encoded
        assertTrue(safeForm.contains("az")) // lowercase not encoded
        assertTrue(safeForm.contains("_")) // underscore not encoded
    }

    @Test
    fun testRealWorldExample_Conference() {
        // Realistic conference example
        val safeForm = MoqNameUtils.createSafeForm(
            "conference.example.com",
            "room_123",
            "video",
            trackName = "camera_main"
        )

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(3, namespaces.size)
        assertEquals("conference.example.com", namespaces[0])
        assertEquals("room_123", namespaces[1])
        assertEquals("video", namespaces[2])
        assertEquals("camera_main", trackName)
    }

    @Test
    fun testRealWorldExample_Streaming() {
        // Realistic streaming example with special characters
        val safeForm = MoqNameUtils.createSafeForm(
            "stream.example.net",
            "user:alice",
            "live-2024",
            trackName = "1080p@60fps"
        )

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(3, namespaces.size)
        assertEquals("stream.example.net", namespaces[0])
        assertEquals("user:alice", namespaces[1])
        assertEquals("live-2024", namespaces[2])
        assertEquals("1080p@60fps", trackName)
    }

    @Test
    fun testEdgeCase_OnlyUnderscores() {
        val safeForm = MoqNameUtils.createSafeForm("___", trackName = "____")
        assertEquals("___--____", safeForm)

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(1, namespaces.size)
        assertEquals("___", namespaces[0])
        assertEquals("____", trackName)
    }

    @Test
    fun testEdgeCase_LongNames() {
        // Test with very long namespace and track names
        val longName = "a".repeat(1000)
        val safeForm = MoqNameUtils.createSafeForm(longName, trackName = longName)

        val (namespaces, trackName) = MoqNameUtils.parseSafeForm(safeForm)
        assertEquals(1, namespaces.size)
        assertEquals(longName, namespaces[0])
        assertEquals(longName, trackName)
    }

    @Test
    fun testEdgeCase_EmptyNamespaceWithDoubleHyphen() {
        // Test the case where we have --trackname (empty namespace)
        val safeForm = "--trackname"

        val result: Pair<List<ByteArray>, ByteArray> = MoqNameUtils.fromSafeForm(safeForm)
        val (namespaces, trackName) = result
        assertEquals(0, namespaces.size)
        assertEquals("trackname", String(trackName))
    }
}
