package dev.chiron.chiroru

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ManagerReleaseTest {
    private fun asset(name: String = "KernelSU_Next_v3.4.2_33355-release.apk") = JSONObject()
        .put("name", name)
        .put("size", 16_682_167L)
        .put("digest", "sha256:" + "a".repeat(64))
        .put("browser_download_url", "https://github.com/KernelSU-Next/KernelSU-Next/releases/download/v3.4.2/$name")

    private fun release(vararg assets: JSONObject) = JSONObject()
        .put("tag_name", "v3.4.2").put("draft", false).put("prerelease", false)
        .put("assets", JSONArray(assets.toList()))

    @Test fun choosesStandardApkInsteadOfSpoofedOrDebugBuilds() {
        val result = ManagerReleases.parse(release(
            asset("KernelSU_Next_v3.4.2-spoofed_33355-release.apk"),
            asset("KernelSU_Next_v3.4.2_33355-debug.apk"), asset(),
        ).toString())
        assertEquals("v3.4.2", result.tag)
        assertFalse(result.url.path.contains("spoofed"))
        assertEquals(16_682_167L, result.size)
    }

    @Test fun rejectsSpoofedOnlyRelease() {
        assertThrows(IllegalStateException::class.java) {
            ManagerReleases.parse(release(asset("KernelSU_Next_v3.4.2-spoofed_33355-release.apk")).toString())
        }
    }

    @Test fun rejectsDraftAndPrerelease() {
        for (field in listOf("draft", "prerelease")) {
            assertThrows(IllegalStateException::class.java) {
                ManagerReleases.parse(release(asset()).put(field, true).toString())
            }
        }
    }

    @Test fun rejectsMissingOrMalformedChecksum() {
        for (digest in listOf("", "md5:" + "a".repeat(32), "sha256:bad")) {
            assertThrows(IllegalStateException::class.java) {
                ManagerReleases.parse(release(asset().put("digest", digest)).toString())
            }
        }
    }

    @Test fun rejectsOtherRepositoriesAndInsecureDownloads() {
        for (url in listOf("http://github.com/KernelSU-Next/KernelSU-Next/releases/download/v1/a.apk",
            "https://github.com/another/repo/releases/download/v1/a.apk", "https://example.com/a.apk")) {
            assertThrows(IllegalStateException::class.java) {
                ManagerReleases.parse(release(asset().put("browser_download_url", url)).toString())
            }
        }
    }

    @Test fun rejectsUnboundedDownloads() {
        for (size in listOf(0L, -1L, ManagerReleases.MAX_APK_BYTES + 1)) {
            assertThrows(IllegalStateException::class.java) {
                ManagerReleases.parse(release(asset().put("size", size)).toString())
            }
        }
    }
}
