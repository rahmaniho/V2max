package ir.v2max.app

import ir.v2max.app.net.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {
    private val release = """{
        "tag_name":"v2.4.0", "name":"V2max 2.4.0", "body":null,
        "draft":false, "prerelease":false, "published_at":"2026-01-01T00:00:00Z",
        "html_url":"https://github.com/rahmaniho/V2max/releases/tag/v2.4.0",
        "assets":[
          {"name":"app-arm64-v8a-release.apk","browser_download_url":"https://github.com/rahmaniho/V2max/releases/download/v2.4.0/arm.apk"},
          {"name":"app-universal-release.apk","browser_download_url":"https://github.com/rahmaniho/V2max/releases/download/v2.4.0/universal.apk"}
        ]
    }""".trimIndent()

    @Test fun `selects newest stable release and universal APK even if arm64 comes first`() = runBlocking {
        val list = """[{"tag_name":"v9.0.0","prerelease":true},$release]"""
        val result = UpdateChecker.checkForUpdate({ list }, "2.3.1", listOf("armeabi-v7a"))
        assertTrue(result is UpdateChecker.Result.Available)
        val info = (result as UpdateChecker.Result.Available).info
        assertEquals("2.4.0", info.version)
        assertEquals("https://github.com/rahmaniho/V2max/releases/download/v2.4.0/universal.apk", info.apkUrl)
        assertEquals("https://github.com/rahmaniho/V2max/releases/tag/v2.4.0", info.pageUrl)
        assertEquals("", info.notes)
    }

    @Test fun `latest alias finds the version in notes instead of the two in V2max`() = runBlocking {
        val alias = """{
            "tag_name":"apk-latest", "name":"V2max — آخرین نسخهٔ آمادهٔ نصب",
            "body":"APK امضاشدهٔ V2max نسخه‌ی 2.5.0", "prerelease":false, "draft":false,
            "html_url":"https://github.com/rahmaniho/V2max/releases/tag/apk-latest", "assets":[]
        }"""
        val result = UpdateChecker.checkForUpdate({ "[$alias]" }, "2.4.0", listOf("arm64-v8a"))
        assertTrue(result is UpdateChecker.Result.Available)
        assertEquals("2.5.0", (result as UpdateChecker.Result.Available).info.version)
    }

    @Test fun `successful current release and failed network are distinct`() = runBlocking {
        assertEquals(UpdateChecker.Result.Current,
            UpdateChecker.checkForUpdate({ "[$release]" }, "2.4.0", listOf("arm64-v8a")))
        assertEquals(UpdateChecker.Result.Unavailable,
            UpdateChecker.checkForUpdate({ throw IOException("offline") }, "2.3.1", listOf("arm64-v8a")))
    }

    @Test fun `uses latest endpoint on list failure and never installs incompatible ABI`() = runBlocking {
        val armOnly = """{
            "tag_name":"v2.4.0", "prerelease":false, "draft":false,
            "html_url":"https://github.com/rahmaniho/V2max/releases/tag/v2.4.0",
            "assets":[{"name":"app-arm64-v8a-release.apk","browser_download_url":"https://github.com/rahmaniho/V2max/releases/download/v2.4.0/arm.apk"}]
        }"""
        val result = UpdateChecker.checkForUpdate(
            { url -> if (url.endsWith("/latest")) armOnly else throw IOException("list unavailable") },
            "2.3.1", listOf("x86_64")
        ) as UpdateChecker.Result.Available
        assertEquals("", result.info.apkUrl)
        assertTrue(result.info.pageUrl.endsWith("/v2.4.0"))
    }

    @Test fun `cancellation is not swallowed as an unavailable update`() = runBlocking {
        try {
            UpdateChecker.checkForUpdate({ throw CancellationException("cancelled") }, "2.3.1", emptyList())
            fail("cancellation should propagate")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
