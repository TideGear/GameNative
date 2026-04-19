package app.gamenative.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import app.gamenative.PrefManager
import app.gamenative.data.DepotInfo
import app.gamenative.data.LaunchInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.data.SteamApp
import app.gamenative.enums.Marker
import app.gamenative.enums.SpecialGameSaveMapping
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.getAppDirName
import app.gamenative.service.SteamService.Companion.getAppInfoOf
import com.winlator.container.Container
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.ImageFs
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.util.HardwareUtils
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import timber.log.Timber
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.setLastModifiedTime

/**
 * Shared SteamFix diagnostics. Written from onWindowMapped so the stall
 * watchdog (and any other late-firing diagnostic) can name *what* was on
 * screen at the moment it gave up. A null value means "nothing mapped yet."
 */
object SteamFixDiagnostics {
    @Volatile var lastMappedWindowClass: String? = null
    @Volatile var lastUnmatchedWindowClass: String? = null
    @Volatile var unmatchedWindowCount: Int = 0

    /**
     * The window class of the last mapped window that matched a known launch
     * config (i.e. the game window itself). Survives later noise from helper
     * windows, so the stall watchdog can distinguish "game never appeared"
     * from "game appeared, then something else grabbed focus."
     */
    @Volatile var gameWindowMapped: Boolean = false
    @Volatile var gameWindowClass: String? = null

    fun reset() {
        lastMappedWindowClass = null
        lastUnmatchedWindowClass = null
        unmatchedWindowCount = 0
        gameWindowMapped = false
        gameWindowClass = null
    }
}

/**
 * Window classes we know are Steam's own bootstrapper/helper windows.
 * Seeing these mapped is normal and does not indicate a modal; log at debug.
 * Anything else in real-Steam mode is more suspicious (login prompt, cloud
 * conflict, update-required dialog) and is worth warning about.
 */
val STEAM_HELPER_WINDOW_CLASSES: Set<String> = setOf(
    "",
    "explorer.exe",
    "steam.exe",
    "steamwebhelper.exe",
    "conhost.exe",
    "winhandler.exe",
    "gameoverlayui.exe",
    "steamerrorreporter.exe",
    "steamerrorreporter64.exe",
    "steamservice.exe",
)

object SteamUtils {

    fun getDownloadBytes(manifest: ManifestInfo?): Long {
        if (manifest == null) return 0L
        return if (manifest.download > 0L) manifest.download else manifest.size
    }

    internal val http = Net.http.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val sfd by lazy {
        SimpleDateFormat("MMM d - h:mm a", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    /**
     * Converts steam time to actual time
     * @return a string in the 'MMM d - h:mm a' format.
     */
    // Note: Mostly correct, has a slight skew when near another minute
    fun fromSteamTime(rtime: Int): String = sfd.format(rtime * 1000L)

    /**
     * Converts steam time from the playtime of a friend into an approximate double representing hours.
     * @return A string representing how many hours were played, ie: 1.5 hrs
     */
    fun formatPlayTime(time: Int): String {
        val hours = time / 60.0
        return if (hours % 1 == 0.0) {
            hours.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", time / 60.0)
        }
    }

    // Steam strips all non-ASCII characters from usernames and passwords
    // source: https://github.com/steevp/UpdogFarmer/blob/8f2d185c7260bc2d2c92d66b81f565188f2c1a0e/app/src/main/java/com/steevsapps/idledaddy/LoginActivity.java#L166C9-L168C104
    // more: https://github.com/winauth/winauth/issues/368#issuecomment-224631002
    /**
     * Strips non-ASCII characters from String
     */
    fun removeSpecialChars(s: String): String = s.replace(Regex("[^\\u0000-\\u007F]"), "")

    private fun generateInterfacesFile(dllPath: Path) {
        val outFile = dllPath.parent.resolve("steam_interfaces.txt")
        if (Files.exists(outFile)) return          // already generated on a previous boot

        // -------- read DLL into memory ----------------------------------------
        val bytes = Files.readAllBytes(dllPath)
        val strings = mutableSetOf<String>()

        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= 10) {                 // only consider reasonably long strings
                val candidate = sb.toString()
                if (candidate.matches(Regex("^Steam[A-Za-z]+[0-9]{3}\$", RegexOption.IGNORE_CASE)))
                    strings += candidate
            }
            sb.setLength(0)
        }

        for (b in bytes) {
            val ch = b.toInt() and 0xFF
            if (ch in 0x20..0x7E) {                // printable ASCII
                sb.append(ch.toChar())
            } else {
                flush()
            }
        }
        flush()                                    // catch trailing string

        if (strings.isEmpty()) {
            Timber.w("No Steam interface strings found in ${dllPath.fileName}")
            return
        }

        val sorted = strings.sorted()
        Files.write(outFile, sorted)
        Timber.i("Generated steam_interfaces.txt (${sorted.size} interfaces)")
    }

    private fun copyOriginalSteamDll(dllPath: Path, appDirPath: String): String? {
        // 1️⃣  back-up next to the original DLL
        val backup = dllPath.parent.resolve("${dllPath.fileName}.orig")
        if (Files.notExists(backup)) {
            try {
                Files.copy(dllPath, backup)
                Timber.i("Copied original ${dllPath.fileName} to $backup")
            } catch (e: IOException) {
                Timber.w(e, "Failed to back up ${dllPath.fileName}")
                return null
            }
        }
        // 2️⃣  return the relative path inside the app directory (even if backup already existed)
        return try {
            val relPath = Paths.get(appDirPath).relativize(backup)
            relPath.toString()
        } catch (e: Exception) {
            Timber.w(e, "Failed to compute relative path for ${dllPath.fileName}")
            null
        }
    }

    /**
     * SteamFix #7: hash-verify that the steam_api DLL currently on disk matches
     * the pipe DLL shipped in assets. An interrupted launch can desync the marker
     * (marker says "replaced" but DLLs are actually original, or vice versa).
     */
    private fun verifyReplacedState(context: Context, appDirPath: String): Boolean {
        return try {
            val assetHashes = mutableMapOf<String, String>()
            listOf("steam_api.dll", "steam_api64.dll").forEach { name ->
                runCatching {
                    context.assets.open("steampipe/$name").use { ins ->
                        assetHashes[name.lowercase()] = sha256OfStream(ins)
                    }
                }
            }
            var found = false
            Paths.get(appDirPath).toFile().walkTopDown().maxDepth(10).forEach { file ->
                if (!file.isFile) return@forEach
                val n = file.name.lowercase()
                if (n == "steam_api.dll" || n == "steam_api64.dll") {
                    found = true
                    val expected = assetHashes[n] ?: return@forEach
                    val actual = sha256OfFile(file)
                    if (actual != expected) {
                        Timber.tag("SteamFix").w("DLL marker desync: %s hash mismatch (marker says REPLACED)", file.absolutePath)
                        return false
                    }
                }
            }
            if (!found) {
                Timber.tag("SteamFix").w("DLL marker desync: no steam_api DLL found at $appDirPath but REPLACED marker present")
                return false
            }
            true
        } catch (e: Exception) {
            Timber.tag("SteamFix").w(e, "verifyReplacedState failed, treating as desync")
            false
        }
    }

    /**
     * SteamFix #7: verify RESTORED marker. Each steam_api DLL must have a .orig
     * sibling and match it byte-for-byte.
     */
    private fun verifyRestoredState(appDirPath: String): Boolean {
        return try {
            Paths.get(appDirPath).toFile().walkTopDown().maxDepth(10).forEach { file ->
                if (!file.isFile) return@forEach
                val n = file.name.lowercase()
                if (n == "steam_api.dll" || n == "steam_api64.dll") {
                    val orig = File(file.parentFile, "${file.name}.orig")
                    if (!orig.exists()) {
                        Timber.tag("SteamFix").w("DLL marker desync: %s has no .orig sibling (marker says RESTORED)", file.absolutePath)
                        return false
                    }
                    if (sha256OfFile(file) != sha256OfFile(orig)) {
                        Timber.tag("SteamFix").w("DLL marker desync: %s != %s.orig (marker says RESTORED)", file.absolutePath, file.name)
                        return false
                    }
                }
            }
            true
        } catch (e: Exception) {
            Timber.tag("SteamFix").w(e, "verifyRestoredState failed, treating as desync")
            false
        }
    }

    private fun sha256OfFile(file: File): String {
        file.inputStream().use { return sha256OfStream(it) }
    }

    private fun sha256OfStream(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Replaces any existing `steam_api.dll` or `steam_api64.dll` in the app directory
     * with our pipe dll stored in assets
     */
    suspend fun replaceSteamApi(context: Context, appId: String, isOffline: Boolean = false) {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val appDirPath = SteamService.getAppDirPath(steamAppId)
        Timber.tag("SteamFix").i("replaceSteamApi: appId=%s dir=%s", appId, appDirPath)
        if (MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED)) {
            if (verifyReplacedState(context, appDirPath)) {
                Timber.tag("SteamFix").i("replaceSteamApi: marker + hash ok, skipping")
                return
            }
            Timber.tag("SteamFix").w("replaceSteamApi: clearing stale REPLACED marker and re-running swap")
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        }
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
        Timber.i("Starting replaceSteamApi for appId: $appId")
        Timber.i("Checking directory: $appDirPath")
        var replaced32Count = 0
        var replaced64Count = 0
        val backupPaths = mutableSetOf<String>()
        val imageFs = ImageFs.find(context)
        autoLoginUserChanges(imageFs)
        setupLightweightSteamConfig(imageFs, SteamService.userSteamId?.toString())

        val rootPath = Paths.get(appDirPath)
        // Get ticket once for all DLLs
        val ticketBase64 = SteamService.instance?.getEncryptedAppTicketBase64(steamAppId)

        rootPath.toFile().walkTopDown().maxDepth(10).forEach { file ->
            val path = file.toPath()
            if (!file.isFile || !path.name.startsWith("steam_api", ignoreCase = true)) return@forEach

            val is64Bit = path.name.equals("steam_api64.dll", ignoreCase = true)
            val is32Bit = path.name.equals("steam_api.dll", ignoreCase = true)

            if (is64Bit || is32Bit) {
                val dllName = if (is64Bit) "steam_api64.dll" else "steam_api.dll"
                Timber.i("Found $dllName at ${path.absolutePathString()}, replacing...")
                generateInterfacesFile(path)
                val relPath = copyOriginalSteamDll(path, appDirPath)
                if (relPath != null) {
                    backupPaths.add(relPath)
                }
                Files.delete(path)
                Files.createFile(path)
                FileOutputStream(path.absolutePathString()).use { fos ->
                    context.assets.open("steampipe/$dllName").use { fs ->
                        fs.copyTo(fos)
                    }
                }
                Timber.i("Replaced $dllName")
                if (is64Bit) replaced64Count++ else replaced32Count++
                ensureSteamSettings(context, path, appId, ticketBase64, isOffline)
            }
        }

        // Write all collected backup paths to orig_dll_path.txt
        if (backupPaths.isNotEmpty()) {
            try {
                Files.write(
                    Paths.get(appDirPath).resolve("orig_dll_path.txt"),
                    backupPaths.sorted(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                Timber.i("Wrote ${backupPaths.size} DLL backup paths to orig_dll_path.txt")
            } catch (e: IOException) {
                Timber.w(e, "Failed to write orig_dll_path.txt")
            }
        }

        Timber.i("Finished replaceSteamApi for appId: $appId. Replaced 32bit: $replaced32Count, Replaced 64bit: $replaced64Count")

        // Restore unpacked executable if it exists (for DRM-free mode)
        restoreUnpackedExecutable(context, steamAppId)

        // Restore original steamclient.dll files if they exist
        restoreSteamclientFiles(context, steamAppId)

        // Create Steam ACF manifest for real Steam compatibility
        createAppManifest(context, steamAppId)

        // Game-specific Handling
        ensureSaveLocationsForGames(context, steamAppId)

        // Generate achievements.json
        generateAchievementsFile(rootPath.resolve("steam_settings"), appId)

        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        logAppDirInventory(ContainerUtils.extractGameIdFromContainerId(appId), "replaceSteamApi.done")
    }

    /**
     * Replaces any existing `steamclient.dll` or `steamclient64.dll` in the Steam directory
     */
    suspend fun replaceSteamclientDll(context: Context, appId: String, isOffline: Boolean = false) {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val appDirPath = SteamService.getAppDirPath(steamAppId)
        val container = ContainerUtils.getContainer(context, appId)

        if (MarkerUtils.hasMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED) && File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steamclient_loader_x64.dll").exists()) {
            return
        }
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_RESTORED)

        // Make a backup before extracting
        backupSteamclientFiles(context, steamAppId)

        // Delete extra_dlls folder before extraction to prevent conflicts
        val extraDllDir = File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/extra_dlls")
        if (extraDllDir.exists()) {
            extraDllDir.deleteRecursively()
            Timber.i("Deleted extra_dlls directory before extraction for appId: $steamAppId")
        }

        val imageFs = ImageFs.find(context)
        val downloaded = File(imageFs.getFilesDir(), "experimental-drm-20260116.tzst")
        TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD,
            downloaded,
            imageFs.getRootDir(),
        )
        putBackSteamDlls(appDirPath)
        restoreUnpackedExecutable(context, steamAppId)

        // Get ticket and pass to ensureSteamSettings
        val ticketBase64 = SteamService.instance?.getEncryptedAppTicketBase64(steamAppId)
        val path = File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steamclient.dll").toPath()
        ensureSteamSettings(context, path, appId, ticketBase64, isOffline)
        generateAchievementsFile(path, appId)

        // Game-specific Handling
        ensureSaveLocationsForGames(context, steamAppId)

        applySteamOverlayPref(context, container)

        MarkerUtils.addMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
        logAppDirInventory(steamAppId, "replaceSteamclientDll.done")
    }

    fun steamClientFiles() : Array<String> {
        return arrayOf(
            "GameOverlayRenderer.dll",
            "GameOverlayRenderer64.dll",
            "steamclient.dll",
            "steamclient64.dll",
            "steamclient_loader_x32.exe",
            "steamclient_loader_x64.exe",
        )
    }

    fun backupSteamclientFiles(context: Context, steamAppId: Int) {
        val imageFs = ImageFs.find(context)

        var backupCount = 0

        val backupDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steamclient_backup")
        backupDir.mkdirs()

        steamClientFiles().forEach { file ->
            val dll = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/$file")
            if (dll.exists()) {
                Files.copy(dll.toPath(), File(backupDir, "$file.orig").toPath(), StandardCopyOption.REPLACE_EXISTING)
                backupCount++
            }
        }

        Timber.i("Finished backupSteamclientFiles for appId: $steamAppId. Backed up $backupCount file(s)")
    }

    // Every Steam-overlay file a running game can pick up. Rename only the
    // binaries — leave the Vulkan layer JSON manifests in place so Wine's
    // Vulkan loader can parse them, discover the referenced DLL is missing,
    // log a warning, and skip the layer cleanly. Renaming the JSONs instead
    // can race with loader init and stall early Vulkan startup.
    // GameOverlayUI.exe is the in-game UI host (inventory popup, shift-tab
    // overlay) and is safe to stash.
    private val steamOverlayFiles = arrayOf(
        "GameOverlayRenderer.dll",
        "GameOverlayRenderer64.dll",
        "SteamOverlayVulkanLayer.dll",
        "SteamOverlayVulkanLayer64.dll",
        "GameOverlayUI.exe",
    )

    /**
     * Apply the container's disableSteamOverlay setting. Rename-based: when
     * enabled, stash overlay files with a `.disabled` suffix; when disabled,
     * un-stash. Idempotent on both sides, and survives Steam's re-extraction
     * of client files (a fresh client install won't touch the stashed copies).
     * Stashes the D3D renderer DLLs, the Vulkan overlay layer DLLs, and the
     * overlay UI process. Also restores any Vulkan layer manifest JSONs an
     * older build had stashed (see legacyStashedFiles).
     */
    // Files an older build stashed that we no longer want to keep hidden.
    // Always restore these if a `.disabled` copy exists — covers upgrade from
    // the previous overlay-disable logic that renamed the Vulkan layer JSONs.
    private val legacyStashedFiles = arrayOf(
        "SteamOverlayVulkanLayer.json",
        "SteamOverlayVulkanLayer64.json",
    )

    fun applySteamOverlayPref(context: Context, container: com.winlator.container.Container) {
        val steamDir = File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam")

        var legacyRestored = 0
        legacyStashedFiles.forEach { name ->
            val live = File(steamDir, name)
            val stashedFile = File(steamDir, "$name.disabled")
            if (stashedFile.exists() && !live.exists()) {
                if (stashedFile.renameTo(live)) legacyRestored++
            }
        }
        if (legacyRestored > 0) {
            Timber.tag("SteamFix").i("applySteamOverlayPref: restored %d legacy-stashed file(s)", legacyRestored)
        }

        if (container.isDisableSteamOverlay) {
            var stashed = 0
            steamOverlayFiles.forEach { name ->
                val live = File(steamDir, name)
                val stashedFile = File(steamDir, "$name.disabled")
                if (live.exists()) {
                    if (stashedFile.exists()) stashedFile.delete()
                    if (live.renameTo(stashedFile)) stashed++
                }
            }
            Timber.tag("SteamFix").i("applySteamOverlayPref: disabled, stashed %d overlay file(s)", stashed)
        } else {
            var restored = 0
            steamOverlayFiles.forEach { name ->
                val live = File(steamDir, name)
                val stashedFile = File(steamDir, "$name.disabled")
                if (stashedFile.exists() && !live.exists()) {
                    if (stashedFile.renameTo(live)) restored++
                }
            }
            if (restored > 0) Timber.tag("SteamFix").i("applySteamOverlayPref: enabled, restored %d overlay file(s)", restored)
        }
    }

    fun restoreSteamclientFiles(context: Context, steamAppId: Int) {
        val imageFs = ImageFs.find(context)

        var restoredCount = 0

        val origDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")

        val backupDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steamclient_backup")
        if (backupDir.exists()) {
            steamClientFiles().forEach { file ->
                val dll = File(backupDir, "$file.orig")
                if (dll.exists()) {
                    Files.copy(dll.toPath(), File(origDir, file).toPath(), StandardCopyOption.REPLACE_EXISTING)
                    restoredCount++
                }
            }
        }

        val extraDllDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/extra_dlls")
        if (extraDllDir.exists()) {
            extraDllDir.deleteRecursively()
        }

        Timber.i("Finished restoreSteamclientFiles for appId: $steamAppId. Restored $restoredCount file(s)")
    }

    internal fun generateColdClientIni(
        gameName: String,
        executablePath: String,
        exeCommandLine: String,
        steamAppId: Int,
        workingDir: String?,
        isUnpackFiles: Boolean,
    ): String {
        val exePath = "steamapps\\common\\$gameName\\${executablePath.replace("/", "\\")}"
        val exeRunDir = if (workingDir.isNullOrEmpty()) exePath.substringBeforeLast("\\") else ""

        // Only include DllsToInjectFolder if unpackFiles is enabled
        val injectionSection = if (isUnpackFiles) {
            """
                [Injection]
                IgnoreLoaderArchDifference=1
                DllsToInjectFolder=extra_dlls
            """
        } else {
            """
                [Injection]
                IgnoreLoaderArchDifference=1
            """
        }

        return """
                [SteamClient]

                Exe=$exePath
                ExeRunDir=$exeRunDir
                ExeCommandLine=$exeCommandLine
                AppId=$steamAppId

                # path to the steamclient dlls, both must be set, absolute paths or relative to the loader directory
                SteamClientDll=steamclient.dll
                SteamClient64Dll=steamclient64.dll

                $injectionSection
            """.trimIndent()
    }

    /**
     * Resolve the ColdClient-style path "steamapps\common\<gameName>\<exe>"
     * case-insensitively against the on-disk Linux filesystem. Inner files may
     * have been installed with different casing (Steam → on-disk) and Linux
     * case-sensitivity turns what Windows treats as a match into a
     * "file not found" error from ColdClientLoader.
     *
     * Returns the actual-casing relative path (using forward slashes) when all
     * components exist on disk. Returns null when a component is missing,
     * which is the "install is actually broken" case rather than a casing
     * mismatch. Logs at SteamFix level when casing differs so the logcat shows
     * exactly which component was wrong.
     */
    internal fun resolveOnDiskCasing(
        steamDir: File,
        gameName: String,
        executablePath: String,
    ): String? {
        val relComponents = buildList {
            add("steamapps")
            add("common")
            add(gameName)
            executablePath.replace("\\", "/").split("/").filter { it.isNotEmpty() }.forEach { add(it) }
        }
        var cursor: File = steamDir
        val resolved = mutableListOf<String>()
        for ((idx, requested) in relComponents.withIndex()) {
            val exact = File(cursor, requested)
            val child: File? = if (exact.exists()) {
                exact
            } else {
                cursor.listFiles()?.firstOrNull { it.name.equals(requested, ignoreCase = true) }
            }
            if (child == null) {
                Timber.tag("SteamFix").w(
                    "ColdClient exe resolve MISS: component %d (%s) not found under %s. siblings=%s",
                    idx, requested, cursor.absolutePath,
                    cursor.listFiles()?.joinToString(", ") { it.name } ?: "<unreadable>",
                )
                return null
            }
            if (child.name != requested) {
                Timber.tag("SteamFix").w(
                    "ColdClient exe resolve CASE MISMATCH: requested '%s' on-disk '%s' at depth %d (%s)",
                    requested, child.name, idx, cursor.absolutePath,
                )
            }
            resolved += child.name
            cursor = child
        }
        return resolved.joinToString("/")
    }

    internal fun writeColdClientIni(steamAppId: Int, container: Container, launchInfo: LaunchInfo? = null) {
        val gameName = getAppDirName(getAppInfoOf(steamAppId))
        val workingDir = launchInfo?.workingDir
        val iniFile = File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/ColdClientLoader.ini")
        iniFile.parentFile?.mkdirs()

        // SteamFix: resolve the "steamapps\common\<gameName>\<exe>" path against
        // the actual on-disk filesystem before we bake it into the INI. If any
        // component's casing differs we log it; if the exe is genuinely missing
        // we still write the INI (so ColdClient's own error surfaces as before)
        // but the logcat now shows exactly which path component went wrong.
        val steamDir = File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam")
        val resolved = resolveOnDiskCasing(steamDir, gameName, container.executablePath)
        val (effectiveGameName, effectiveExe) = if (resolved != null) {
            val parts = resolved.split("/")
            // Components 0,1 are steamapps/common; 2 is gameName; rest is exe path.
            val diskGameName = parts.getOrNull(2) ?: gameName
            val diskExe = parts.drop(3).joinToString("/")
            if (diskGameName != gameName || diskExe.replace("/", "\\") != container.executablePath.replace("/", "\\")) {
                Timber.tag("SteamFix").i(
                    "ColdClient INI using on-disk casing: gameName='%s' (requested '%s'), exe='%s' (requested '%s')",
                    diskGameName, gameName, diskExe, container.executablePath,
                )
            }
            diskGameName to diskExe
        } else {
            Timber.tag("SteamFix").w(
                "ColdClient INI: falling back to requested casing because resolve failed. ColdClientLoader will likely report 'couldn't find the requested exe file'.",
            )
            gameName to container.executablePath
        }

        iniFile.writeText(
            generateColdClientIni(
                gameName = effectiveGameName,
                executablePath = effectiveExe,
                exeCommandLine = container.execArgs,
                steamAppId = steamAppId,
                workingDir = workingDir,
                isUnpackFiles = container.isUnpackFiles,
            )
        )
    }

    fun autoLoginUserChanges(imageFs: ImageFs) {
        val vdfFileText = SteamService.getLoginUsersVdfOauth(
            steamId64 = SteamService.userSteamId?.convertToUInt64().toString(),
            account = PrefManager.username,
            refreshToken = PrefManager.refreshToken,
            accessToken = PrefManager.accessToken,      // may be blank
            personaName = SteamService.instance?.localPersona?.value?.name ?: PrefManager.username
        )
        val steamConfigDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/config")
        try {
            File(steamConfigDir, "loginusers.vdf").writeText(vdfFileText)
            val rootDir = imageFs.rootDir
            val userRegFile = File(rootDir, ImageFs.WINEPREFIX + "/user.reg")
            val steamRoot = "C:\\Program Files (x86)\\Steam"
            val steamExe = "$steamRoot\\steam.exe"
            val hkcu = "Software\\Valve\\Steam"
            WineRegistryEditor(userRegFile).use { reg ->
                reg.setStringValue("Software\\Valve\\Steam", "AutoLoginUser", PrefManager.username)
                reg.setStringValue(hkcu, "SteamExe", steamExe)
                reg.setStringValue(hkcu, "SteamPath", steamRoot)
                reg.setStringValue(hkcu, "InstallPath", steamRoot)
            }
        } catch (e: Exception) {
            Timber.w("Could not add steam config options: $e")
        }
    }

    /**
     * Creates configuration files that make Steam run in lightweight mode
     * with reduced resource usage and disabled community features
     */
    private fun setupLightweightSteamConfig(imageFs: ImageFs, steamId64: String?) {
        Timber.i("Setting up lightweight steam configs")
        try {
            val steamPath = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")

            // Create necessary directories
            val userDataPath = File(steamPath, "userdata/$steamId64")
            val configPath = File(userDataPath, "config")
            val remotePath = File(userDataPath, "7/remote")

            configPath.mkdirs()
            remotePath.mkdirs()

            // Create localconfig.vdf for small mode and low resource usage
            val localConfigContent = """
                "UserLocalConfigStore"
                {
                  "Software"
                  {
                    "Valve"
                    {
                      "Steam"
                      {
                        "SmallMode"                      "1"
                        "LibraryDisableCommunityContent" "1"
                        "LibraryLowBandwidthMode"        "1"
                        "LibraryLowPerfMode"             "1"
                      }
                    }
                  }
                  "friends"
                  {
                    "SignIntoFriends" "0"
                  }
                }
            """.trimIndent()

            // Create sharedconfig.vdf for additional optimizations
            val sharedConfigContent = """
                "UserRoamingConfigStore"
                {
                  "Software"
                  {
                    "Valve"
                    {
                      "Steam"
                      {
                        "SteamDefaultDialog" "#app_games"
                        "FriendsUI"
                        {
                          "FriendsUIJSON" "{\"bSignIntoFriends\":false,\"bAnimatedAvatars\":false,\"PersonaNotifications\":0,\"bDisableRoomEffects\":true}"
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            // Write the configuration files if they don't exist
            val localConfigFile = File(configPath, "localconfig.vdf")
            val sharedConfigFile = File(remotePath, "sharedconfig.vdf")

            if (!localConfigFile.exists()) {
                localConfigFile.writeText(localConfigContent)
                Timber.i("Created lightweight Steam localconfig.vdf")
            }

            if (!sharedConfigFile.exists()) {
                sharedConfigFile.writeText(sharedConfigContent)
                Timber.i("Created lightweight Steam sharedconfig.vdf")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to setup lightweight Steam configuration")
        }
    }

    /**
     * Restores the unpacked executable (.unpacked.exe) if it exists and is different from current .exe
     * This ensures we use the DRM-free version when not using real Steam
     */
    private fun restoreUnpackedExecutable(context: Context, steamAppId: Int) {
        try {
            val imageFs = ImageFs.find(context)
            val appDirPath = SteamService.getAppDirPath(steamAppId)

            // Convert to Wine path format
            val container = ContainerUtils.getContainer(context, "STEAM_$steamAppId")
            val executablePath = container.executablePath
            val drives = container.drives
            val driveIndex = drives.indexOf(appDirPath)
            val drive = if (driveIndex > 1) {
                drives[driveIndex - 2]
            } else {
                Timber.e("Could not locate game drive")
                'D'
            }
            val executableFile = "$drive:\\${executablePath}"

            val exe = File(imageFs.wineprefix + "/dosdevices/" + executableFile.replace("A:", "a:").replace('\\', '/'))
            val unpackedExe = File(imageFs.wineprefix + "/dosdevices/" + executableFile.replace("A:", "a:").replace('\\', '/') + ".unpacked.exe")

            if (unpackedExe.exists()) {
                // Check if files are different (compare size and last modified time for efficiency)
                val areFilesDifferent = !exe.exists() ||
                    exe.length() != unpackedExe.length() ||
                    exe.lastModified() != unpackedExe.lastModified()

                if (areFilesDifferent) {
                    Files.copy(unpackedExe.toPath(), exe.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Timber.i("Restored unpacked executable from ${unpackedExe.name} to ${exe.name}")
                } else {
                    Timber.i("Unpacked executable is already current, no restore needed")
                }
            } else {
                Timber.i("No unpacked executable found, using current executable")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore unpacked executable for appId $steamAppId")
        }
    }

    /**
     * Creates a Steam ACF (Application Cache File) manifest for the given app
     * This allows real Steam to detect the game as installed
     */
    private fun createAppManifest(context: Context, steamAppId: Int) {
        try {
            Timber.tag("SteamFix").i("createAppManifest: begin for appId=$steamAppId")
            val appInfo = SteamService.getAppInfoOf(steamAppId)
            if (appInfo == null) {
                Timber.tag("SteamFix").w("createAppManifest ABORT: no SteamApp info for appId=$steamAppId. Steam will treat the game as not installed and gray out Play.")
                return
            }

            val imageFs = ImageFs.find(context)

            // Create the steamapps folder structure
            val steamappsDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steamapps")
            if (!steamappsDir.exists()) {
                steamappsDir.mkdirs()
            }

            // Create the common folder
            val commonDir = File(steamappsDir, "common")
            if (!commonDir.exists()) {
                commonDir.mkdirs()
            }

            // Get game directory info
            val gameDir = File(SteamService.getAppDirPath(steamAppId))
            val gameName = gameDir.name
            val sizeOnDisk = calculateDirectorySize(gameDir)

            // Resolve the installdir Steam expects. Real steam.exe -applaunch looks
            // the game up via steamapps/common/<installdir>, NOT the on-disk folder
            // name, so the primary symlink must match the manifest's installdir.
            val actualInstallDir = appInfo.config.installDir.ifEmpty { gameName }
            Timber.tag("SteamFix").i(
                "createAppManifest: installDir pre-check appId=%d appInfo.installDir='%s' gameDir.name='%s' actualInstallDir='%s' match=%s",
                steamAppId, appInfo.config.installDir, gameName, actualInstallDir,
                appInfo.config.installDir.equals(gameName, ignoreCase = false),
            )

            val primaryLink = File(commonDir, actualInstallDir)
            createSteamCommonLink(primaryLink, gameDir)
            // Keep a fallback alias under the on-disk folder name for code paths
            // that still resolve via gameDir.name (WineUtils, legacy helpers).
            if (actualInstallDir != gameName) {
                createSteamCommonLink(File(commonDir, gameName), gameDir)
            }
            try {
                val linkPath = primaryLink.toPath()
                val isSymlink = java.nio.file.Files.isSymbolicLink(linkPath)
                val targetReadback = if (isSymlink) java.nio.file.Files.readSymbolicLink(linkPath).toString() else "(not a symlink)"
                val targetExists = primaryLink.exists()
                Timber.tag("SteamFix").i(
                    "createAppManifest: symlink readback appId=%d link=%s isSymlink=%s target=%s resolvedExists=%s",
                    steamAppId, primaryLink.absolutePath, isSymlink, targetReadback, targetExists,
                )
            } catch (e: Exception) {
                Timber.tag("SteamFix").w(e, "createAppManifest: symlink readback failed for %s", primaryLink.absolutePath)
            }

            val installedBranch = SteamService.getInstalledApp(steamAppId)?.branch ?: "public"
            val buildId = (appInfo.branches[installedBranch] ?: appInfo.branches["public"])?.buildId ?: 0L
            if (buildId == 0L) {
                Timber.tag("SteamFix").w("createAppManifest ABORT: appId=$steamAppId buildid unresolvable (branch=$installedBranch, known branches=${appInfo.branches.keys}). Zero buildid makes Steam force an update and gray out Play.")
                return
            }
            val downloadableDepots = SteamService.getDownloadableDepots(steamAppId)

            val regularDepots = mutableMapOf<Int, DepotInfo>()
            val sharedDepots = mutableMapOf<Int, DepotInfo>()

            downloadableDepots.forEach { (depotId, depotInfo) ->
                val manifest = depotInfo.manifests[installedBranch]
                    ?: depotInfo.manifests["public"]
                    ?: depotInfo.manifests.values.firstOrNull()
                if (manifest != null && manifest.gid != 0L) {
                    regularDepots[depotId] = depotInfo
                } else {
                    sharedDepots[depotId] = depotInfo
                }
            }

            // Find the main content depot (owner) - typically the one with the lowest ID that has content
            val mainDepotId = regularDepots.keys.minOrNull()

            // LastOwner is how Steam attributes the install to a signed-in account.
            // Leaving it zero makes cloud-sync/ownership checks resolve against a
            // non-existent user, which is one of the stalls that leaves Play disabled.
            val lastOwner = SteamService.userSteamId?.convertToUInt64()?.toString() ?: "0"
            if (lastOwner == "0") {
                Timber.tag("SteamFix").w("createAppManifest WARN: appId=$steamAppId LastOwner=0 — no signed-in SteamID. Cloud sync and ownership checks may stall.")
            }

            // Pre-resolve depot manifests so we can bail before writing a broken ACF.
            val regularDepotManifests = regularDepots.mapValues { (_, depotInfo) ->
                depotInfo.manifests[installedBranch]
                    ?: depotInfo.manifests["public"]
                    ?: depotInfo.manifests.values.firstOrNull()
            }
            val brokenDepotIds = regularDepotManifests.filter { (_, m) -> m == null || m.gid == 0L }.keys
            if (brokenDepotIds.isNotEmpty()) {
                Timber.tag("SteamFix").w("createAppManifest ABORT: appId=$steamAppId depot(s) $brokenDepotIds have no resolvable manifest GID for branch=$installedBranch. Zero depot GID triggers Steam's 'Update Required' loop and disables Play.")
                return
            }

            // SteamFix #28: if PICS gave us depots but none resolved with a GID, every
            // game-declared depot landed in `sharedDepots` and our ACF would be written
            // with an empty InstalledDepots block — Steam then flips Update Required
            // on the game itself. Bail out instead of overwriting a previously-good acf.
            if (regularDepots.isEmpty()) {
                val existing = File(steamappsDir, "appmanifest_$steamAppId.acf")
                Timber.tag("SteamFix").w(
                    "createAppManifest ABORT: appId=%d regularDepots empty (downloadableDepots=%s sharedDepots=%s branch=%s). Keeping existing acf=%s (exists=%s) to avoid regressing Steam into Update Required.",
                    steamAppId,
                    downloadableDepots.keys,
                    sharedDepots.keys,
                    installedBranch,
                    existing.absolutePath,
                    existing.exists(),
                )
                // Still write 228980's manifest — it's independent of the child's
                // state and we just resolved fresh GIDs for it.
                writeSteamworksCommonManifest(steamappsDir, commonDir, lastOwner)
                return
            }

            Timber.tag("SteamFix").i(
                "createAppManifest: appId=%d branch=%s downloadableDepots=%s regular=%s shared=%s buildid=%d",
                steamAppId, installedBranch,
                downloadableDepots.keys, regularDepots.keys, sharedDepots.keys, buildId,
            )

            // Create ACF content
            val acfContent = buildString {
                appendLine("\"AppState\"")
                appendLine("{")
                appendLine("\t\"appid\"\t\t\"$steamAppId\"")
                appendLine("\t\"Universe\"\t\t\"1\"")
                appendLine("\t\"name\"\t\t\"${escapeString(appInfo.name)}\"")
                // StateFlags = 4 = fully installed. SteamFix: we intentionally do not
                // set 2 (update required) / 8 (update pending) / 16 (validating) — if
                // Steam thinks an update is needed, it will flip these bits itself on
                // next launch; we just don't claim authority over them.
                appendLine("\t\"StateFlags\"\t\t\"4\"")
                appendLine("\t\"LastUpdated\"\t\t\"${System.currentTimeMillis() / 1000}\"")
                appendLine("\t\"SizeOnDisk\"\t\t\"$sizeOnDisk\"")
                appendLine("\t\"buildid\"\t\t\"$buildId\"")
                // SteamFix #14: TargetBuildID matches buildid so Steam doesn't think
                // it's mid-update. UpdateResult "0" = last update succeeded.
                appendLine("\t\"TargetBuildID\"\t\t\"$buildId\"")
                appendLine("\t\"UpdateResult\"\t\t\"0\"")
                appendLine("\t\"AppType\"\t\t\"Game\"")
                // SteamFix #26: write branch so invalidate-on-branch-change is
                // observable in logcat and so Steam reflects the selected branch.
                appendLine("\t\"betakey\"\t\t\"${if (installedBranch == "public") "" else escapeString(installedBranch)}\"")

                appendLine("\t\"installdir\"\t\t\"${escapeString(actualInstallDir)}\"")

                appendLine("\t\"LastOwner\"\t\t\"$lastOwner\"")
                appendLine("\t\"BytesToDownload\"\t\t\"0\"")
                appendLine("\t\"BytesDownloaded\"\t\t\"0\"")
                appendLine("\t\"AutoUpdateBehavior\"\t\t\"0\"")
                appendLine("\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"")
                appendLine("\t\"ScheduledAutoUpdate\"\t\t\"0\"")

                if (regularDepots.isNotEmpty()) {
                    appendLine("\t\"InstalledDepots\"")
                    appendLine("\t{")
                    regularDepots.forEach { (depotId, _) ->
                        val manifest = regularDepotManifests[depotId]!!
                        appendLine("\t\t\"$depotId\"")
                        appendLine("\t\t{")
                        appendLine("\t\t\t\"manifest\"\t\t\"${manifest.gid}\"")
                        appendLine("\t\t\t\"size\"\t\t\"${manifest.size}\"")
                        appendLine("\t\t}")
                    }
                    appendLine("\t}")
                }

                // Declare shared-redist depots (228985/228989 etc.) as
                // SharedDepots owned by their parent app (e.g. 228980). Without
                // this block Steam loads the child's acf, notices via PICS that
                // these depots are "really" owned by 228980, logs
                // `config changed : removed depots` + `Dependency added: parent
                // 228980, child <me>`, and flips 228980 → `Update Required` →
                // cascades `Fully Installed,Update Queued,` onto us. That is
                // the gray-Play state. Declaring the ownership explicitly tells
                // Steam the link is already satisfied, so no recategorization /
                // queue flip happens.
                if (sharedDepots.isNotEmpty()) {
                    appendLine("\t\"SharedDepots\"")
                    appendLine("\t{")
                    sharedDepots.forEach { (depotId, info) ->
                        appendLine("\t\t\"$depotId\"\t\t\"${info.depotFromApp}\"")
                    }
                    appendLine("\t}")
                }

                // SteamFix #13: cloud_enabled="0" is a deliberate choice (Option B).
                // GameNative already runs SteamAutoCloud.syncUserFiles around every
                // launch with a signed JWT, so letting the Wine-hosted Steam client
                // also sync would race and occasionally blow away saves. Leaving it
                // disabled here is what keeps the ON toggle from corrupting data.
                // If we ever expose a user-facing "let real Steam manage cloud"
                // setting, this line becomes the toggle point.
                appendLine("\t\"UserConfig\"")
                appendLine("\t{")
                appendLine("\t\t\"language\"\t\t\"english\"")
                appendLine("\t\t\"cloud_enabled\"\t\t\"0\"")
                appendLine("\t\t\"BetaKey\"\t\t\"${if (installedBranch == "public") "" else escapeString(installedBranch)}\"")
                appendLine("\t}")
                appendLine("\t\"MountedConfig\" { \"language\" \"english\" }")

                appendLine("}")
            }

            // Write ACF file
            val acfFile = File(steamappsDir, "appmanifest_$steamAppId.acf")
            acfFile.writeText(acfContent)

            Timber.tag("SteamFix").i("createAppManifest OK: appId=$steamAppId name=${appInfo.name} installdir=$actualInstallDir buildid=$buildId branch=$installedBranch depots=${regularDepots.keys}")

            // SteamFix #27: always write a real appmanifest_228980.acf for Steamworks
            // Common Redistributables. The dependency is declared server-side in
            // Steam's PICS metadata, not in the child game's own depot list, so
            // `sharedDepots` on the child is empty for affected games (e.g. Shiren).
            // Without a valid 228980 manifest, Steam flips "Update Required" on the
            // shared dep, queues a download it can't complete, and leaves the child
            // stuck in "Update Queued" — which presents as a gray Play button.
            // writeSteamworksCommonManifest is a no-op if PICS has no AppInfo for 228980.
            writeSteamworksCommonManifest(steamappsDir, commonDir, lastOwner)

        } catch (e: Exception) {
            Timber.e(e, "Failed to create ACF manifest for appId $steamAppId")
        }
    }

    private data class CanonicalDepot(
        val id: Int,
        val manifestGid: Long,
        val size: Long,
        val installScript: String,
    )

    // Buildid + depot GIDs sourced verbatim from a canonical PC install of 228980.
    // Steam treats a manifest with this exact shape as "nothing to do" — no
    // reconfigure, no "config changed : removed depots", no scheduler backoff.
    private val canonical228980Depots: List<CanonicalDepot> = listOf(
        CanonicalDepot(228981, 7613356809904826842L, 5884085L,   "_CommonRedist\\\\vcredist\\\\2005\\\\installscript.vdf"),
        CanonicalDepot(228982, 6413394087650432851L, 9688647L,   "_CommonRedist\\\\vcredist\\\\2008\\\\installscript.vdf"),
        CanonicalDepot(228983, 8124929965194586177L, 19265607L,  "_CommonRedist\\\\vcredist\\\\2010\\\\installscript.vdf"),
        CanonicalDepot(228984, 2547553897526095397L, 13742505L,  "_CommonRedist\\\\vcredist\\\\2012\\\\installscript.vdf"),
        CanonicalDepot(228985, 3966345552745568756L, 13699237L,  "_CommonRedist\\\\vcredist\\\\2013\\\\installscript.vdf"),
        CanonicalDepot(228986, 8782296191957114623L, 29759921L,  "_CommonRedist\\\\vcredist\\\\2015\\\\installscript.vdf"),
        CanonicalDepot(228987, 4302102680580581867L, 29664201L,  "_CommonRedist\\\\vcredist\\\\2017\\\\installscript.vdf"),
        CanonicalDepot(228988, 6645201662696499616L, 29212173L,  "_CommonRedist\\\\vcredist\\\\2019\\\\installscript.vdf"),
        CanonicalDepot(228989, 3514306556860204959L, 39590283L,  "_CommonRedist\\\\vcredist\\\\2022\\\\installscript.vdf"),
        CanonicalDepot(228990, 1829726630299308803L, 102931551L, "_CommonRedist\\\\DirectX\\\\Jun2010\\\\installscript.vdf"),
        CanonicalDepot(229000, 4622705914179893434L, 242743889L, "_CommonRedist\\\\DotNet\\\\3.5\\\\installscript.vdf"),
        CanonicalDepot(229001, 4049573910112143457L, 267964564L, "_CommonRedist\\\\DotNet\\\\3.5 Client Profile\\\\installscript.vdf"),
        CanonicalDepot(229002, 7260605429366465749L, 50450161L,  "_CommonRedist\\\\DotNet\\\\4.0\\\\installscript.vdf"),
        CanonicalDepot(229003, 8740933542064151477L, 43001447L,  "_CommonRedist\\\\DotNet\\\\4.0 Client Profile\\\\installscript.vdf"),
        CanonicalDepot(229004, 5220958916987797232L, 70000464L,  "_CommonRedist\\\\DotNet\\\\4.5.2\\\\installscript.vdf"),
        CanonicalDepot(229005, 7992454656023763365L, 62009092L,  "_CommonRedist\\\\DotNet\\\\4.6\\\\installscript.vdf"),
        CanonicalDepot(229006, 1784011429307107530L, 83944258L,  "_CommonRedist\\\\DotNet\\\\4.7\\\\installscript.vdf"),
        CanonicalDepot(229007, 4477590687906973371L, 117381405L, "_CommonRedist\\\\DotNet\\\\4.8\\\\installscript.vdf"),
        CanonicalDepot(229011, 392351049714934122L,  7672416L,   "_CommonRedist\\\\XNA\\\\3.1\\\\installscript.vdf"),
        CanonicalDepot(229012, 4353723233161159493L, 7061608L,   "_CommonRedist\\\\XNA\\\\4.0\\\\installscript.vdf"),
        CanonicalDepot(229020, 5799761707845834510L, 810085L,    "_CommonRedist\\\\OpenAL\\\\2.0.7.0\\\\installscript.vdf"),
        CanonicalDepot(229030, 1043465440436835055L, 51790718L,  "_CommonRedist\\\\PhysX\\\\8.09.04\\\\installscript.vdf"),
        CanonicalDepot(229031, 7746630274301172884L, 26729083L,  "_CommonRedist\\\\PhysX\\\\9.12.1031\\\\installscript.vdf"),
        CanonicalDepot(229032, 3616495131483866412L, 41178235L,  "_CommonRedist\\\\PhysX\\\\9.13.1220\\\\installscript.vdf"),
    )

    private val canonical228980BuildId = 19222509L

    /**
     * SteamFix #35: write a canonical appmanifest_228980.acf that mirrors the
     * exact shape Steam writes on a real PC after a clean commit — all 24
     * Steamworks-Common-Redist depots, matching InstallScripts block, PC-
     * matching byte counters. Steam treats this as "nothing to do" on every
     * launch regardless of which child game is launching, breaking the
     * per-launch "Updating…" loop we saw with per-game filtered subsets.
     *
     * Background: earlier approaches (SteamFix #31/33/34) wrote only the
     * depot subset the currently-launching game declared via
     * `DepotInfo.depotFromApp == 228980`. Steam's in-memory mount state
     * tracks what it last satisfied; since we SIGKILL the wine prefix on
     * Exit Game (GuestProgramLauncherComponent.java:74), Steam never flushes
     * its post-reconfigure view to disk. Next boot: disk ≠ memory →
     * reconfigure → "Updating Steamworks Common…" window → gray Play.
     * The canonical baseline removes the mismatch surface entirely.
     */
    private fun writeSteamworksCommonManifest(
        steamappsDir: File,
        commonDir: File,
        lastOwner: String,
    ) {
        val sharedAppId = 228980
        val staleAcf = File(steamappsDir, "appmanifest_$sharedAppId.acf")
        val sharedAppInfo = SteamService.getAppInfoOf(sharedAppId)
        if (sharedAppInfo == null) {
            if (staleAcf.exists() && staleAcf.delete()) {
                Timber.tag("SteamFix").i(
                    "writeSteamworksCommonManifest: removed stale %s (no PICS info for 228980)",
                    staleAcf.absolutePath,
                )
            }
            Timber.tag("SteamFix").w(
                "writeSteamworksCommonManifest ABORT: PICS has no AppInfo for 228980 — " +
                    "Steam will queue 228980 for update and gray out Play for the child game."
            )
            return
        }

        val sharedBuildId = sharedAppInfo.branches["public"]?.buildId
            ?: sharedAppInfo.branches.values.firstOrNull()?.buildId
            ?: canonical228980BuildId

        // Declare only the depots whose `installscript.vdf` is present on
        // disk. An empty manifest made Steam say
        // `update prefetch finished : 52086224 bytes to download` and try to
        // download the missing content — the download gets suspended mid-run
        // and leaves 228980 `Suspended`, which cascades `Update Queued` onto
        // the child indefinitely. The 2-depot present-only shape has
        // `0 bytes to download`, so Steam's reconcile is a ~1-second no-op.
        // Matches the shape Steam itself writes after its first successful
        // reconcile.
        val sharedRedistDirForSkip = File(commonDir, "Steamworks Shared")
        val presentDepots = canonical228980Depots.filter { d ->
            File(sharedRedistDirForSkip, d.installScript.replace("\\\\", "/").replace("\\", "/")).isFile
        }
        val presentDepotIds = presentDepots.map { it.id }.toSet()
        if (staleAcf.isFile) {
            val existingBuildId = parseAcfBuildId(staleAcf)
            val existingDepots = parseAcfInstalledDepotIds(staleAcf)
            val existingScripts = parseAcfInstallScriptDepotIds(staleAcf)
            val depotsMatch = existingDepots == presentDepotIds
            val buildIdMatch = existingBuildId == sharedBuildId
            val scriptsMatch = existingScripts == presentDepotIds
            if (depotsMatch && buildIdMatch && scriptsMatch) {
                val updateResult = parseAcfUpdateResult(staleAcf)
                if (updateResult == 0L) {
                    Timber.tag("SteamFix").i(
                        "writeSteamworksCommonManifest SKIP: %s matches present-depot baseline (buildid=%d, %d depots, UpdateResult=0)",
                        staleAcf.absolutePath, sharedBuildId, presentDepotIds.size,
                    )
                    return
                }
                if (staleAcf.delete()) {
                    Timber.tag("SteamFix").w(
                        "writeSteamworksCommonManifest: deleted present-shaped %s with UpdateResult=%d; rewriting",
                        staleAcf.absolutePath, updateResult,
                    )
                }
            } else {
                Timber.tag("SteamFix").i(
                    "writeSteamworksCommonManifest: existing %s differs from present baseline (buildid=%d vs %d, %d vs %d depots, %d vs %d scripts); rewriting",
                    staleAcf.absolutePath, existingBuildId, sharedBuildId,
                    existingDepots.size, presentDepotIds.size,
                    existingScripts.size, presentDepotIds.size,
                )
            }
        }

        val sharedInstallDir = "Steamworks Shared"
        val sharedCommonDir = File(commonDir, sharedInstallDir)
        if (!sharedCommonDir.exists()) {
            sharedCommonDir.mkdirs()
        }

        val launcherPath = "C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe"

        val acfContent = buildString {
            appendLine("\"AppState\"")
            appendLine("{")
            appendLine("\t\"appid\"\t\t\"$sharedAppId\"")
            appendLine("\t\"Universe\"\t\t\"1\"")
            appendLine("\t\"LauncherPath\"\t\t\"$launcherPath\"")
            appendLine("\t\"name\"\t\t\"${escapeString(sharedAppInfo.name.ifBlank { "Steamworks Common Redistributables" })}\"")
            appendLine("\t\"StateFlags\"\t\t\"4\"")
            appendLine("\t\"installdir\"\t\t\"$sharedInstallDir\"")
            appendLine("\t\"LastUpdated\"\t\t\"${System.currentTimeMillis() / 1000}\"")
            appendLine("\t\"LastPlayed\"\t\t\"0\"")
            val presentSizeOnDisk = presentDepots.sumOf { it.size }
            appendLine("\t\"SizeOnDisk\"\t\t\"$presentSizeOnDisk\"")
            appendLine("\t\"StagingSize\"\t\t\"0\"")
            appendLine("\t\"buildid\"\t\t\"$sharedBuildId\"")
            appendLine("\t\"LastOwner\"\t\t\"$lastOwner\"")
            appendLine("\t\"DownloadType\"\t\t\"1\"")
            appendLine("\t\"UpdateResult\"\t\t\"0\"")
            appendLine("\t\"BytesToDownload\"\t\t\"0\"")
            appendLine("\t\"BytesDownloaded\"\t\t\"0\"")
            appendLine("\t\"BytesToStage\"\t\t\"0\"")
            appendLine("\t\"BytesStaged\"\t\t\"0\"")
            appendLine("\t\"TargetBuildID\"\t\t\"$sharedBuildId\"")
            appendLine("\t\"AutoUpdateBehavior\"\t\t\"0\"")
            appendLine("\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"")
            appendLine("\t\"ScheduledAutoUpdate\"\t\t\"0\"")

            appendLine("\t\"InstalledDepots\"")
            appendLine("\t{")
            presentDepots.forEach { d ->
                appendLine("\t\t\"${d.id}\"")
                appendLine("\t\t{")
                appendLine("\t\t\t\"manifest\"\t\t\"${d.manifestGid}\"")
                appendLine("\t\t\t\"size\"\t\t\"${d.size}\"")
                appendLine("\t\t}")
            }
            appendLine("\t}")

            appendLine("\t\"InstallScripts\"")
            appendLine("\t{")
            presentDepots.forEach { d ->
                appendLine("\t\t\"${d.id}\"\t\t\"${d.installScript}\"")
            }
            appendLine("\t}")

            appendLine("\t\"UserConfig\"")
            appendLine("\t{")
            appendLine("\t\t\"BetaKey\"\t\t\"public\"")
            appendLine("\t}")
            appendLine("\t\"MountedConfig\"")
            appendLine("\t{")
            appendLine("\t\t\"BetaKey\"\t\t\"public\"")
            appendLine("\t}")
            appendLine("}")
        }

        staleAcf.writeText(acfContent)

        Timber.tag("SteamFix").i(
            "writeSteamworksCommonManifest OK: wrote present-depot %s buildid=%d (%d depots) lastOwner=%s",
            staleAcf.absolutePath, sharedBuildId, presentDepots.size, lastOwner,
        )
    }

    private fun escapeString(input: String?): String {
        if (input == null) return ""
        return input.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }

    private val acfBuildIdRegex = Regex("\"buildid\"\\s*\"(\\d+)\"")
    private val acfUpdateResultRegex = Regex("\"UpdateResult\"\\s*\"(\\d+)\"")

    // Matches `"<depotId>" { "manifest" "<gid>" ... }` entries inside the
    // InstalledDepots block of our own acf output. Tolerant of whitespace /
    // newlines; depot ids are ints, manifest GIDs can be long-valued.
    private val acfInstalledDepotEntryRegex = Regex(
        "\"(\\d+)\"\\s*\\{\\s*\"manifest\"\\s*\"\\d+\"",
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun parseAcfBuildId(acf: File): Long {
        return try {
            val match = acfBuildIdRegex.find(acf.readText()) ?: return 0L
            match.groupValues[1].toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun parseAcfUpdateResult(acf: File): Long {
        return try {
            val match = acfUpdateResultRegex.find(acf.readText()) ?: return 0L
            match.groupValues[1].toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun parseAcfInstalledDepotIds(acf: File): Set<Int> {
        return try {
            val text = acf.readText()
            val start = text.indexOf("\"InstalledDepots\"")
            if (start < 0) return emptySet()
            val braceOpen = text.indexOf('{', start)
            if (braceOpen < 0) return emptySet()
            // Find the matching closing brace for InstalledDepots.
            var depth = 0
            var braceClose = -1
            for (i in braceOpen until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { braceClose = i; break }
                    }
                }
            }
            if (braceClose < 0) return emptySet()
            val section = text.substring(braceOpen, braceClose)
            acfInstalledDepotEntryRegex.findAll(section)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun parseAcfInstallScriptDepotIds(acf: File): Set<Int> {
        return try {
            val text = acf.readText()
            val start = text.indexOf("\"InstallScripts\"")
            if (start < 0) return emptySet()
            val braceOpen = text.indexOf('{', start)
            if (braceOpen < 0) return emptySet()
            var depth = 0
            var braceClose = -1
            for (i in braceOpen until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { braceClose = i; break }
                    }
                }
            }
            if (braceClose < 0) return emptySet()
            val section = text.substring(braceOpen, braceClose)
            Regex("\"(\\d+)\"\\s*\"[^\"]*installscript\\.vdf\"", RegexOption.IGNORE_CASE)
                .findAll(section)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * SteamFix #12: Create a link for `steamapps/common/<installdir>`. Try
     * NIO `createSymbolicLink` first, then fall back to a junction-style
     * directory with a sentinel (if the underlying filesystem rejects
     * symlinks — some Android external storage mounts do). We log loudly
     * so this shows up as a breadcrumb rather than a silent launch failure.
     */
    private fun createSteamCommonLink(link: File, target: File) {
        if (link.exists()) {
            Timber.tag("SteamFix").d("common link already present: %s", link.absolutePath)
            return
        }
        val parent = link.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            Timber.tag("SteamFix").i("created symlink %s -> %s", link.absolutePath, target.absolutePath)
            return
        } catch (e: Exception) {
            Timber.tag("SteamFix").w(e, "createSymbolicLink failed for %s, falling back to directory + redirect file", link.absolutePath)
        }
        // Fallback: create a real directory containing a sentinel file so Steam at least
        // sees the folder exist. This won't let Steam find the EXE, but it prevents a
        // null-dir crash and gives us a diagnostic marker to search for in logcat.
        try {
            if (link.mkdirs()) {
                File(link, ".steamfix_symlink_failed").writeText(target.absolutePath)
                Timber.tag("SteamFix").w("wrote fallback dir+sentinel at %s (pointing to %s)", link.absolutePath, target.absolutePath)
            }
        } catch (e2: Exception) {
            Timber.tag("SteamFix").e(e2, "fallback directory creation failed for %s", link.absolutePath)
        }
    }

    private fun calculateDirectorySize(directory: File): Long {
        if (!directory.exists() || !directory.isDirectory()) {
            return 0L
        }

        var size = 0L
        try {
            directory.walkTopDown().forEach { file ->
                if (file.isFile()) {
                    size += file.length()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error calculating directory size")
        }

        return size
    }

    /**
     * Restores the original steam_api.dll and steam_api64.dll files from their .orig backups
     * if they exist. Does not error if backup files are not found.
     */
    fun restoreSteamApi(context: Context, appId: String) {

        Timber.tag("SteamFix").i("restoreSteamApi starting for appId=%s", appId)
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val imageFs = ImageFs.find(context)
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val cfgFile = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steam.cfg")
        if (!cfgFile.exists()){
            cfgFile.parentFile?.mkdirs()
            Files.createFile(cfgFile.toPath())
            cfgFile.writeText("BootStrapperInhibitAll=Enable\nBootStrapperForceSelfUpdate=False")
        }

        // Update or modify localconfig.vdf
        updateOrModifyLocalConfig(imageFs, container, steamAppId.toString(), SteamService.userSteamId!!.accountID.toString())

        skipFirstTimeSteamSetup(imageFs.rootDir)
        val appDirPath = SteamService.getAppDirPath(steamAppId)

        val needsDllRestore = if (MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_RESTORED)) {
            if (verifyRestoredState(appDirPath)) {
                Timber.tag("SteamFix").i("restoreSteamApi: DLL marker + hash ok, skipping DLL copy")
                false
            } else {
                Timber.tag("SteamFix").w("restoreSteamApi: clearing stale RESTORED marker and re-running restore")
                MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
                true
            }
        } else true

        if (needsDllRestore) {
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
            Timber.tag("SteamFix").i("restoreSteamApi: running DLL restore in %s", appDirPath)

            autoLoginUserChanges(imageFs)
            setupLightweightSteamConfig(imageFs, SteamService.userSteamId!!.accountID.toString())

            putBackSteamDlls(appDirPath)

            // Restore original executable if it exists (for real Steam mode)
            restoreOriginalExecutable(context, steamAppId)

            // Restore original steamclient.dll files if they exist
            restoreSteamclientFiles(context, steamAppId)

            MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
        }

        applySteamOverlayPref(context, container)

        // SteamFix #25/#26: always refresh manifest + symlinks on every real-Steam
        // launch. Multi-account contamination and branch changes otherwise leave
        // stale LastOwner / buildid / installdir until the DLL marker is cleared.
        createAppManifest(context, steamAppId)

        // Game-specific Handling
        ensureSaveLocationsForGames(context, steamAppId)

        // SteamFix: sanity check the install. If the dir has only dot-prefixed
        // metadata sidecars and _CommonRedist (no actual game .exe anywhere),
        // Steam will happily "launch" the game but ColdClientLoader or
        // steam.exe -applaunch will fail because the exe isn't on disk. Make
        // that obvious in logcat instead of surfacing as a silent black screen.
        try {
            val appDir = File(SteamService.getAppDirPath(steamAppId))
            if (appDir.isDirectory) {
                val topLevel = appDir.listFiles()?.map { it.name } ?: emptyList()
                val hasAnyExe = appDir.walkTopDown()
                    .maxDepth(4)
                    .any { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
                if (!hasAnyExe) {
                    Timber.tag("SteamFix").w(
                        "Install INCOMPLETE for appId=%s: no .exe found under %s within 4 levels. topLevel=%s. " +
                            "GameNative's download_complete marker is present but the real game files aren't. Re-verify / re-download from library.",
                        appId, appDir.absolutePath, topLevel,
                    )
                }
            } else {
                Timber.tag("SteamFix").w(
                    "Install MISSING for appId=%s: %s is not a directory.",
                    appId, appDir.absolutePath,
                )
            }
        } catch (e: Exception) {
            Timber.tag("SteamFix").w(e, "install sanity check failed for appId=%s", appId)
        }

        Timber.tag("SteamFix").i("restoreSteamApi finished for appId=%s", appId)
        logAppDirInventory(ContainerUtils.extractGameIdFromContainerId(appId), "restoreSteamApi.done")
    }

    fun findSteamApiDllRootFile(file: File, depth: Int): File? {
        if (depth < 0) return null
        val (files, directories) = file.walkTopDown().maxDepth(1).partition { it.isFile }

        val steamApi = files.firstOrNull {
            it.toPath().name.startsWith("steam_api", true)
            && (
                it.toPath().name.endsWith(".dll", true)
                || it.toPath().name.endsWith(".dll.orig", true)
            )
        }

        if (steamApi != null)
            return steamApi.parentFile

        return directories.filter { it != file }.firstNotNullOfOrNull { findSteamApiDllRootFile(it, depth - 1) }
    }

    fun putBackSteamDlls(appDirPath: String) {
        val rootPath = Paths.get(appDirPath)

        val dllRootFile = findSteamApiDllRootFile(rootPath.toFile(), 10)

        if (dllRootFile == null) {
            Timber.w("Failed to find steam_api.dll/steam_api64.dll on a Steam game")
            return
        }

        dllRootFile.walkTopDown().maxDepth(1).forEach { file ->
            val path = file.toPath()
            if (!file.isFile || !path.name.startsWith("steam_api", ignoreCase = true) || !path.name.endsWith(".orig", ignoreCase = true)) return@forEach

            val is64Bit = path.name.equals("steam_api64.dll.orig", ignoreCase = true)
            val is32Bit = path.name.equals("steam_api.dll.orig", ignoreCase = true)

            if (!is32Bit && !is64Bit) return@forEach

            if (is64Bit || is32Bit) {
                try {
                    val dllName = if (is64Bit) "steam_api64.dll" else "steam_api.dll"
                    val originalPath = path.parent.resolve(dllName)
                    Timber.i("Found ${path.name} at ${path.absolutePathString()}, restoring...")

                    // Delete the current DLL if it exists
                    if (Files.exists(originalPath)) {
                        Files.delete(originalPath)
                    }

                    // Copy the backup back to the original location
                    Files.copy(path, originalPath)

                    Timber.i("Restored $dllName from backup")
                } catch (e: IOException) {
                    Timber.w(e, "Failed to restore ${path.name} from backup")
                }
            }
        }
    }

    /**
     * Restores the original executable files from their .original.exe backups
     * if they exist. Does not error if backup files are not found.
     */
    fun restoreOriginalExecutable(context: Context, steamAppId: Int) {
        Timber.i("Starting restoreOriginalExecutable for appId: $steamAppId")
        val appDirPath = SteamService.getAppDirPath(steamAppId)
        Timber.i("Checking directory: $appDirPath")
        var restoredCount = 0

        val imageFs = ImageFs.find(context)
        val dosDevicesPath = File(imageFs.wineprefix, "dosdevices/a:")

        dosDevicesPath.walkTopDown().maxDepth(10)
            .filter { it.isFile && it.name.endsWith(".original.exe", ignoreCase = true) }
            .forEach { file ->
                try {
                    val origPath = file.toPath()
                    val originalPath = origPath.parent.resolve(origPath.name.removeSuffix(".original.exe"))
                    Timber.i("Found ${origPath.name} at ${origPath.absolutePathString()}, restoring...")

                    // Delete the current exe if it exists
                    if (Files.exists(originalPath)) {
                        Files.delete(originalPath)
                    }

                    // Copy the backup back to the original location
                    Files.copy(origPath, originalPath)

                    Timber.i("Restored ${originalPath.fileName} from backup")
                    restoredCount++
                } catch (e: IOException) {
                    Timber.w(e, "Failed to restore ${file.name} from backup")
                }
            }

        Timber.i("Finished restoreOriginalExecutable for appId: $steamAppId. Restored $restoredCount executable(s)")
    }

    /**
     * SteamFix #11: Only copy GSE saves into Steam userdata when the next launch
     * is actually real Steam. Running this in OFF mode moved GSE's own saves out
     * from under Goldberg. Caller threads `isLaunchRealSteam` through.
     */
    fun migrateGSESavesToSteamUserdata(context: Context, appId: Int, isLaunchRealSteam: Boolean = true) {
        if (!isLaunchRealSteam) {
            Timber.tag("SteamFix").d("migrateGSESavesToSteamUserdata: skipping for appId=%d (not real-Steam mode)", appId)
            return
        }
        val imageFs = ImageFs.find(context)
        val accountId = SteamService.userSteamId?.accountID?.toInt()
            ?: PrefManager.steamUserAccountId.takeIf { it != 0 }

        if (accountId == null) {
            Timber.tag("migrateGSESavesToSteamUserdata").w("Cannot migrate GSE saves: no Steam account ID available")
            return
        }

        val gseDir = File(
            imageFs.rootDir,
            "${ImageFs.WINEPREFIX}/drive_c/users/xuser/AppData/Roaming/GSE Saves/$appId"
        )

        val steamUserdataDir = File(
            imageFs.rootDir,
            "${ImageFs.WINEPREFIX}/drive_c/Program Files (x86)/Steam/userdata/$accountId/$appId"
        )

        fun isDirectoryEmpty(file: File): Boolean {
            return file.isDirectory && file.list()?.isEmpty() ?: true
        }

        if (
            !gseDir.exists() ||
            !gseDir.isDirectory ||
            isDirectoryEmpty(gseDir) // No files inside gseDir
        ) {
            Timber.tag("migrateGSESavesToSteamUserdata").d("No GSE save directory found for appId=$appId")
            return
        }

        Timber.tag("migrateGSESavesToSteamUserdata").i("Starting GSE Saves Migration for appId=$appId")

        if (!steamUserdataDir.exists()) {
            try {
                Files.createDirectories(steamUserdataDir.toPath())
                Timber.tag("migrateGSESavesToSteamUserdata").i("Created Steam userdata directory: ${steamUserdataDir.absolutePath}")
            } catch (e: IOException) {
                Timber.tag("migrateGSESavesToSteamUserdata").e(e, "Failed to create Steam userdata directory")
                return
            }
        }

        var migratedCount = 0
        var migrationFailed = false

        gseDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = gseDir.toPath().relativize(file.toPath())
                val targetFile = steamUserdataDir.toPath().resolve(relativePath)
                try {
                    Files.createDirectories(targetFile.parent)

                    val fileTimestamp = file.lastModified()

                    // As Files.move use linux rename syscall (or simply mv command we know, no need to manually remove the target file)
                    Files.move(
                        file.toPath(),
                        targetFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE   // will throw if the FS can’t guarantee atomicity
                    )

                    // Preserve file timestamp
                    targetFile.setLastModifiedTime(FileTime.fromMillis(fileTimestamp))

                    Timber.tag("migrateGSESavesToSteamUserdata").i("Migrated ${file.name} from GSE saves to Steam userdata")
                    migratedCount++
                } catch (e: Exception) {
                    migrationFailed = true
                    Timber.tag("migrateGSESavesToSteamUserdata").w(e, "Failed to migrate ${file.name}")
                }
            }

        if (!migrationFailed) {
            gseDir.deleteRecursively()
        }

        Timber.tag("migrateGSESavesToSteamUserdata").i("Migration completed for appId=$appId. Migrated $migratedCount file(s)")
    }

    /**
     * SteamFix #10: reverse migration. When a user toggles Launch Steam Client
     * OFF after an ON session, saves that were copied into Steam/userdata need
     * to move back so Goldberg (which reads from GSE Saves) can find them.
     * Only runs when entering OFF mode, since the opposite direction is
     * handled by [migrateGSESavesToSteamUserdata].
     */
    fun migrateSteamUserdataToGSESaves(context: Context, appId: Int, isLaunchRealSteam: Boolean = false) {
        if (isLaunchRealSteam) {
            Timber.tag("SteamFix").d("migrateSteamUserdataToGSESaves: skipping for appId=%d (real-Steam mode)", appId)
            return
        }
        val imageFs = ImageFs.find(context)
        val accountId = SteamService.userSteamId?.accountID?.toInt()
            ?: PrefManager.steamUserAccountId.takeIf { it != 0 }

        if (accountId == null) {
            Timber.tag("SteamFix").w("migrateSteamUserdataToGSESaves: no Steam account ID available")
            return
        }

        val steamUserdataDir = File(
            imageFs.rootDir,
            "${ImageFs.WINEPREFIX}/drive_c/Program Files (x86)/Steam/userdata/$accountId/$appId"
        )
        val gseDir = File(
            imageFs.rootDir,
            "${ImageFs.WINEPREFIX}/drive_c/users/xuser/AppData/Roaming/GSE Saves/$appId"
        )

        fun isDirectoryEmpty(file: File): Boolean {
            return file.isDirectory && file.list()?.isEmpty() ?: true
        }

        if (
            !steamUserdataDir.exists() ||
            !steamUserdataDir.isDirectory ||
            isDirectoryEmpty(steamUserdataDir)
        ) {
            Timber.tag("SteamFix").d("migrateSteamUserdataToGSESaves: no userdata to migrate for appId=%d", appId)
            return
        }

        Timber.tag("SteamFix").i("migrateSteamUserdataToGSESaves: starting migration for appId=%d", appId)

        if (!gseDir.exists()) {
            try {
                Files.createDirectories(gseDir.toPath())
            } catch (e: IOException) {
                Timber.tag("SteamFix").e(e, "migrateSteamUserdataToGSESaves: failed to create GSE Saves dir")
                return
            }
        }

        var migratedCount = 0
        var migrationFailed = false

        steamUserdataDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = steamUserdataDir.toPath().relativize(file.toPath())
                val targetFile = gseDir.toPath().resolve(relativePath)
                try {
                    Files.createDirectories(targetFile.parent)
                    val ts = file.lastModified()
                    Files.move(
                        file.toPath(),
                        targetFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                    targetFile.setLastModifiedTime(FileTime.fromMillis(ts))
                    migratedCount++
                } catch (e: Exception) {
                    migrationFailed = true
                    Timber.tag("SteamFix").w(e, "migrateSteamUserdataToGSESaves: failed to migrate %s", file.name)
                }
            }

        if (!migrationFailed) {
            steamUserdataDir.deleteRecursively()
        }

        Timber.tag("SteamFix").i("migrateSteamUserdataToGSESaves: completed appId=%d, files=%d", appId, migratedCount)
    }

    /**
     * SteamFix #20: one-shot cleanup of the ~200 MB extracted Steam binaries
     * when we're confident no real-Steam launch is pending. Caller owns the
     * "is this safe right now" decision (e.g. after confirming OFF toggle).
     */
    /**
     * Symlink-safe recursive delete. Kotlin's `File.deleteRecursively()` follows
     * symbolic links to directories and deletes the files on the *other* side,
     * which is catastrophic when the tree contains `steamapps/common/<installdir>`
     * symlinks pointing at the real game-files directory on the GameNative side.
     *
     * Java's NIO `Files.walkFileTree` does NOT follow symlinks by default — a
     * symlink to a directory is visited as a file, and `Files.delete` on it
     * unlinks the symlink without touching the target. This is the behaviour we
     * want: tear down the extracted Steam prefix but leave the actual game
     * content the symlinks point to alone.
     */
    /**
     * Emit a one-line inventory of a Steam game's install directory so we can
     * correlate "where did my game go?" reports against launch / mode-switch /
     * variant-reset events. Tagged `SteamFix` so it shows up in the same
     * logcat filter as the rest of the diagnostics.
     */
    fun logAppDirInventory(appId: Int, phase: String) {
        try {
            val path = SteamService.getAppDirPath(appId)
            val dir = File(path)
            if (!dir.exists()) {
                Timber.tag("SteamFix").w("inventory[%s] appId=%d path=%s MISSING", phase, appId, path)
                return
            }
            var fileCount = 0
            var exeCount = 0
            var totalBytes = 0L
            val exeList = mutableListOf<String>()
            try {
                dir.walkTopDown().maxDepth(4).forEach { f ->
                    if (f.isFile) {
                        fileCount++
                        totalBytes += f.length()
                        if (f.name.endsWith(".exe", ignoreCase = true)) {
                            exeCount++
                            if (exeList.size < 8) exeList += f.relativeTo(dir).path
                        }
                    }
                }
            } catch (_: Exception) { /* best-effort walk */ }
            val appInfo = SteamService.getAppInfoOf(appId)
            val configuredExe = appInfo?.config?.launch
                ?.firstOrNull { it.executable.isNotBlank() }?.executable.orEmpty()
            val expectedExeRel = configuredExe.replace("\\", "/").trimStart('/')
            val expectedExeFile = if (expectedExeRel.isNotBlank()) File(dir, expectedExeRel) else null
            val expectedExePresent = expectedExeFile?.exists() == true
            Timber.tag("SteamFix").i(
                "inventory[%s] appId=%d path=%s files=%d exes=%d bytes=%d expectedExe=%s present=%s exes=%s",
                phase, appId, path, fileCount, exeCount, totalBytes,
                expectedExeRel.ifBlank { "(unset)" }, expectedExePresent, exeList,
            )
        } catch (e: Exception) {
            Timber.tag("SteamFix").w(e, "inventory[%s] appId=%d FAILED", phase, appId)
        }
    }

    internal fun deleteTreeNoFollowSymlinks(root: File) {
        if (!root.exists() && !java.nio.file.Files.isSymbolicLink(root.toPath())) return
        val rootPath = root.toPath()
        java.nio.file.Files.walkFileTree(rootPath, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.deleteIfExists(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): java.nio.file.FileVisitResult {
                // Broken symlink or permission issue — unlink and keep going.
                try { java.nio.file.Files.deleteIfExists(file) } catch (_: Exception) {}
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): java.nio.file.FileVisitResult {
                java.nio.file.Files.deleteIfExists(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    fun cleanupExtractedSteamFiles(context: Context) {
        try {
            val imageFs = ImageFs.find(context)
            val steamDir = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/Program Files (x86)/Steam")
            if (!steamDir.exists()) return
            val steamExe = File(steamDir, "steam.exe")
            if (!steamExe.exists()) return
            Timber.tag("SteamFix").i(
                "cleanupExtractedSteamFiles: removing %s (symlink-safe walk)",
                steamDir.absolutePath,
            )
            deleteTreeNoFollowSymlinks(steamDir)
        } catch (e: Exception) {
            Timber.tag("SteamFix").w(e, "cleanupExtractedSteamFiles failed")
        }
    }

    /**
     * Sibling folder "steam_settings" + empty "offline.txt" file, no-ops if they already exist.
     */
    private fun ensureSteamSettings(context: Context, dllPath: Path, appId: String, ticketBase64: String? = null, isOffline: Boolean = false) {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val steamDir = dllPath.parent
        Files.createDirectories(steamDir)
        val appIdFileUpper = dllPath.parent.resolve("steam_appid.txt")
        if (Files.notExists(appIdFileUpper)) {
            Files.createFile(appIdFileUpper)
            appIdFileUpper.toFile().writeText(steamAppId.toString())
        }
        val settingsDir = dllPath.parent.resolve("steam_settings")
        if (Files.notExists(settingsDir)) {
            Files.createDirectories(settingsDir)
        }
        val appIdFile = settingsDir.resolve("steam_appid.txt")
        if (Files.notExists(appIdFile)) {
            Files.createFile(appIdFile)
            appIdFile.toFile().writeText(steamAppId.toString())
        }
        val depotsFile = settingsDir.resolve("depots.txt")
        if (Files.exists(depotsFile)) {
            Files.delete(depotsFile)
        }
        SteamService.getInstalledDepotsOf(steamAppId)?.sorted()?.let { depotsList ->
            Files.createFile(depotsFile)
            depotsFile.toFile().writeText(depotsList.joinToString(System.lineSeparator()))
        }

        val configsIni = settingsDir.resolve("configs.user.ini")
        val accountName   = PrefManager.username
        val accountSteamId = SteamService.userSteamId?.convertToUInt64()?.toString()
            ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }?.toString()
            ?: "0"
        val accountId = SteamService.userSteamId?.accountID
            ?: PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()
            ?: 0L
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val language = runCatching {
            (container.getExtra("language", null)
                ?: container.javaClass.getMethod("getLanguage").invoke(container) as? String)
                ?: "english"
        }.getOrDefault("english").lowercase()
        val useSteamInput = container.getExtra("useSteamInput", "false").toBoolean()

        // Get appInfo to check if saveFilePatterns exist (used for both user and app configs)
        val appInfo = getAppInfoOf(steamAppId)

        val iniContent = buildString {
            appendLine("[user::general]")
            appendLine("account_name=$accountName")
            appendLine("account_steamid=$accountSteamId")
            appendLine("language=$language")
            if (!ticketBase64.isNullOrEmpty()) {
                appendLine("ticket=$ticketBase64")
            }

            // SteamFix #10/#11: in OFF mode (ensureSteamSettings only runs in the
            // emulated-Steam launch path) we must not move GSE saves into Steam's
            // userdata — that's the ON-mode direction. Instead, pull any leftover
            // userdata back into GSE Saves so Goldberg can find it.
            migrateSteamUserdataToGSESaves(context, steamAppId, isLaunchRealSteam = false)

            // Add [user::saves] section
            val steamUserDataPath = "C:\\Program Files (x86)\\Steam\\userdata\\$accountId"
            appendLine()
            appendLine("[user::saves]")
            appendLine("local_save_path=$steamUserDataPath")
        }

        if (Files.notExists(configsIni)) Files.createFile(configsIni)
        configsIni.toFile().writeText(iniContent)

        val appIni = settingsDir.resolve("configs.app.ini")
        val dlcIds = SteamService.getInstalledDlcDepotsOf(steamAppId)
        val dlcApps = SteamService.getDownloadableDlcAppsOf(steamAppId)
        val hiddenDlcApps = SteamService.getHiddenDlcAppsOf(steamAppId)
        val appendedDlcIds = mutableListOf<Int>()

        val forceDlc = container.isForceDlc()
        val appIniContent = buildString {
            appendLine("[app::dlcs]")
            appendLine("unlock_all=${if (forceDlc) 1 else 0}")
            dlcIds?.sorted()?.forEach {
                appendLine("$it=dlc$it")
                appendedDlcIds.add(it)
            }

            dlcApps?.forEach { dlcApp ->
                val installedDlcApp = SteamService.getInstalledApp(dlcApp.id)
                if (installedDlcApp != null && !appendedDlcIds.contains(dlcApp.id)) {
                    appendLine("${dlcApp.id}=dlc${dlcApp.id}")
                    appendedDlcIds.add(dlcApp.id)
                }
            }

            // only add hidden dlc apps if not found in appendedDlcIds
            hiddenDlcApps?.forEach { hiddenDlcApp ->
                if (!appendedDlcIds.contains(hiddenDlcApp.id) &&
                    // only add hidden dlc apps if it is not a DLC of the main app
                    appInfo!!.depots.filter { (_, depot) -> depot.dlcAppId == hiddenDlcApp.id }.size <= 1) {
                    appendLine("${hiddenDlcApp.id}=dlc${hiddenDlcApp.id}")
                }
            }

            // Add app paths and cloud save config sections if appInfo exists
            if (appInfo != null) {
                // Some games required this path to be setup for detecting dlc, e.g. Vampire Survivors
                val gameDir = File(SteamService.getAppDirPath(steamAppId))
                val gameName = gameDir.name
                val actualInstallDir = appInfo.config.installDir.ifEmpty { gameName }
                appendLine()
                appendLine("[app::paths]")
                appendLine("$steamAppId=./steamapps/common/$actualInstallDir")

                // Setup for cloud save
                appendLine()
                append(generateCloudSaveConfig(appInfo))
            }
        }

        if (Files.notExists(appIni)) Files.createFile(appIni)
        appIni.toFile().writeText(appIniContent)

        val mainIni = settingsDir.resolve("configs.main.ini")

        val steamOfflineMode = container.isSteamOfflineMode()
        val useOfflineConfig = steamOfflineMode || isOffline
        val mainIniContent = buildString {
            appendLine("[main::connectivity]")
            appendLine("disable_lan_only=${if (useOfflineConfig) 0 else 1}")
            if (useOfflineConfig) {
                appendLine("offline=1")
            }
        }

        if (Files.notExists(mainIni)) Files.createFile(mainIni)
        mainIni.toFile().writeText(mainIniContent)

        val controllerDir = settingsDir.resolve("controller")
        if (useSteamInput) {
            val controllerVdfText = SteamService.resolveSteamControllerVdfText(steamAppId)
            if (!controllerVdfText.isNullOrEmpty()) {
                runCatching {
                    SteamControllerVdfUtils.generateControllerConfig(controllerVdfText, controllerDir)
                }.onFailure { error ->
                    Timber.w(error, "Failed to generate controller config for $steamAppId")
                }
            }
        } else {
            runCatching {
                if (Files.exists(controllerDir)) {
                    controllerDir.toFile().deleteRecursively()
                }
            }.onFailure { error ->
                Timber.w(error, "Failed to delete controller config for $steamAppId")
            }
        }

        // Write supported languages list
        val supportedLanguagesFile = settingsDir.resolve("supported_languages.txt")
        if (Files.notExists(supportedLanguagesFile)) {
            Files.createFile(supportedLanguagesFile)
        }
        val supportedLanguages = listOf(
            "arabic",
            "bulgarian",
            "schinese",
            "tchinese",
            "czech",
            "danish",
            "dutch",
            "english",
            "finnish",
            "french",
            "german",
            "greek",
            "hungarian",
            "italian",
            "japanese",
            "koreana",
            "norwegian",
            "polish",
            "portuguese",
            "brazilian",
            "romanian",
            "russian",
            "spanish",
            "latam",
            "swedish",
            "thai",
            "turkish",
            "ukrainian",
            "vietnamese",
        )
        supportedLanguagesFile.toFile().writeText(supportedLanguages.joinToString("\n"))
    }

    /**
     * Generates cloud save configuration sections for configs.app.ini
     * Returns empty string if no Windows save patterns are found
     */
    private fun generateCloudSaveConfig(appInfo: SteamApp): String {
        // Filter to only Windows save patterns
        val windowsPatterns = appInfo.ufs.saveFilePatterns.filter { it.root.isWindows }

        return buildString {
            if (windowsPatterns.isNotEmpty()) {
                appendLine("[app::cloud_save::general]")
                appendLine("create_default_dir=1")
                appendLine("create_specific_dirs=1")
                appendLine()
                appendLine("[app::cloud_save::win]")
                val uniqueDirs = LinkedHashSet<String>()
                windowsPatterns.forEach { pattern ->
                    val root = if (pattern.root.name == "GameInstall") "gameinstall" else pattern.root.name
                    val path = pattern.path
                        .replace("{64BitSteamID}", "{::64BitSteamID::}")
                        .replace("{Steam3AccountID}", "{::Steam3AccountID::}")
                    uniqueDirs.add("{::$root::}/$path")
                }

                uniqueDirs.forEachIndexed { index, dir ->
                    appendLine("dir${index + 1}=$dir")
                }
            }
        }
    }

    private fun convertToWindowsPath(unixPath: String): String {
        // Find the drive_c component and convert everything after to Windows semantics
        val marker = "/drive_c/"
        val idx = unixPath.indexOf(marker)
        val tail = if (idx >= 0) {
            unixPath.substring(idx + marker.length)
        } else if (unixPath.contains("drive_c/")) {
            val i = unixPath.indexOf("drive_c/")
            unixPath.substring(i + "drive_c/".length)
        } else {
            // Fallback: best-effort replacement of leading wineprefix
            unixPath
        }
        val windowsTail = tail.replace('/', '\\')
        return "C:" + if (windowsTail.startsWith("\\")) windowsTail else "\\" + windowsTail
    }

    /**
     * Gets the Android user-editable device name or falls back to [HardwareUtils.getMachineName]
     */
    fun getMachineName(context: Context): String {
        return try {
            // Try different methods to get device name
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?: Settings.System.getString(context.contentResolver, "device_name")
                // ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                // ?: BluetoothAdapter.getDefaultAdapter()?.name
                ?: HardwareUtils.getMachineName() // Fallback to machine name if all else fails
        } catch (e: Exception) {
            HardwareUtils.getMachineName() // Return machine name as last resort
        }
    }

    // Set LoginID to a non-zero value if you have another client connected using the same account,
    // the same private ip, and same public ip.
    // source: https://github.com/Longi94/JavaSteam/blob/08690d0aab254b44b0072ed8a4db2f86d757109b/javasteam-samples/src/main/java/in/dragonbra/javasteamsamples/_000_authentication/SampleLogonAuthentication.java#L146C13-L147C56
    /**
     * This ID is unique to the device and app combination
     */
    @SuppressLint("HardwareIds")
    fun getUniqueDeviceId(context: Context): Int {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        return androidId.hashCode()
    }

    private fun skipFirstTimeSteamSetup(rootDir: File?) {
        val systemRegFile = File(rootDir, ImageFs.WINEPREFIX + "/system.reg")
        val redistributables = listOf(
            "DirectX\\Jun2010" to "DXSetup",              // DirectX Jun 2010
            ".NET\\3.5" to "3.5 SP1",              // .NET 3.5
            ".NET\\3.5 Client Profile" to "3.5 Client Profile SP1",
            ".NET\\4.0" to "4.0",                   // .NET 4.0
            ".NET\\4.0 Client Profile" to "4.0 Client Profile",
            ".NET\\4.5.2" to "4.5.2",
            ".NET\\4.6" to "4.6",
            ".NET\\4.7" to "4.7",
            ".NET\\4.8" to "4.8",
            "XNA\\3.0" to "3.0",                   // XNA 3.0
            "XNA\\3.1" to "3.1",
            "XNA\\4.0" to "4.0",
            "OpenAL\\2.0.7.0" to "2.0.7.0",               // OpenAL 2.0.7.0
            ".NET\\4.5.1" to "4.5.1",   // some Unity 5 titles
            ".NET\\4.6.1" to "4.6.1",   // Space Engineers, Far Cry 5 :contentReference[oaicite:1]{index=1}
            ".NET\\4.6.2" to "4.6.2",
            ".NET\\4.7.1" to "4.7.1",
            ".NET\\4.7.2" to "4.7.2",   // common fix loops :contentReference[oaicite:2]{index=2}
            ".NET\\4.8.1" to "4.8.1",
        )

        WineRegistryEditor(systemRegFile).use { registryEditor ->
            redistributables.forEach { (subPath, valueName) ->
                registryEditor.setDwordValue(
                    "Software\\Valve\\Steam\\Apps\\CommonRedist\\$subPath",
                    valueName,
                    1,
                )
                registryEditor.setDwordValue(
                    "Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\$subPath",
                    valueName,
                    1,
                )
            }
        }
    }

    fun fetchDirect3DMajor(steamAppId: Int, callback: (Int) -> Unit) {
        // Build a single Cargo query: SELECT API.direct3d_versions WHERE steam_appid="<appId>"
        Timber.i("[DX Fetch] Starting fetchDirect3DMajor for appId=%d", steamAppId)
        val where = URLEncoder.encode("Infobox_game.Steam_AppID HOLDS \"$steamAppId\"", "UTF-8")
        val url =
            "https://pcgamingwiki.com/w/api.php" +
                    "?action=cargoquery" +
                    "&tables=Infobox_game,AP" +
                    "I&join_on=Infobox_game._pageID=API._pageID" +
                    "&fields=API.Direct3D_versions" +
                    "&where=$where" +
                    "&format=json"

        Timber.i("[DX Fetch] Starting fetchDirect3DMajor for query=%s", url)

        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(-1)

            override fun onResponse(call: Call, res: Response) {
                res.use {
                    try {
                        val body = it.body?.string() ?: run { callback(-1); return }
                        Timber.i("[DX Fetch] Raw body fetchDirect3DMajor for body=%s", body)
                        val arr = JSONObject(body)
                            .optJSONArray("cargoquery") ?: run { callback(-1); return }

                        // There should be at most one row; take the first.
                        val raw = arr.optJSONObject(0)
                            ?.optJSONObject("title")
                            ?.optString("Direct3D versions")
                            ?.trim() ?: ""

                        Timber.i("[DX Fetch] Raw fetchDirect3DMajor for raw=%s", raw)

                        // Extract highest DX major number present.
                        val dx = Regex("\\b(9|10|11|12)\\b")
                            .findAll(raw)
                            .map { it.value.toInt() }
                            .maxOrNull() ?: -1

                        Timber.i("[DX Fetch] dx fetchDirect3DMajor is dx=%d", dx)

                        callback(dx)
                    } catch (e: Exception){
                        callback(-1)
                    }
                }
            }
        })
    }

    fun updateOrModifyLocalConfig(imageFs: ImageFs, container: Container, appId: String, steamUserId64: String) {
        try {
            val exeCommandLine = container.execArgs

            val steamPath = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")

            // Create necessary directories
            val userDataPath = File(steamPath, "userdata/$steamUserId64")
            val configPath = File(userDataPath, "config")
            configPath.mkdirs()

            val localConfigFile = File(configPath, "localconfig.vdf")

            if (localConfigFile.exists()) {
                val vdfContent = FileUtils.readFileAsString(localConfigFile.absolutePath)
                val vdfData = KeyValue.loadFromString(vdfContent!!)!!
                val app = vdfData["Software"]["Valve"]["Steam"]["apps"][appId]
                val option = app.children.firstOrNull { it.name == "LaunchOptions" }
                if (option != null) {
                    option.value = exeCommandLine.orEmpty()
                } else {
                    app.children.add(KeyValue("LaunchOptions", exeCommandLine))
                }

                vdfData.saveToFile(localConfigFile, false)
            } else {
                val vdfData = KeyValue(name = "UserLocalConfigStore")
                val option = KeyValue("LaunchOptions", exeCommandLine)
                val software = KeyValue("Software")
                val valve = KeyValue("Valve")
                val steam = KeyValue("Steam")
                val apps = KeyValue("apps")
                val app = KeyValue(appId)

                app.children.add(option)
                apps.children.add(app)
                steam.children.add(apps)
                valve.children.add(steam)
                software.children.add(valve)
                vdfData.children.add(software)

                vdfData.saveToFile(localConfigFile, false)
            }

            val userLanguage = container.language
            val steamappsDir = File(steamPath, "steamapps")
            val appManifestFile = File(steamappsDir, "appmanifest_$appId.acf")

            if (appManifestFile.exists()) {
                val manifestContent = FileUtils.readFileAsString(appManifestFile.absolutePath)
                val manifestData = KeyValue.loadFromString(manifestContent!!)!!

                val userConfig = manifestData.children.firstOrNull { it.name == "UserConfig" }
                if (userConfig != null) {
                    val languageKey = userConfig.children.firstOrNull { it.name == "language" }
                    if (languageKey != null) {
                        languageKey.value = userLanguage
                    } else {
                        userConfig.children.add(KeyValue("language", userLanguage))
                    }
                } else {
                    val newUserConfig = KeyValue("UserConfig")
                    newUserConfig.children.add(KeyValue("language", userLanguage))
                    manifestData.children.add(newUserConfig)
                }

                val mountedConfig = manifestData.children.firstOrNull { it.name == "MountedConfig" }
                if (mountedConfig != null) {
                    val languageKey = mountedConfig.children.firstOrNull { it.name == "language" }
                    if (languageKey != null) {
                        languageKey.value = userLanguage
                    } else {
                        mountedConfig.children.add(KeyValue("language", userLanguage))
                    }
                } else {
                    val newMountedConfig = KeyValue("MountedConfig")
                    newMountedConfig.children.add(KeyValue("language", userLanguage))
                    manifestData.children.add(newMountedConfig)
                }

                manifestData.saveToFile(appManifestFile, false)
                Timber.i("Updated app manifest language to $userLanguage for appId $appId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update or modify local config")
        }
    }

    fun getSteamId64(): Long? {
        return SteamService.userSteamId?.convertToUInt64()?.toLong()
    }

    fun getSteam3AccountId(): Long? {
        return SteamService.userSteamId?.accountID?.toLong()
    }

    /**
     * Ensures save locations for games that require special handling (e.g., symlinks)
     * This function checks if the current game needs any save location mappings
     * and applies them automatically.
     *
     * Supports placeholders in paths:
     * - {64BitSteamID} - Replaced with the user's 64-bit Steam ID
     * - {Steam3AccountID} - Replaced with the user's Steam3 account ID
     */
    fun ensureSaveLocationsForGames(context: Context, steamAppId: Int) {
        val mapping = SpecialGameSaveMapping.registry.find { it.appId == steamAppId } ?: return

        try {
            val accountId = SteamService.userSteamId?.accountID?.toLong() ?: 0L
            val steamId64 = SteamService.userSteamId?.convertToUInt64()?.toString() ?: "0"
            val steam3AccountId = accountId.toString()

            val basePath = mapping.pathType.toAbsPath(context, steamAppId, accountId)

            // Substitute placeholders in paths
            val sourceRelativePath = mapping.sourceRelativePath
                .replace("{64BitSteamID}", steamId64)
                .replace("{Steam3AccountID}", steam3AccountId)
            val targetRelativePath = mapping.targetRelativePath
                .replace("{64BitSteamID}", steamId64)
                .replace("{Steam3AccountID}", steam3AccountId)

            val sourcePath = File(basePath, sourceRelativePath)
            val targetPath = File(basePath, targetRelativePath)

            if (!sourcePath.exists()) {
                Timber.i("[${mapping.description}] Source save folder does not exist yet: ${sourcePath.absolutePath}")
                return
            }

            if (targetPath.exists()) {
                if (Files.isSymbolicLink(targetPath.toPath())) {
                    Timber.i("[${mapping.description}] Symlink already exists: ${targetPath.absolutePath}")
                    return
                } else {
                    Timber.w("[${mapping.description}] Target path exists but is not a symlink: ${targetPath.absolutePath}")
                    return
                }
            }

            targetPath.parentFile?.mkdirs()

            Files.createSymbolicLink(targetPath.toPath(), sourcePath.toPath())
            Timber.i("[${mapping.description}] Created symlink: ${targetPath.absolutePath} -> ${sourcePath.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "[${mapping.description}] Failed to create save location symlink")
        }
    }

    fun generateAchievementsFile(dllPath: Path, appId: String) {
        if (!SteamService.isLoggedIn) {
            Timber.w("Skipping achievements generation for $appId — Steam not logged in")
            return
        }

        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val settingsDir = dllPath.parent.resolve("steam_settings")
        if (Files.notExists(settingsDir)) {
            Files.createDirectories(settingsDir)
        }

        try {
            runBlocking {
                SteamService.generateAchievements(steamAppId, settingsDir.absolutePathString())
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate achievements for $appId")
        }
    }
}

