package dev.chiron.chiroru

import org.json.JSONObject
import java.net.URL

internal data class ManagerRelease(val tag: String, val url: URL, val size: Long, val sha256: String)

internal object ManagerReleases {
    const val PACKAGE = "com.rifsxd.ksunext"
    // The shipped 1307 daemon/module use UAPI 5; never silently select a future release.
    const val TAG = "v3.4.2"
    const val API = "https://api.github.com/repos/KernelSU-Next/KernelSU-Next/releases/tags/$TAG"
    const val DOWNLOAD_PATH = "/KernelSU-Next/KernelSU-Next/releases/download/"
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    // Also used as EXPECTED_MANAGER_HASH by the shipped KernelSU-Next module.
    const val SIGNER_SHA256 = "79e590113c4c4c0c222978e413a5faa801666957b1212a328e46c00c69821bf7"

    fun parse(json: String): ManagerRelease {
        val release = JSONObject(json)
        check(release.getString("tag_name") == TAG) { "Unexpected manager release" }
        check(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "Not a stable release" }
        val assets = release.getJSONArray("assets")
        val candidates = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
            val name = it.getString("name")
            name.startsWith("KernelSU_Next_") && name.endsWith("-release.apk") &&
                !name.contains("spoof", ignoreCase = true)
        }
        check(candidates.size == 1) { "Expected one standard KernelSU Next release APK" }
        val asset = candidates.single()
        val url = URL(asset.getString("browser_download_url"))
        check(url.protocol == "https" && url.host == "github.com" &&
            url.path.startsWith("$DOWNLOAD_PATH$TAG/") && url.userInfo == null && url.port == -1) {
            "APK is not hosted in the official release repository"
        }
        val size = asset.getLong("size")
        check(size in 1..MAX_APK_BYTES) { "Invalid manager APK size" }
        val digest = asset.getString("digest")
        check(Regex("sha256:[a-fA-F0-9]{64}").matches(digest)) { "Missing GitHub SHA-256 digest" }
        return ManagerRelease(release.getString("tag_name"), url, size, digest.removePrefix("sha256:").lowercase())
    }
}
