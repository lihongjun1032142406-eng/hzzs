package top.azek431.hzzs.core.algorithm.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.algorithm.AlgorithmCardStatus
import top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase
import top.azek431.hzzs.core.algorithm.AlgorithmDownloadSource
import top.azek431.hzzs.core.algorithm.AlgorithmIds
import top.azek431.hzzs.core.algorithm.AlgorithmOrigin
import top.azek431.hzzs.core.algorithm.AlgorithmPackageInfo
import top.azek431.hzzs.core.algorithm.InstalledAlgorithmStore
import top.azek431.hzzs.core.algorithm.statusAgainst
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.update.UpdateSourceId

/**
 * [AlgorithmCatalogPure] 纯函数单测。
 *
 * 覆盖算法目录核心决策：resolveActive / mergeInstalled / sort / planUpgrades /
 * computePending / versionToCode / builtinPackages / catalogPhaseAfter /
 * mergeDiskInstalled / parseCatalog / statusAgainst 推导。
 *
 * 所有测试均为 JVM 纯函数测试，无 Android 依赖。
 */
class AlgorithmCatalogPureTest {

    // ---- 测试夹具 ----

    private fun builtin(): AlgorithmPackageInfo =
        AlgorithmCatalogPure.builtinPackages().first()

    private fun net(
        id: String,
        versionCode: Long,
        scenes: Set<SceneId> = setOf(SceneId.SEA_SALT_LIVING_ROOM),
        compatible: Boolean = true,
        isInstalled: Boolean = true,
    ): AlgorithmPackageInfo = AlgorithmPackageInfo(
        id = id,
        name = id,
        versionName = "v$versionCode",
        versionCode = versionCode,
        channel = AlgorithmChannel.STABLE,
        summary = "test",
        supportedScenes = scenes,
        minAppVersionCode = 1L,
        publishedAtEpochMs = 0L,
        sizeBytes = 0L,
        origin = if (isInstalled) AlgorithmOrigin.INSTALLED else AlgorithmOrigin.REMOTE,
        downloadSource = if (isInstalled) AlgorithmDownloadSource.CACHE else AlgorithmDownloadSource.GITHUB,
        isInstalled = isInstalled,
        isCompatible = compatible,
    )

    // ---- resolveActive ----

    @Test
    fun resolveActive_manual_returnsPinnedWhenCompatible() {
        val installed = listOf(builtin(), net("x", 1000L), net("y", 2000L))
        val active = AlgorithmCatalogPure.resolveActive(installed, pinned = "x", mode = AlgorithmSelectionMode.MANUAL, scene = SceneId.SEA_SALT_LIVING_ROOM)
        assertEquals("x", active?.id)
    }

    @Test
    fun resolveActive_manual_picksBuiltinWhenPinnedMissing() {
        val installed = listOf(builtin(), net("x", 1000L))
        val active = AlgorithmCatalogPure.resolveActive(installed, pinned = "missing", mode = AlgorithmSelectionMode.MANUAL, scene = SceneId.SEA_SALT_LIVING_ROOM)
        assertEquals(AlgorithmIds.BUILTIN_CATALOG_ID, active?.id)
    }

    @Test
    fun resolveActive_manual_skipsIncompatiblePinned() {
        val installed = listOf(builtin(), net("bad", 1000L, compatible = false))
        val active = AlgorithmCatalogPure.resolveActive(installed, pinned = "bad", mode = AlgorithmSelectionMode.MANUAL, scene = SceneId.SEA_SALT_LIVING_ROOM)
        assertEquals(AlgorithmIds.BUILTIN_CATALOG_ID, active?.id)
    }

    @Test
    fun resolveActive_auto_returnsLatestCompatibleForScene() {
        val installed = listOf(
            builtin(),
            net("a", 1000L, scenes = setOf(SceneId.BAMBOO_BOOKSTORE)),
            net("b", 3000L, scenes = setOf(SceneId.SEA_SALT_LIVING_ROOM)),
            net("c", 2000L, scenes = setOf(SceneId.SEA_SALT_LIVING_ROOM)),
        )
        val active = AlgorithmCatalogPure.resolveActive(installed, pinned = null, mode = AlgorithmSelectionMode.AUTO, scene = SceneId.SEA_SALT_LIVING_ROOM)
        assertEquals("b", active?.id)
    }

    @Test
    fun resolveActive_auto_fallsBackToBuiltinWhenNoSceneMatch() {
        val installed = listOf(builtin(), net("a", 1000L, scenes = setOf(SceneId.BAMBOO_BOOKSTORE)))
        val active = AlgorithmCatalogPure.resolveActive(installed, pinned = null, mode = AlgorithmSelectionMode.AUTO, scene = SceneId.SEA_SALT_LIVING_ROOM)
        assertEquals(AlgorithmIds.BUILTIN_CATALOG_ID, active?.id)
    }

    // ---- mergeInstalled ----

    @Test
    fun mergeInstalled_bundledOverridesRemote() {
        val current = listOf(net("x", 1000L).copy(origin = AlgorithmOrigin.REMOTE, isInstalled = false))
        val extras = listOf(net("x", 1000L).copy(origin = AlgorithmOrigin.BUNDLED, isInstalled = true))
        val merged = AlgorithmCatalogPure.mergeInstalled(current, extras)
        val x = merged.first { it.id == "x" }
        assertEquals(AlgorithmOrigin.BUNDLED, x.origin)
        assertTrue(x.isInstalled)
    }

    @Test
    fun mergeInstalled_preservesBuiltinAlways() {
        val current = emptyList<AlgorithmPackageInfo>()
        val extras = emptyList<AlgorithmPackageInfo>()
        val merged = AlgorithmCatalogPure.mergeInstalled(current, extras)
        assertTrue(merged.any { it.id == AlgorithmIds.BUILTIN_CATALOG_ID })
    }

    @Test
    fun mergeInstalled_keepsCurrentWhenNotInExtras() {
        val current = listOf(net("x", 1000L))
        val extras = listOf(net("y", 2000L))
        val merged = AlgorithmCatalogPure.mergeInstalled(current, extras)
        assertEquals(AlgorithmCatalogPure.builtinPackages().size + 2, merged.size)
        assertTrue(merged.any { it.id == "x" })
        assertTrue(merged.any { it.id == "y" })
    }

    // ---- sort ----

    @Test
    fun sortInstalled_putsActiveFirst() {
        val list = listOf(net("a", 3000L), net("b", 1000L), net("c", 2000L))
        val sorted = list.sortedWith(AlgorithmCatalogPure.sortInstalled(list, activeId = "b", scene = SceneId.SEA_SALT_LIVING_ROOM))
        assertEquals("b", sorted.first().id)
    }

    @Test
    fun sortRemote_putsIncompatibleLast() {
        val list = listOf(
            net("a", 1000L, compatible = false),
            net("b", 500L, compatible = true),
        )
        val sorted = list.sortedWith(AlgorithmCatalogPure.sortRemote(list, SceneId.SEA_SALT_LIVING_ROOM))
        assertEquals("b", sorted.first().id)
    }

    // ---- planUpgrades ----

    @Test
    fun planUpgrades_skipsBundledAndBuiltin() {
        val installed = listOf(
            builtin().copy(id = "builtin-x", origin = AlgorithmOrigin.BUILTIN, isBuiltin = true),
            net("b", 1000L).copy(origin = AlgorithmOrigin.BUNDLED),
        )
        val remote = emptyList<AlgorithmPackageInfo>()
        val plan = AlgorithmCatalogPure.planUpgrades(installed, remote)
        assertTrue(plan.candidates.isEmpty())
        assertEquals(2, plan.skipped.size)
    }

    @Test
    fun planUpgrades_candidatesHigherRemote() {
        val installed = listOf(net("x", 1000L))
        val remote = listOf(net("x", 2000L))
        val plan = AlgorithmCatalogPure.planUpgrades(installed, remote)
        assertEquals(listOf("x"), plan.candidates)
    }

    @Test
    fun planUpgrades_skipsWhenSameVersion() {
        val installed = listOf(net("x", 1000L))
        val remote = listOf(net("x", 1000L))
        val plan = AlgorithmCatalogPure.planUpgrades(installed, remote)
        assertTrue(plan.candidates.isEmpty())
        assertTrue(plan.skipped.contains("x"))
    }

    // ---- computePending ----

    @Test
    fun computePending_manualAndAnalysisRunning_returnsPending() {
        val pending = net("x", 1000L)
        val result = AlgorithmCatalogPure.computePending(
            pendingFromUi = pending,
            activeId = "builtin",
            pinnedId = "x",
            mode = AlgorithmSelectionMode.MANUAL,
            analysisRunning = true,
        )
        assertEquals(pending, result)
    }

    @Test
    fun computePending_manualNotRunningAndActiveDiffers_returnsPending() {
        val pending = net("x", 1000L)
        val result = AlgorithmCatalogPure.computePending(
            pendingFromUi = pending,
            activeId = "builtin",
            pinnedId = "x",
            mode = AlgorithmSelectionMode.MANUAL,
            analysisRunning = false,
        )
        assertEquals(pending, result)
    }

    @Test
    fun computePending_manualNotRunningAndActiveSame_clearsPending() {
        val pending = net("x", 1000L)
        val result = AlgorithmCatalogPure.computePending(
            pendingFromUi = pending,
            activeId = "x",
            pinnedId = "x",
            mode = AlgorithmSelectionMode.MANUAL,
            analysisRunning = false,
        )
        assertNull(result)
    }

    @Test
    fun computePending_auto_returnsNull() {
        val pending = net("x", 1000L)
        val result = AlgorithmCatalogPure.computePending(
            pendingFromUi = pending,
            activeId = "builtin",
            pinnedId = "x",
            mode = AlgorithmSelectionMode.AUTO,
            analysisRunning = true,
        )
        assertNull(result)
    }

    @Test
    fun computePending_nullPending_returnsNull() {
        val result = AlgorithmCatalogPure.computePending(
            pendingFromUi = null,
            activeId = "x",
            pinnedId = "x",
            mode = AlgorithmSelectionMode.MANUAL,
            analysisRunning = true,
        )
        assertNull(result)
    }

    // ---- versionToCode ----

    @Test
    fun versionToCode_parsesCore() {
        assertEquals(1_000L, AlgorithmCatalogPure.versionToCode("0.1.0"))
        assertEquals(1_002L, AlgorithmCatalogPure.versionToCode("0.1.2"))
        assertEquals(2_001_003L, AlgorithmCatalogPure.versionToCode("2.1.3"))
    }

    @Test
    fun versionToCode_ignoresPrereleaseAndMetadata() {
        assertEquals(2_001_003L, AlgorithmCatalogPure.versionToCode("2.1.3-beta.1"))
        assertEquals(1_000_000L, AlgorithmCatalogPure.versionToCode("1.0.0+build2026"))
    }

    @Test
    fun versionToCode_returnsZeroWhenInvalid() {
        assertEquals(0L, AlgorithmCatalogPure.versionToCode("1.0"))
        assertEquals(0L, AlgorithmCatalogPure.versionToCode("abc"))
    }

    // ---- builtinPackages ----

    @Test
    fun builtinPackages_containsBaseAlignedWithIds() {
        val list = AlgorithmCatalogPure.builtinPackages()
        assertEquals(2, list.size)
        val base = list.first { it.id == AlgorithmIds.BUILTIN_CATALOG_ID }
        assertEquals(AlgorithmIds.BUILTIN_VERSION, base.versionName)
        assertTrue(base.isBuiltin)
        assertEquals(1_000L, base.versionCode)
        assertEquals(AlgorithmDownloadSource.BUILTIN, base.downloadSource)
    }

    // ---- catalogPhaseAfter ----

    @Test
    fun catalogPhaseAfter_preservesDownloading() {
        val loading = AlgorithmCatalogPhase.Downloading("x", 0.5f)
        val out = AlgorithmCatalogPure.catalogPhaseAfter(
            current = loading,
            remoteInfos = emptyList(),
            installed = emptyList(),
            catalog = null,
        )
        assertEquals(loading, out)
    }

    @Test
    fun catalogPhaseAfter_emptyWhenNoData() {
        val out = AlgorithmCatalogPure.catalogPhaseAfter(
            current = AlgorithmCatalogPhase.Idle,
            remoteInfos = emptyList(),
            installed = emptyList(),
            catalog = null,
        )
        assertTrue(out is AlgorithmCatalogPhase.Empty)
    }

    @Test
    fun catalogPhaseAfter_mirrorFallbackWhenUsed() {
        val out = AlgorithmCatalogPure.catalogPhaseAfter(
            current = AlgorithmCatalogPhase.Idle,
            remoteInfos = listOf(net("x", 1000L)),
            installed = emptyList(),
            catalog = AlgorithmCatalogPure.RemoteCatalogMeta(
                activeSource = UpdateSourceId.GITHUB,
                usedFallback = true,
                fallbackReason = "首选源不可达",
                message = null,
            ),
        )
        assertTrue(out is AlgorithmCatalogPhase.MirrorFallback)
    }

    @Test
    fun catalogPhaseAfter_idleWhenDataPresent() {
        val out = AlgorithmCatalogPure.catalogPhaseAfter(
            current = AlgorithmCatalogPhase.Idle,
            remoteInfos = listOf(net("x", 1000L)),
            installed = emptyList(),
            catalog = AlgorithmCatalogPure.RemoteCatalogMeta(
                activeSource = UpdateSourceId.GITEE,
                usedFallback = false,
                fallbackReason = null,
                message = null,
            ),
        )
        assertTrue(out is AlgorithmCatalogPhase.Idle)
    }

    // ---- mergeDiskInstalled ----

    @Test
    fun mergeDiskInstalled_mapsRecords() {
        val record = InstalledAlgorithmStore.InstalledAlgorithmRecord(
            catalogId = "net-sample-v1",
            runtimeId = "pack.net-sample-v1",
            version = "0.1.0",
            versionCode = 100L,
            displayName = "Sample",
            supportedScenes = setOf(SceneId.BAMBOO_BOOKSTORE),
            profile = top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile.builtin(),
            directory = java.io.File("."),
            installedAtEpochMs = 1234L,
            sha256 = "abc",
            author = "Tester",
            summary = "desc",
            channelName = "beta",
            originTag = "network",
        )
        val list = AlgorithmCatalogPure.mergeDiskInstalled(listOf(record))
        assertEquals(1, list.size)
        val item = list.first()
        assertEquals("net-sample-v1", item.id)
        assertEquals(AlgorithmOrigin.INSTALLED, item.origin)
        assertTrue(item.isInstalled)
        assertTrue(item.author == "Tester")
        assertEquals(1234L, item.publishedAtEpochMs)
    }

    @Test
    fun mergeDiskInstalled_bundledOrigin() {
        val record = InstalledAlgorithmStore.InstalledAlgorithmRecord(
            catalogId = "bundled-sample-v1",
            runtimeId = "pack.bundled-sample-v1",
            version = "0.1.0",
            versionCode = 100L,
            displayName = "Bundled",
            supportedScenes = emptySet(),
            profile = top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile.builtin(),
            directory = java.io.File("."),
            installedAtEpochMs = 0L,
            originTag = "bundled",
        )
        val list = AlgorithmCatalogPure.mergeDiskInstalled(listOf(record))
        assertEquals(AlgorithmOrigin.BUNDLED, list.first().origin)
    }

    // ---- previousOf ----

    @Test
    fun previousOf_returnsHighestNonBuiltinNotActive() {
        val installed = listOf(
            builtin(),
            net("a", 3000L),
            net("b", 2000L),
        )
        val prev = AlgorithmCatalogPure.previousOf(installed, activeId = "a")
        assertEquals("b", prev?.id)
    }

    @Test
    fun previousOf_fallbackToBuiltinWhenOnlyOneNonBuiltin() {
        val installed = listOf(builtin(), net("a", 1000L))
        val prev = AlgorithmCatalogPure.previousOf(installed, activeId = "a")
        assertEquals(AlgorithmIds.BUILTIN_CATALOG_ID, prev?.id)
    }

    @Test
    fun previousOf_returnsNullWhenOnlyActive() {
        val installed = listOf(net("a", 1000L))
        val prev = AlgorithmCatalogPure.previousOf(installed, activeId = "a")
        assertNull(prev)
    }

    // ---- parseCatalog ----

    @Test
    fun parseCatalog_returnsCatalogRemoteEntriesWithInfo() {
        val raw = """
            {
              "schemaVersion": 1,
              "algorithms": [
                {
                  "id": "official-bamboo-baseline",
                  "version": "0.1.0",
                  "filename": "official-bamboo-baseline-v0.1.0.hzzsalg",
                  "assetPath": "algorithms/packages/official-bamboo-baseline-v0.1.0.hzzsalg",
                  "size": 1234,
                  "sha256": "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234",
                  "displayName": "Bamboo Baseline",
                  "supportedScenes": ["BAMBOO_BOOKSTORE"]
                }
              ]
            }
        """.trimIndent()
        val out = AlgorithmCatalogPure.parseCatalog(
            raw = raw,
            channel = top.azek431.hzzs.core.model.AlgorithmChannel.STABLE,
            source = UpdateSourceId.GITHUB,
            appVersionCode = 1L,
        )
        assertEquals(1, out.size)
        val entry = out.first()
        assertEquals("official-bamboo-baseline", entry.info.id)
        assertEquals(1_000L, entry.info.versionCode)
        assertTrue(entry.info.supportedScenes.contains(SceneId.BAMBOO_BOOKSTORE))
        assertEquals("algorithms/packages/official-bamboo-baseline-v0.1.0.hzzsalg", entry.assetPath)
    }

    @Test
    fun parseCatalog_parsesRemoteEntryWithoutSignature() {
        val raw = """
            {
              "schemaVersion": 1,
              "algorithms": [
                {
                  "id": "x-sample-v1",
                  "version": "0.1.0",
                  "filename": "x-sample-v1.hzzsalg",
                  "size": 100,
                  "sha256": "0011223344556677889900112233445566778899001122334455667788990011"
                }
              ]
            }
        """.trimIndent()
        val out = AlgorithmCatalogPure.parseCatalog(
            raw = raw,
            channel = top.azek431.hzzs.core.model.AlgorithmChannel.BETA,
            source = UpdateSourceId.GITEE,
            appVersionCode = 1L,
        )
        assertEquals(1, out.size)
        // 远端包 info 正常解析（签名验证已移除）。
        assertNotNull(out.first().info)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseCatalog_rejectsUnknownSchema() {
        val raw = """{"schemaVersion": 99, "algorithms": []}"""
        AlgorithmCatalogPure.parseCatalog(
            raw = raw,
            channel = top.azek431.hzzs.core.model.AlgorithmChannel.STABLE,
            source = UpdateSourceId.GITEE,
            appVersionCode = 1L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseCatalog_rejectsInvalidId() {
        val raw = """
            {
              "schemaVersion": 1,
              "algorithms": [
                {
                  "id": "INVALID",
                  "version": "0.1.0",
                  "filename": "x.hzzsalg",
                  "size": 100,
                  "sha256": "0011223344556677889900112233445566778899001122334455667788990011"
                }
              ]
            }
        """.trimIndent()
        AlgorithmCatalogPure.parseCatalog(
            raw = raw,
            channel = top.azek431.hzzs.core.model.AlgorithmChannel.STABLE,
            source = UpdateSourceId.GITEE,
            appVersionCode = 1L,
        )
    }

    // ---- statusAgainst 优先级 ----

    @Test
    fun statusAgainst_incompatibleWins() {
        val info = net("x", 1000L, compatible = false)
        val s = info.statusAgainst(activeId = "x", pendingId = "x", latestCompatibleId = "x")
        assertEquals(AlgorithmCardStatus.INCOMPATIBLE, s)
    }

    @Test
    fun statusAgainst_pendingWinsOverActive() {
        val info = net("x", 1000L)
        val s = info.statusAgainst(activeId = "x", pendingId = "x", latestCompatibleId = "x")
        assertEquals(AlgorithmCardStatus.PENDING_ACTIVATION, s)
    }

    @Test
    fun statusAgainst_currentWinsOverLatest() {
        val info = net("x", 1000L, isInstalled = true)
        val s = info.statusAgainst(activeId = "x", pendingId = null, latestCompatibleId = "x")
        assertEquals(AlgorithmCardStatus.CURRENT, s)
    }

    @Test
    fun statusAgainst_downloadableWhenRemoteNewer() {
        val info = net("x", 1000L, isInstalled = false)
        val s = info.statusAgainst(activeId = "y", pendingId = null, latestCompatibleId = null, activeVersionCode = 500L)
        assertEquals(AlgorithmCardStatus.UPDATABLE, s)
    }

    // ---- 安全常量 ----

    @Test
    fun safeId_acceptsValidAndRejectsInvalid() {
        assertTrue(AlgorithmCatalogPure.SAFE_ID.matches("official-bamboo-baseline"))
        assertFalse(AlgorithmCatalogPure.SAFE_ID.matches("Invalid"))
        assertFalse(AlgorithmCatalogPure.SAFE_ID.matches("a"))
    }

    @Test
    fun safeAssetPath_acceptsOnlyAlgorithmsPackagesSingleFile() {
        assertTrue(AlgorithmCatalogPure.SAFE_ASSET_PATH.matches("algorithms/packages/foo-v1.0.0.hzzsalg"))
        assertFalse(AlgorithmCatalogPure.SAFE_ASSET_PATH.matches("algorithms/packages/../etc/passwd"))
        assertFalse(AlgorithmCatalogPure.SAFE_ASSET_PATH.matches("other/foo.hzzsalg"))
    }

    @Test
    fun safeSha256_requires64HexChars() {
        assertTrue(AlgorithmCatalogPure.SAFE_SHA256.matches("abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"))
        assertFalse(AlgorithmCatalogPure.SAFE_SHA256.matches("abcd"))
        assertFalse(AlgorithmCatalogPure.SAFE_SHA256.matches("abcd1234xyz"))
    }
}
