package dev.chiron.chiroru

import dev.chiron.chiroru.firmware.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FirmwareTest {
    @get:Rule val temporary = TemporaryFolder()
    private val catalogText get() = RuntimeEnvironment.getApplication().assets
        .open("firmware/profiles.json").bufferedReader().use { it.readText() }
    private val shipped get() = FirmwareEnvironment.parseCatalog(catalogText).profiles.single { it.id == "chiron-5000001307" }
    private fun artifact(path: String, content: String) = FirmwareArtifact(path,
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) })
    private fun profile() = shipped.copy(kernelSu = KernelSuSupport(
        shipped.kernelSu!!.backend, artifact("test/module", "module-v1"), artifact("test/ksud", "ksud-v1"), shipped.kernelSu!!.manager))
    private val contents = mutableMapOf("test/module" to "module-v1", "test/ksud" to "ksud-v1")
    private var assetReads = 0
    private fun assets() = FirmwareAssets(temporary.root) {
        assetReads++
        checkNotNull(contents[it]).byteInputStream()
    }

    private class FakeBackend : FirmwareBackend {
        var available = true
        val unavailableIds = mutableSetOf<String>()
        var supportsCalls = 0
        val calls = mutableListOf<String>()
        override fun supports(id: String): Boolean { supportsCalls++; return available && id !in unavailableIds }
        override fun probe(id: String): String {
            calls += "probe:$id"
            return "open(/dev/mali0) = 3\nVERSION_CHECK rc=0\n"
        }
        override fun root(id: String, module: String?, ksud: String?, runHooks: Boolean): String {
            calls += "root:$id:$runHooks:${module?.let { File(it).readText() }}:${ksud?.let { File(it).readText() }}"
            return "root_stage_done uid=0 euid=0\nroot_console: ready\nksud rc=0\n"
        }
    }

    @Test fun exactDeviceSelectsTheShippedProfile() {
        val identity = FirmwareIdentity("chiron", "YNDX-00055",
            "Yandex/chiron/chiron:11/RQ3A.210805.001.A1/5000001307:user/dev-keys", "5000001307", 30,
            "5.4.125-android11-2-g601513d4b115-ab5000001307", "aarch64", 4096)
        assertEquals("chiron-5000001307", FirmwareEnvironment.parseCatalog(catalogText).select(identity).profile?.id)
    }

    @Test fun factoryProfileIsSelectedByExactIdentityAndOffersAdbAndKernelSu() {
        val identity = FirmwareIdentity("chiron", "YNDX-00055",
            "Yandex/chiron/chiron:11/RQ3A.210805.001.A1/1982735225:user/dev-keys", "1982735225", 30,
            "5.4.125-android11-2-g85fe5498964f-ab1982735225", "armv8l", 4096)
        val selection = FirmwareEnvironment.parseCatalog(catalogText).select(identity)
        assertEquals("chiron-1982735225", selection.profile?.id)
        assertEquals(FirmwareSupport.ROOT_AND_KERNEL_SU, selection.support)
        assertEquals("chiron-1982735225", selection.profile?.kernelSu?.backend)
        assertEquals("chiron-1982735225-adb", selection.profile?.adbRoot?.backend)
    }

    @Test fun factoryAdbRootUsesItsOwnStaticClientWithoutKernelSu() {
        val factory = FirmwareEnvironment.parseCatalog(catalogText).profiles.single { it.id == "chiron-1982735225" }
        contents.clear()
        contents.putAll(mapOf("test/mod" to "mod", "test/loader" to "loader", "test/client" to "client"))
        val p = factory.copy(adbRoot = AdbRootSupport("factory-adb-backend",
            artifact("test/mod", "mod"), artifact("test/loader", "loader"),
            artifact("test/client", "client")))
        val backend = FakeBackend()
        FirmwareRunner(assets(), backend).run(FirmwareSelection(p.identity, p), Operation.ADB_ROOT)
        assertEquals(listOf("root:factory-adb-backend:false:mod:loader"), backend.calls)
        assertEquals("client", File(temporary.root, "firmware/${p.id}/chiroru_adb.ko.client").readText())
    }

    @Test fun allShippedProfilesRouteEveryOperationToTheirOwnVerifiedPayloads() {
        val catalog = FirmwareEnvironment.parseCatalog(catalogText)
        val context = RuntimeEnvironment.getApplication()
        for (p in catalog.profiles) {
            val selection = catalog.select(p.identity)
            val seen = mutableListOf<String>()
            val store = FirmwareAssets(temporary.root) {
                seen += it
                context.assets.open(it)
            }
            val backend = object : FirmwareBackend {
                val calls = mutableListOf<Pair<String, Boolean>>()
                override fun supports(id: String) = true
                override fun probe(id: String): String { calls += id to false; return "probe" }
                override fun root(id: String, module: String?, ksud: String?, runHooks: Boolean): String {
                    calls += id to runHooks
                    if (module != null) {
                        assertTrue(module.contains("/firmware/${p.id}/"))
                        assertTrue(checkNotNull(ksud).contains("/firmware/${p.id}/"))
                    }
                    return "root_stage_done uid=0 euid=0\nmodule: kernelsu loaded\n"
                }
            }
            val runner = FirmwareRunner(store, backend) { true }
            Operation.entries.forEach { runner.run(selection, it) }
            assertEquals(listOf(p.root.backend to false, p.root.backend to false,
                p.kernelSu!!.backend to true, p.kernelSu!!.backend to false, p.adbRoot!!.backend to false), backend.calls)
            assertEquals(listOf(p.kernelSu!!.module.asset, p.kernelSu!!.ksud.asset,
                p.kernelSu!!.module.asset, p.kernelSu!!.ksud.asset,
                p.adbRoot!!.module.asset, p.adbRoot!!.loader.asset, p.adbRoot!!.client.asset), seen)
        }
    }

    @Test fun newBuildIsDistinctAndUsesItsOwnKernelPayloads() {
        val catalog = FirmwareEnvironment.parseCatalog(catalogText)
        val build = catalog.profiles.single { it.id == "chiron-5000001245" }
        val previous = catalog.profiles.single { it.id == "chiron-5000001307" }
        assertEquals("5000001245", build.identity.incremental)
        assertEquals("5.4.125-android11-2-g601513d4b115-ab5000001245", build.identity.kernelRelease)
        assertEquals("aarch64", build.identity.machine)
        assertEquals(ManagerSource.OFFICIAL_RELEASE, build.kernelSu!!.manager.source)
        assertNotEquals(previous.kernelSu!!.module.sha256, build.kernelSu!!.module.sha256)
        assertNotEquals(previous.adbRoot!!.module.sha256, build.adbRoot!!.module.sha256)
        assertEquals(previous.adbRoot!!.client.asset, build.adbRoot!!.client.asset)
        assertEquals(FirmwareSupport.ROOT_AND_KERNEL_SU, catalog.select(build.identity).support)
    }

    @Test fun otaCandidateRejectsMixedAndNeighbouringFirmwareIdentities() {
        val catalog = FirmwareEnvironment.parseCatalog(catalogText)
        val candidate = catalog.profiles.single { it.id == "chiron-5000001248" }
        val previous = catalog.profiles.single { it.id == "chiron-5000001245" }
        assertEquals(candidate, catalog.select(candidate.identity).profile)
        assertTrue(candidate.label.contains("OTA test"))
        assertNull(catalog.select(candidate.identity.copy(kernelRelease = previous.identity.kernelRelease)).profile)
        assertNull(catalog.select(candidate.identity.copy(fingerprint = previous.identity.fingerprint)).profile)
        assertNull(catalog.select(candidate.identity.copy(incremental = "5000001249")).profile)
        assertNotEquals(previous.adbRoot!!.module.sha256, candidate.adbRoot!!.module.sha256)
        assertNotEquals(previous.kernelSu!!.module.sha256, candidate.kernelSu!!.module.sha256)
        assertEquals(previous.kernelSu!!.manager, candidate.kernelSu!!.manager)
    }

    @Test fun nextOtaProfileIsSelectedOnlyByItsExactIdentity() {
        val catalog = FirmwareEnvironment.parseCatalog(catalogText)
        val candidate = catalog.profiles.single { it.id == "chiron-5000001289" }
        val previous = catalog.profiles.single { it.id == "chiron-5000001248" }
        assertEquals(candidate, catalog.select(candidate.identity).profile)
        assertTrue(candidate.label.contains("OTA test"))
        assertNull(catalog.select(candidate.identity.copy(kernelRelease = previous.identity.kernelRelease)).profile)
        assertNull(catalog.select(candidate.identity.copy(incremental = "5000001288")).profile)
        assertNotEquals(previous.adbRoot!!.module.sha256, candidate.adbRoot!!.module.sha256)
        assertEquals(previous.kernelSu!!.manager, candidate.kernelSu!!.manager)
    }

    @Test fun ota1321RejectsMixedIdentityAndUsesItsOwnModules() {
        val catalog = FirmwareEnvironment.parseCatalog(catalogText)
        val candidate = catalog.profiles.single { it.id == "chiron-5000001321" }
        assertEquals(candidate, catalog.select(candidate.identity).profile)
        assertTrue(candidate.label.contains("OTA test"))
        for (previous in catalog.profiles.filter { it.id != candidate.id }) {
            assertNull(catalog.select(candidate.identity.copy(fingerprint = previous.identity.fingerprint)).profile)
            assertNull(catalog.select(candidate.identity.copy(incremental = previous.identity.incremental)).profile)
            assertNull(catalog.select(candidate.identity.copy(kernelRelease = previous.identity.kernelRelease)).profile)
            assertNotEquals(previous.adbRoot!!.module.sha256, candidate.adbRoot!!.module.sha256)
            assertNotEquals(previous.kernelSu!!.module.sha256, candidate.kernelSu!!.module.sha256)
        }
        for (build in listOf("5000001320", "5000001322")) {
            assertNull(catalog.select(candidate.identity.copy(incremental = build)).profile)
        }
    }

    @Test fun ota1346RequiresExactIdentityAndDedicatedModules() {
        val catalog = FirmwareEnvironment.parseCatalog(catalogText)
        val candidate = catalog.profiles.single { it.id == "chiron-5000001346" }
        assertEquals(candidate, catalog.select(candidate.identity).profile)
        for (previous in catalog.profiles.filter { it.id != candidate.id }) {
            assertNull(catalog.select(candidate.identity.copy(fingerprint = previous.identity.fingerprint)).profile)
            assertNull(catalog.select(candidate.identity.copy(incremental = previous.identity.incremental)).profile)
            assertNull(catalog.select(candidate.identity.copy(kernelRelease = previous.identity.kernelRelease)).profile)
            assertNotEquals(previous.adbRoot!!.module.sha256, candidate.adbRoot!!.module.sha256)
            assertNotEquals(previous.kernelSu!!.module.sha256, candidate.kernelSu!!.module.sha256)
        }
        for (build in listOf("5000001345", "5000001347")) {
            assertNull(catalog.select(candidate.identity.copy(incremental = build)).profile)
        }
    }

    @Test fun factoryManagerIsRequiredBeforeAnyNativeCallOrPayloadRead() {
        val factory = FirmwareEnvironment.parseCatalog(catalogText).profiles.single { it.id == "chiron-1982735225" }
        val backend = FakeBackend()
        val runner = FirmwareRunner(assets(), backend) { false }
        for (operation in listOf(Operation.MODULE, Operation.MODULE_SAFE)) {
            val report = runner.run(FirmwareSelection(factory.identity, factory), operation)
            assertTrue(report.contains("preflight: blocked"))
            assertTrue(report.contains("matching bundled"))
            assertFalse(ReportResult.parse(operation, report).kernelTouched)
        }
        assertEquals(0, backend.supportsCalls)
        assertEquals(0, assetReads)
        assertTrue(backend.calls.isEmpty())
        runner.run(FirmwareSelection(factory.identity, factory), Operation.ROOT)
        assertEquals(listOf("root:${factory.root.backend}:false:null:null"), backend.calls)
    }

    @Test fun managerRequirementsRejectCrossProfileSignersAndUnpinnedFactoryVersions() {
        val profiles = FirmwareEnvironment.parseCatalog(catalogText).profiles
        val official = profiles.single { it.id == "chiron-5000001307" }.kernelSu!!.manager
        val factory = profiles.single { it.id == "chiron-1982735225" }.kernelSu!!.manager
        assertEquals(ManagerSource.OFFICIAL_RELEASE, official.source)
        assertEquals(ManagerSource.BUNDLED, factory.source)
        for (requirement in listOf(official, factory)) {
            assertTrue(requirement.accepts(requirement.packageName, listOf(requirement.signerSha256), requirement.versionCode))
            assertFalse(requirement.accepts("other.package", listOf(requirement.signerSha256), 30046))
            assertFalse(requirement.accepts(requirement.packageName, emptyList(), 30046))
            assertFalse(requirement.accepts(requirement.packageName, listOf(official.signerSha256, factory.signerSha256), 30046))
        }
        assertFalse(factory.accepts(factory.packageName, listOf(official.signerSha256), 30046))
        assertFalse(official.accepts(official.packageName, listOf(factory.signerSha256), 30046))
        assertFalse(factory.accepts(factory.packageName, listOf(factory.signerSha256), 30047))
        assertFalse(official.accepts(official.packageName, listOf(official.signerSha256), 33356))
    }

    @Test fun managerPolicyCannotDisappearOrFallBackToOfficialInTheCatalog() {
        for (mutation in listOf("missing", "source", "apk", "versionCode")) {
            val catalog = JSONObject(catalogText)
            val support = catalog.getJSONArray("profiles").getJSONObject(1).getJSONObject("kernelSu")
            when (mutation) {
                "missing" -> support.remove("manager")
                "source" -> support.getJSONObject("manager").put("source", "LATEST")
                else -> support.getJSONObject("manager").remove(mutation)
            }
            assertThrows(Exception::class.java) { FirmwareEnvironment.parseCatalog(catalog.toString()) }
        }
    }

    @Test fun nonDuoMaxIsBlockedEvenIfCatalogContainsAnExactProfile() {
        val p = profile()
        for (identity in listOf(p.identity.copy(device = "other"), p.identity.copy(model = "YNDX-OTHER"),
            p.identity.copy(device = "CHIRON"), p.identity.copy(model = "YNDX-00055 "))) {
            val foreign = p.copy(identity = identity)
            val selected = FirmwareCatalog(listOf(foreign)).select(identity)
            assertNull(selected.profile)
            assertEquals(FirmwareSupport.UNSUPPORTED, selected.support)
            val backend = FakeBackend()
            val runner = FirmwareRunner(assets(), backend)
            // Even a directly constructed selection cannot bypass the device gate.
            Operation.entries.forEach { operation ->
                val report = runner.run(FirmwareSelection(identity, foreign), operation)
                assertTrue(report.contains("preflight: blocked (Yandex Duo Max only)"))
            }
            assertEquals(0, backend.supportsCalls)
            assertTrue(backend.calls.isEmpty())
        }
        assertEquals(0, assetReads)
    }

    @Test fun forgedSelectionCannotBypassExactFirmwareMatching() {
        val p = profile()
        val selection = FirmwareSelection(p.identity.copy(incremental = "unknown"), p)
        val backend = FakeBackend()
        assertEquals(FirmwareSupport.UNSUPPORTED, selection.support)
        Operation.entries.forEach {
            assertTrue(FirmwareRunner(assets(), backend).run(selection, it).contains("firmware identity mismatch"))
        }
        assertEquals(0, backend.supportsCalls)
        assertEquals(0, assetReads)
    }

    @Test fun adbRootUsesItsOwnModuleAndClientWithoutKernelSuOrLifecycle() {
        val p = profile().copy(kernelSu = null, adbRoot = AdbRootSupport("adb-backend",
            artifact("test/adb", "adb-module"), artifact("test/loader", "loader"),
            artifact("test/client", "client")))
        contents.clear()
        contents.putAll(mapOf("test/adb" to "adb-module", "test/loader" to "loader", "test/client" to "client"))
        val backend = FakeBackend()
        FirmwareRunner(assets(), backend).run(FirmwareSelection(p.identity, p), Operation.ADB_ROOT)
        assertEquals(listOf("root:adb-backend:false:adb-module:loader"), backend.calls)
        assertEquals("client", File(temporary.root, "firmware/${p.id}/chiroru_adb.ko.client").readText())
        assertFalse(File(temporary.root, "firmware/${p.id}/kernelsu.ko").exists())
        contents["test/client"] = "corrupt"
        backend.calls.clear()
        val report = FirmwareRunner(assets(), backend).run(FirmwareSelection(p.identity, p), Operation.ADB_ROOT)
        assertTrue(report.contains("hash mismatch"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun absentAdbSupportDoesNotFallBackToKernelSu() {
        val p = profile().copy(adbRoot = null)
        val backend = FakeBackend()
        val report = FirmwareRunner(assets(), backend).run(FirmwareSelection(p.identity, p), Operation.ADB_ROOT)
        assertTrue(report.contains("ADB root is not supported"))
        assertEquals(0, backend.supportsCalls)
        assertEquals(0, assetReads)
    }

    @Test fun everyIdentityFieldParticipatesAndThereIsNoNearestVersionFallback() {
        val p = profile()
        val identity = p.identity
        val alternatives = listOf(identity.copy(device = "other"), identity.copy(model = "other"),
            identity.copy(fingerprint = identity.fingerprint + "-changed"), identity.copy(incremental = "5000001364"),
            identity.copy(sdk = 31), identity.copy(kernelRelease = identity.kernelRelease + "-changed"),
            identity.copy(machine = "armv7l"), identity.copy(pageSize = 16384))
        alternatives.forEach { assertNull(FirmwareCatalog(listOf(p)).select(it).profile) }
    }

    @Test fun ambiguousProfilesAndUnknownSchemaAreRejected() {
        val p = profile()
        assertNull(FirmwareCatalog(listOf(p, p.copy(id = "another"))).select(p.identity).profile)
        assertThrows(IllegalArgumentException::class.java) {
            FirmwareEnvironment.parseCatalog(catalogText.replace("\"schemaVersion\": 3", "\"schemaVersion\": 99"))
        }
        assertThrows(IllegalArgumentException::class.java) { FirmwareCatalog(listOf(p, p)) }
    }

    @Test fun unknownFirmwareNeverTouchesNativeOrAssetsForAnyEntryMode() {
        val backend = FakeBackend()
        val runner = FirmwareRunner(assets(), backend)
        val selection = FirmwareCatalog(listOf(profile())).select(profile().identity.copy(incremental = "new"))
        Operation.entries.forEach { operation ->
            val report = runner.run(selection, operation)
            assertTrue(report.contains("incremental=new"))
            assertTrue(report.contains("preflight: blocked"))
            val result = ReportResult.parse(operation, report)
            assertEquals(Outcome.ERROR, result.outcome)
            assertFalse(result.kernelTouched)
            assertFalse(result.canRetry(report))
        }
        assertEquals(0, backend.supportsCalls)
        assertEquals(0, assetReads)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun missingNativeBackendNeverExtractsOrExecutesPayloads() {
        val backend = FakeBackend().apply { available = false }
        val p = profile()
        val report = FirmwareRunner(assets(), backend).run(FirmwareSelection(p.identity, p), Operation.MODULE)
        assertTrue(report.contains("Native backend missing"))
        assertEquals(0, assetReads)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun badPayloadHashStopsBeforeRootAndKeepsThePreviousCachedModule() {
        val p = profile()
        val store = assets()
        val (cached, _) = store.prepare(p)
        contents["test/module"] = "broken module"
        val backend = FakeBackend()
        val report = FirmwareRunner(store, backend).run(FirmwareSelection(p.identity, p), Operation.MODULE)
        assertTrue(report.contains("hash mismatch"))
        assertEquals("module-v1", File(cached).readText())
        assertFalse(File("$cached.tmp").exists())
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun badKsudAlsoStopsBeforeRoot() {
        val p = profile()
        contents["test/ksud"] = "wrong cli"
        val backend = FakeBackend()
        val report = FirmwareRunner(assets(), backend).run(FirmwareSelection(p.identity, p), Operation.MODULE_SAFE)
        assertTrue(report.contains("hash mismatch"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun payloadsAreRefreshedAndEachProfileHasItsOwnDirectory() {
        val p = profile()
        val store = assets()
        val (first, _) = store.prepare(p)
        File(first).writeText("old cache")
        store.prepare(p)
        assertEquals("module-v1", File(first).readText())
        contents["test/module2"] = "module-v2"
        val second = p.copy(id = "future-build", kernelSu = p.kernelSu!!.copy(module = artifact("test/module2", "module-v2")))
        val (other, cli) = store.prepare(second)
        assertNotEquals(first, other)
        assertEquals("module-v1", File(first).readText())
        assertEquals("module-v2", File(other).readText())
        assertTrue(File(cli).canExecute())
    }

    @Test fun operationRoutingPreservesProbeRootAndLifecycleModes() {
        val p = profile()
        val selection = FirmwareSelection(p.identity, p)
        val backend = FakeBackend()
        val runner = FirmwareRunner(assets(), backend)
        runner.run(selection, Operation.PROBE)
        runner.run(selection, Operation.ROOT)
        assertEquals(0, assetReads)
        runner.run(selection, Operation.MODULE_SAFE)
        runner.run(selection, Operation.MODULE)
        assertEquals(listOf("probe:${p.root.backend}", "root:${p.root.backend}:false:null:null",
            "root:${p.kernelSu!!.backend}:false:module-v1:ksud-v1", "root:${p.kernelSu!!.backend}:true:module-v1:ksud-v1"), backend.calls)
    }

    @Test fun additionalProfileSelectsItsOwnBackendAndPayloadWithoutChangingTheRunner() {
        val p = profile()
        contents["test/future-module"] = "module-v2"
        val future = p.copy(id = "future-build", root = RootSupport("future-root-backend"),
            identity = p.identity.copy(incremental = "future"),
            kernelSu = p.kernelSu!!.copy(backend = "future-backend", module = artifact("test/future-module", "module-v2")))
        val selection = FirmwareCatalog(listOf(p, future)).select(future.identity)
        val backend = FakeBackend()
        FirmwareRunner(assets(), backend).run(selection, Operation.MODULE_SAFE)
        assertEquals(listOf("root:future-backend:false:module-v2:ksud-v1"), backend.calls)
    }

    @Test fun catalogCanDeclareRootWithoutAnyKernelSuArtifacts() {
        for (explicitNull in listOf(false, true)) {
            val catalog = JSONObject(catalogText)
            val entry = catalog.getJSONArray("profiles").getJSONObject(0)
            if (explicitNull) entry.put("kernelSu", JSONObject.NULL) else entry.remove("kernelSu")
            val profile = FirmwareEnvironment.parseCatalog(catalog.toString()).profiles.single { it.id == "chiron-5000001307" }
            assertNull(profile.kernelSu)
            val selection = FirmwareCatalog(listOf(profile)).select(profile.identity)
            assertEquals(FirmwareSupport.ROOT_ONLY, selection.support)
            assertTrue(selection.support.canRoot)
            assertFalse(selection.support.canLoadKernelSu)
        }
    }

    @Test fun partialKernelSuDeclarationIsAnErrorNotSilentRootOnlySupport() {
        val catalog = JSONObject(catalogText)
        catalog.getJSONArray("profiles").getJSONObject(0).getJSONObject("kernelSu").remove("ksud")
        assertThrows(org.json.JSONException::class.java) { FirmwareEnvironment.parseCatalog(catalog.toString()) }
    }

    @Test fun rootOnlyProbeAndRootRunWithoutOpeningAssetsOrInvokingManager() {
        val p = profile().copy(kernelSu = null)
        val backend = FakeBackend()
        val runner = FirmwareRunner(assets(), backend)
        contents.clear() // No module or CLI exists in this installation.
        val selection = FirmwareSelection(p.identity, p)
        val probe = runner.run(selection, Operation.PROBE)
        val root = runner.run(selection, Operation.ROOT)
        assertEquals(Outcome.FINISHED, ReportResult.parse(Operation.PROBE, probe).outcome)
        assertEquals(Outcome.ROOT_READY, ReportResult.parse(Operation.ROOT, root).outcome)
        assertFalse(ManagerInstaller.shouldRun(Operation.ROOT, root))
        assertEquals(listOf("probe:${p.root.backend}", "root:${p.root.backend}:false:null:null"), backend.calls)
        assertEquals(0, assetReads)
        assertFalse(File(temporary.root, "firmware").exists())
    }

    @Test fun rootOnlyRejectsBothModuleModesBeforeNativeIncludingBootMode() {
        val p = profile().copy(kernelSu = null)
        val backend = FakeBackend()
        val runner = FirmwareRunner(assets(), backend)
        for (operation in listOf(Operation.MODULE, Operation.MODULE_SAFE)) {
            val report = runner.run(FirmwareSelection(p.identity, p), operation)
            assertTrue(report.contains("KernelSU is not supported"))
            val result = ReportResult.parse(operation, report)
            assertEquals(Outcome.ERROR, result.outcome)
            assertFalse(result.kernelTouched)
            assertFalse(result.canRetry(report)) // LoadService uses MODULE and this retry decision.
            assertFalse(ManagerInstaller.shouldRun(operation, report))
        }
        assertEquals(0, backend.supportsCalls)
        assertEquals(0, assetReads)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun rootDoesNotDependOnKernelSuBackendOrPayloadAvailability() {
        val p = profile().copy(kernelSu = profile().kernelSu!!.copy(backend = "missing-ksu-backend"))
        val backend = FakeBackend().apply { unavailableIds += "missing-ksu-backend" }
        val runner = FirmwareRunner(assets(), backend)
        contents.clear()
        val selection = FirmwareSelection(p.identity, p)
        runner.run(selection, Operation.PROBE)
        runner.run(selection, Operation.ROOT)
        val report = runner.run(selection, Operation.MODULE)
        assertTrue(report.contains("Native backend missing: missing-ksu-backend"))
        assertEquals(listOf("probe:${p.root.backend}", "root:${p.root.backend}:false:null:null"), backend.calls)
        assertEquals(0, assetReads)
    }

    @Test fun verifiedBuildsCanShareRootBackendWithDifferentKernelSuSupport() {
        val full = profile()
        val rootOnly = full.copy(id = "another-build", identity = full.identity.copy(incremental = "another"), kernelSu = null)
        val catalog = FirmwareCatalog(listOf(full, rootOnly))
        val backend = FakeBackend()
        val runner = FirmwareRunner(assets(), backend)
        runner.run(catalog.select(full.identity), Operation.ROOT)
        runner.run(catalog.select(rootOnly.identity), Operation.ROOT)
        assertEquals(listOf("root:${full.root.backend}:false:null:null", "root:${full.root.backend}:false:null:null"), backend.calls)
        assertEquals(FirmwareSupport.ROOT_AND_KERNEL_SU, catalog.select(full.identity).support)
        assertEquals(FirmwareSupport.ROOT_ONLY, catalog.select(rootOnly.identity).support)
        assertEquals(0, assetReads)
    }
}
