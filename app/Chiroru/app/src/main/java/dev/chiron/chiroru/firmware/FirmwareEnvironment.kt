package dev.chiron.chiroru.firmware

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject

internal object FirmwareEnvironment {
    fun identity(): FirmwareIdentity {
        val uname = runCatching { Os.uname() }.getOrNull()
        return FirmwareIdentity(
            Build.DEVICE, Build.MODEL, Build.FINGERPRINT, Build.VERSION.INCREMENTAL,
            Build.VERSION.SDK_INT, uname?.release.orEmpty(), uname?.machine.orEmpty(),
            runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1L),
        )
    }

    fun inspect(context: Context): FirmwareSelection {
        val identity = identity()
        return try {
            context.assets.open("firmware/profiles.json").bufferedReader().use {
                parseCatalog(it.readText()).select(identity)
            }
        } catch (error: Exception) {
            FirmwareSelection(identity, null, "Firmware catalog unavailable: ${error.message}")
        }
    }

    fun parseCatalog(text: String): FirmwareCatalog {
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 2) { "Unknown firmware catalog schema" }
        val entries = root.getJSONArray("profiles")
        return FirmwareCatalog(List(entries.length()) { index ->
            val profile = entries.getJSONObject(index)
            val match = profile.getJSONObject("match")
            fun artifact(parent: JSONObject, name: String): FirmwareArtifact = parent.getJSONObject(name).let {
                FirmwareArtifact(it.getString("asset"), it.getString("sha256"))
            }
            val kernelSu = if (!profile.has("kernelSu") || profile.isNull("kernelSu")) null else {
                val support = profile.getJSONObject("kernelSu")
                KernelSuSupport(support.getString("backend"), artifact(support, "module"), artifact(support, "ksud"))
            }
            val adbRoot = if (!profile.has("adbRoot") || profile.isNull("adbRoot")) null else {
                val support = profile.getJSONObject("adbRoot")
                AdbRootSupport(support.getString("backend"), artifact(support, "module"),
                    artifact(support, "loader"), artifact(support, "client"))
            }
            FirmwareProfile(
                id = profile.getString("id"), label = profile.getString("label"),
                identity = FirmwareIdentity(
                    match.getString("device"), match.getString("model"),
                    match.getString("fingerprint"), match.getString("incremental"),
                    match.getInt("sdk"), match.getString("kernelRelease"),
                    match.getString("machine"), match.getLong("pageSize"),
                ),
                root = RootSupport(profile.getJSONObject("root").getString("backend")), kernelSu = kernelSu,
                adbRoot = adbRoot,
            )
        })
    }
}
