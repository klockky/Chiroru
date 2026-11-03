package dev.chiron.chiroru.firmware

/** Exact build identity, not a version range: vendor ABI can change in any OTA. */
internal data class FirmwareIdentity(
    val device: String,
    val model: String,
    val fingerprint: String,
    val incremental: String,
    val sdk: Int,
    val kernelRelease: String,
    val machine: String,
    val pageSize: Long,
) {
    val isDuoMax: Boolean get() = device == "chiron" && model == "YNDX-00055"

    fun report(): String = "device=$device model=$model sdk=$sdk\n" +
        "fingerprint=$fingerprint\nincremental=$incremental\n" +
        "kernel=$kernelRelease machine=$machine page_size=$pageSize"
}

internal data class FirmwareArtifact(val asset: String, val sha256: String) {
    init {
        require(asset.split('/').all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) })
        require(sha256.matches(Regex("[a-f0-9]{64}")))
    }
}

internal data class RootSupport(val backend: String) {
    init { require(backend.matches(Regex("[a-z0-9][a-z0-9-]*"))) }
}

/** Optional, separately verified root+module backend and its payloads. */
internal data class KernelSuSupport(
    val backend: String,
    val module: FirmwareArtifact,
    val ksud: FirmwareArtifact,
    val manager: ManagerSupport,
) {
    init { require(backend.matches(Regex("[a-z0-9][a-z0-9-]*"))) }
}

internal enum class ManagerSource { OFFICIAL_RELEASE, BUNDLED }

/** Manager identity belongs to the kernel payload, not to the UI or device UID. */
internal data class ManagerSupport(
    val source: ManagerSource,
    val packageName: String,
    val signerSha256: String,
    val versionCode: Long,
    val apk: FirmwareArtifact? = null,
) {
    init {
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")))
        require(signerSha256.matches(Regex("[a-f0-9]{64}")))
        require(versionCode > 0)
        require((source == ManagerSource.BUNDLED) == (apk != null))
    }

    fun accepts(packageName: String, signers: List<String>, versionCode: Long): Boolean =
        packageName == this.packageName && signers == listOf(signerSha256) &&
            versionCode == this.versionCode
}

internal enum class FirmwareSupport {
    UNSUPPORTED, ROOT_ONLY, ROOT_AND_KERNEL_SU;

    val canRoot: Boolean get() = this != UNSUPPORTED
    val canLoadKernelSu: Boolean get() = this == ROOT_AND_KERNEL_SU
}

/** ADB root is independent of KernelSU and never participates in boot loading. */
internal data class AdbRootSupport(
    val backend: String,
    val module: FirmwareArtifact,
    val loader: FirmwareArtifact,
    val client: FirmwareArtifact,
) {
    init { require(backend.matches(Regex("[a-z0-9][a-z0-9-]*"))) }
}

internal data class FirmwareProfile(
    val id: String,
    val label: String,
    val identity: FirmwareIdentity,
    val root: RootSupport,
    val kernelSu: KernelSuSupport? = null,
    val adbRoot: AdbRootSupport? = null,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]*")))
        require(label.isNotBlank())
    }

    val support: FirmwareSupport get() = if (kernelSu == null) FirmwareSupport.ROOT_ONLY else FirmwareSupport.ROOT_AND_KERNEL_SU
}

internal data class FirmwareSelection(
    val identity: FirmwareIdentity,
    val profile: FirmwareProfile?,
    val reason: String? = null,
) {
    val support: FirmwareSupport get() = if (identity.isDuoMax && profile != null && profile.identity == identity)
        profile.support else FirmwareSupport.UNSUPPORTED

    fun report(): String = identity.report() + "\nfirmware_profile=${profile?.id ?: "unsupported"}" +
        "\nroot_backend=${profile?.root?.backend ?: "unsupported"}" +
        "\nkernelsu_backend=${profile?.kernelSu?.backend ?: "unsupported"}" +
        "\nadb_root_backend=${profile?.adbRoot?.backend ?: "unsupported"}" +
        (reason?.let { "\n$it" } ?: "")
}

internal class FirmwareCatalog(val profiles: List<FirmwareProfile>) {
    init {
        require(profiles.isNotEmpty()) { "Empty firmware catalog" }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate firmware ID" }
    }

    fun select(identity: FirmwareIdentity): FirmwareSelection {
        if (!identity.isDuoMax) return FirmwareSelection(identity, null, "Only Yandex Duo Max (chiron / YNDX-00055) is supported")
        val matches = profiles.filter { it.identity == identity }
        return when (matches.size) {
            1 -> FirmwareSelection(identity, matches.single())
            0 -> FirmwareSelection(identity, null, "No exact firmware profile")
            else -> FirmwareSelection(identity, null, "Ambiguous firmware profiles")
        }
    }
}
