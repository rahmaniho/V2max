package ir.v2max.app

import ir.v2max.app.net.UpdateAssets
import org.junit.Assert.*
import org.junit.Test

class UpdateAssetsTest {
    private fun asset(name: String, url: String = "https://github.com/rahmaniho/V2max/releases/download/v2/$name") =
        UpdateAssets.Asset(name, url)

    @Test fun `universal is preferred over the first architecture asset`() {
        val arm = asset("app-arm64-v8a-release.apk")
        val universal = asset("app-universal-release.apk")
        assertEquals(universal, UpdateAssets.select(listOf(arm, universal), listOf("armeabi-v7a")))
    }

    @Test fun `without universal selects supported architecture in device order`() {
        val arm64 = asset("app-arm64-v8a-release.apk")
        val arm32 = asset("app-armeabi-v7a-release.apk")
        val x64 = asset("app-x86_64-release.apk")
        assertEquals(arm32, UpdateAssets.select(listOf(arm64, arm32, x64), listOf("armeabi-v7a")))
        assertEquals(x64, UpdateAssets.select(listOf(arm64, arm32, x64), listOf("x86_64", "x86")))
        assertNull(UpdateAssets.select(listOf(x64), listOf("x86")))
    }

    @Test fun `missing or unsafe downloads fall back to release page`() {
        assertNull(UpdateAssets.select(listOf(asset("app-arm64-v8a-release.apk")), listOf("x86_64")))
        assertNull(UpdateAssets.select(listOf(asset("app-universal-release.apk", "http://example.org/update.apk")), listOf("arm64-v8a")))
        assertNull(UpdateAssets.select(listOf(asset("app-universal-release.apk", "https://example.org/update.apk")), listOf("arm64-v8a")))
        assertNull(UpdateAssets.select(listOf(asset("app-universal-release.apk.sig")), listOf("arm64-v8a")))
        assertNull(UpdateAssets.select(emptyList(), listOf("arm64-v8a")))
        assertFalse(UpdateAssets.isOfficialDownload("https://github.com.evil.example/rahmaniho/V2max/releases/download/v2/file.apk"))
        assertFalse(UpdateAssets.isOfficialDownload("https://github.com/rahmaniho/V2max/releases/tag/v2"))
        assertTrue(UpdateAssets.isOfficialDownload("https://github.com/rahmaniho/V2max/releases/download/v2/file.apk"))
    }
}
