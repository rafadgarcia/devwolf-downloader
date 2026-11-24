package com.devwolf.downloader

import com.devwolf.downloader.AppUpdateManager.AppVersion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    private fun v(raw: String) = AppVersion.parse(raw)!!

    @Test
    fun semverOrderingHandlesReleaseCandidates() {
        assertTrue(v("3.0.0-rc.6") < v("3.0.0-rc.7"))
        assertTrue(v("3.0.0-rc.7") < v("3.0.0-rc.8"))
        assertTrue(v("3.0.0-rc.8") < v("3.0.0-rc.9"))
        assertTrue(v("3.0.0-rc.9") < v("3.0.0"))
        assertTrue(v("3.0.0-rc.10") > v("3.0.0-rc.9")) // numeric, not lexical
        assertTrue(v("v3.0.0") > v("2.9.9"))
        assertTrue(v("3.1.0-beta") > v("3.0.5"))
        assertTrue(v("3.0.0-rc.1") > v("3.0.0-beta.9")) // "rc" > "beta" lexically
        assertEquals(0, v("v3.0.0").compareTo(v("3.0.0")))
        assertEquals(v("3.0"), v("3.0.0"))
        assertNull(AppVersion.parse("nightly"))
        assertNull(AppVersion.parse(""))
    }

    private fun release(tag: String, pre: Boolean, draft: Boolean = false, apk: Boolean = true): JSONObject {
        val assets = JSONArray()
        if (apk) {
            assets.put(
                JSONObject()
                    .put("name", "devwolf-downloader-$tag-arm64-v8a-release.apk")
                    .put("browser_download_url", "https://github.com/x/y/releases/download/$tag/a.apk")
                    .put("size", 100)
                    .put("digest", "sha256:ABCDEF"),
            )
            assets.put(
                JSONObject()
                    .put("name", "devwolf-downloader-$tag-universal-release.apk")
                    .put("browser_download_url", "https://github.com/x/y/releases/download/$tag/u.apk")
                    .put("size", 300),
            )
        }
        assets.put(JSONObject().put("name", "notes.txt").put("browser_download_url", "https://github.com/x/y/n.txt"))
        return JSONObject()
            .put("tag_name", tag)
            .put("name", "Release $tag")
            .put("prerelease", pre)
            .put("draft", draft)
            .put("body", "Notas $tag")
            .put("assets", assets)
    }

    @Test
    fun picksHighestReleaseRespectingPreReleaseOption() {
        val releases = JSONArray()
            .put(release("v3.0.0-rc.6", pre = true))
            .put(release("v3.0.0-rc.8", pre = true, draft = true)) // drafts ignored
            .put(release("v3.0.0-rc.7", pre = true, apk = false)) // no APK ignored
            .put(release("v2.0.0", pre = false))
            .put(release("v3.0.0-rc.5", pre = true))

        val withPre = AppUpdateManager.pickLatestRelease(releases, includePreReleases = true)
        assertEquals("v3.0.0-rc.6", withPre!!.tag)
        assertTrue(withPre.isPreRelease)
        assertEquals("abcdef", withPre.assets.first().sha256)
        assertEquals(2, withPre.assets.size) // non-APK assets filtered

        val stableOnly = AppUpdateManager.pickLatestRelease(releases, includePreReleases = false)
        assertEquals("v2.0.0", stableOnly!!.tag)
    }

    @Test
    fun picksAbiSpecificApkThenUniversal() {
        val info = AppUpdateManager.pickLatestRelease(JSONArray().put(release("v3.0.0", pre = false)), true)!!
        assertEquals(
            "devwolf-downloader-v3.0.0-arm64-v8a-release.apk",
            AppUpdateManager.pickAssetForAbis(info.assets, listOf("arm64-v8a", "armeabi-v7a"))!!.name,
        )
        assertEquals(
            "devwolf-downloader-v3.0.0-universal-release.apk",
            AppUpdateManager.pickAssetForAbis(info.assets, listOf("x86_64"))!!.name,
        )
        assertNotNull(AppUpdateManager.pickAssetForAbis(info.assets, emptyList()))
        assertNull(AppUpdateManager.pickAssetForAbis(emptyList(), listOf("arm64-v8a")))
    }
}
