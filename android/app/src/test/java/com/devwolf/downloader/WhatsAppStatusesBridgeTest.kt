package com.devwolf.downloader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppStatusesBridgeTest {
    @Test
    fun serializesStatusItemsAsCapacitorArray() {
        val items = JSONArray().put(JSONObject().apply {
            put("uri", "content://statuses/1")
            put("name", "status.mp4")
            put("mimeType", "video/mp4")
            put("sizeBytes", 1234)
        })

        val bridgeItems = whatsappStatusesToJsArray(items)

        assertEquals(1, bridgeItems.length())
        assertEquals("status.mp4", bridgeItems.getJSONObject(0).getString("name"))
        assertEquals("video/mp4", bridgeItems.getJSONObject(0).getString("mimeType"))
    }
}
