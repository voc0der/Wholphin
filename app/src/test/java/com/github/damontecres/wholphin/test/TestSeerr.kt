package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.api.seerr.infrastructure.Serializer
import com.github.damontecres.wholphin.api.seerrproxy.SeerrProxyRequest
import com.github.damontecres.wholphin.api.seerrproxy.SeerrProxyStatusResponse
import com.github.damontecres.wholphin.api.seerrproxy.isAvailable
import com.github.damontecres.wholphin.ui.setup.seerr.createSeerrApiUrl
import com.github.damontecres.wholphin.ui.setup.seerr.createUrls
import com.github.damontecres.wholphin.ui.setup.seerr.migrateSeerrUrl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestSeerr {
    @Test
    fun testCreateUrls() {
        val urls =
            createUrls("jellyseerr.com")
                .map { it.toString() }

        val expected =
            listOf(
                "http://jellyseerr.com/",
                "https://jellyseerr.com/",
                "http://jellyseerr.com:5055/",
                "https://jellyseerr.com:5055/",
            )
        assertEquals(expected, urls)
    }

    @Test
    fun testCreateUrls2() {
        val urls =
            createUrls("https://jellyseerr.com")
                .map { it.toString() }

        val expected =
            listOf(
                "https://jellyseerr.com/",
                "https://jellyseerr.com:5055/",
            )
        assertEquals(expected, urls)
    }

    @Test
    fun testCreateUrls3() {
        val urls =
            createUrls("http://jellyseerr.com")
                .map { it.toString() }

        val expected =
            listOf(
                "http://jellyseerr.com/",
                "http://jellyseerr.com:5055/",
            )
        assertEquals(expected, urls)
    }

    @Test
    fun testCreateUrls4() {
        val urls =
            createUrls("jellyseerr.com:5055")
                .map { it.toString() }

        val expected =
            listOf(
                "http://jellyseerr.com:5055/",
                "https://jellyseerr.com:5055/",
            )
        assertEquals(expected, urls)
    }

    @Test
    fun testCreateUrls5() {
        val urls =
            createUrls("10.0.0.2:443")
                .map { it.toString() }

        val expected =
            listOf(
                "http://10.0.0.2:443/",
                "https://10.0.0.2/",
            )
        assertEquals(expected, urls)
    }

    @Test
    fun testCreateUrls6() {
        val urls =
            createUrls("10.0.0.2:8080")
                .map { it.toString() }

        val expected =
            listOf(
                "http://10.0.0.2:8080/",
                "https://10.0.0.2:8080/",
            )
        assertEquals(expected, urls)
    }

    @Test
    fun testCreateUrls7() {
        val urls =
            createUrls("http://10.0.0.2:80")
                .map { it.toString() }

        val expected =
            listOf(
                "http://10.0.0.2/",
                "http://10.0.0.2:5055/",
            )
        assertEquals(expected, urls)
    }

    @Test
    fun testCreateUrls8() {
        val urls =
            createUrls("https://10.0.0.2:443")
                .map { it.toString() }

        val expected =
            listOf(
                "https://10.0.0.2/",
                "https://10.0.0.2:5055/",
            )
        assertEquals(expected, urls)
    }

    @Test
    fun `Test createUrls for path`() {
        val urls =
            createUrls("https://jellyseerr.com/seerr/")
                .map { it.toString() }

        val expected =
            listOf(
                "https://jellyseerr.com/seerr/",
                "https://jellyseerr.com:5055/seerr/",
            )
        assertEquals(expected, urls)
    }

    @Test
    fun `Test build api url`() {
        var url = "https://jellyseerr.com/"
        assertEquals("https://jellyseerr.com/api/v1", createSeerrApiUrl(url))

        url = "https://jellyseerr.com/path"
        assertEquals("https://jellyseerr.com/path/api/v1", createSeerrApiUrl(url))

        url = "http://jellyseerr.com:5055/"
        assertEquals("http://jellyseerr.com:5055/api/v1", createSeerrApiUrl(url))

        url = "http://jellyseerr.com:7878/path/"
        assertEquals("http://jellyseerr.com:7878/path/api/v1", createSeerrApiUrl(url))

        url = "http://jellyseerr.com/api/v1"
        assertEquals("http://jellyseerr.com/api/v1", createSeerrApiUrl(url))

        url = "http://jellyseerr.com/api/v1/"
        assertEquals("http://jellyseerr.com/api/v1/", createSeerrApiUrl(url))

        url = "http://jellyseerr.com/path/api/v1"
        assertEquals("http://jellyseerr.com/path/api/v1", createSeerrApiUrl(url))
    }

    @Test
    fun `Test migration`() {
        assertEquals("http://10.0.0.2/", migrateSeerrUrl("http://10.0.0.2/api/v1"))
        assertEquals("https://10.0.0.2/", migrateSeerrUrl("https://10.0.0.2/api/v1"))
        assertEquals("http://10.0.0.2:5055/", migrateSeerrUrl("http://10.0.0.2:5055/api/v1"))
        assertEquals("http://10.0.0.2/", migrateSeerrUrl("http://10.0.0.2/api/v1/"))
        assertEquals("http://10.0.0.2/path/", migrateSeerrUrl("http://10.0.0.2/path/api/v1"))
        assertEquals("http://10.0.0.2/api/v1/", migrateSeerrUrl("http://10.0.0.2/api/v1/api/v1"))
        assertEquals("http://10.0.0.2/", migrateSeerrUrl("http://10.0.0.2/"))
        assertEquals("http://10.0.0.2/", migrateSeerrUrl("http://10.0.0.2"))
    }

    @Test
    fun testSeerrProxyStatusAvailable() {
        val status =
            SeerrProxyStatusResponse(
                enabled = true,
                configured = true,
                linked = true,
                seerrReachable = true,
            )

        assertTrue(status.isAvailable)
    }

    @Test
    fun testSeerrProxyStatusAvailableWithJellyfinResponseCasing() {
        val status =
            Serializer.kotlinxSerializationJson.decodeFromString<SeerrProxyStatusResponse>(
                """
                {
                  "Enabled": true,
                  "Configured": true,
                  "JellyfinUserId": "d340eac9d80e4a9ee133ddf1479",
                  "Linked": true,
                  "SeerrUserId": 13,
                  "DisplayName": "vocoder",
                  "SeerrReachable": true
                }
                """.trimIndent(),
            )

        assertTrue(status.isAvailable)
        assertEquals("d340eac9d80e4a9ee133ddf1479", status.jellyfinUserId)
        assertEquals(13, status.seerrUserId)
        assertEquals("vocoder", status.displayName)
    }

    @Test
    fun testSeerrProxyStatusUnavailable() {
        assertFalse(
            SeerrProxyStatusResponse(
                enabled = true,
                configured = true,
                linked = false,
                seerrReachable = true,
            ).isAvailable,
        )
        assertFalse(
            SeerrProxyStatusResponse(
                enabled = true,
                configured = true,
                linked = true,
                seerrReachable = false,
            ).isAvailable,
        )
        assertFalse(
            SeerrProxyStatusResponse(
                enabled = true,
                configured = false,
                linked = true,
                seerrReachable = true,
            ).isAvailable,
        )
    }

    @Test
    fun testSeerrProxyRequestJson() {
        val encoded =
            Serializer.kotlinxSerializationJson
                .encodeToString(
                    SeerrProxyRequest(
                        mediaType = "tv",
                        mediaId = 1399,
                        seasons = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))),
                        is4k = false,
                    ),
                ).let { Serializer.kotlinxSerializationJson.parseToJsonElement(it).jsonObject }

        assertEquals("tv", encoded["mediaType"]?.jsonPrimitive?.content)
        assertEquals(1399, encoded["mediaId"]?.jsonPrimitive?.int)
        assertEquals(false, encoded["is4k"]?.jsonPrimitive?.boolean)
        assertEquals(
            listOf(1, 2),
            encoded["seasons"]?.jsonArray?.map { it.jsonPrimitive.int },
        )
        assertFalse(encoded.containsKey("userId"))
    }
}
