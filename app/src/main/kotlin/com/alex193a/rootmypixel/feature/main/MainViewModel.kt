package com.alex193a.rootmypixel.feature.main

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alex193a.rootmypixel.R
import com.alex193a.rootmypixel.core.Result
import com.alex193a.rootmypixel.domain.model.DeviceSnapshot
import com.alex193a.rootmypixel.domain.model.InstallPhase
import com.alex193a.rootmypixel.domain.model.InstallUiState
import com.alex193a.rootmypixel.domain.model.UnrootWarningUi
import com.alex193a.rootmypixel.domain.usecase.ResolveTargetUseCase
import com.alex193a.rootmypixel.feature.install.InstallActivity
import com.alex193a.rootmypixel.shizuku.ExploitService
import com.alex193a.rootmypixel.shizuku.IExploitService
import com.alex193a.rootmypixel.utils.NativeProbe
import com.alex193a.rootmypixel.utils.RootShellProbe
import com.alex193a.rootmypixel.utils.UnrootCommandOutcome
import com.alex193a.rootmypixel.utils.UnrootIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val resolveTargetUseCase: ResolveTargetUseCase by lazy {
        get(ResolveTargetUseCase::class.java)
    }

    private val mutableState = MutableStateFlow(InstallUiState())
    private val mutableShizukuAvailable = MutableStateFlow(false)
    private val mutableReSukiSuInstalled = MutableStateFlow(false)
    private val mutableUptimeExceeded = MutableStateFlow(false)
    private var refreshJob: Job? = null

    val state: StateFlow<InstallUiState> = mutableState.asStateFlow()
    val shizukuAvailable: StateFlow<Boolean> = mutableShizukuAvailable.asStateFlow()
    val reSukiSuInstalled: StateFlow<Boolean> = mutableReSukiSuInstalled.asStateFlow()
    val uptimeExceeded: StateFlow<Boolean> = mutableUptimeExceeded.asStateFlow()


    private val shizukuPermissionHandler = Handler(Looper.getMainLooper())
    private val shizukuListener = Shizuku.OnBinderReceivedListener {
        shizukuPermissionHandler.post { checkShizuku() }
    }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        shizukuPermissionHandler.post { mutableShizukuAvailable.value = false }
    }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_PERMISSION_CODE) {
            shizukuPermissionHandler.post { checkShizuku() }
        }
    }

    init {
        refresh()
    }

    fun initShizuku() {
        Shizuku.addBinderReceivedListener(shizukuListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        if (Shizuku.pingBinder()) {
            checkShizuku()
        }
    }

    private fun checkShizuku() {
        val available = try {
            Shizuku.pingBinder() &&
            Shizuku.isPreV11().not() &&
            Shizuku.getUid() == 2000 &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

        if (!available && Shizuku.pingBinder() && Shizuku.isPreV11().not()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
            }
        }

        mutableShizukuAvailable.value = available
    }

    override fun onCleared() {
        super.onCleared()
        Shizuku.removeBinderReceivedListener(shizukuListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = InstallUiState(phase = InstallPhase.Checking)
            mutableUptimeExceeded.value = SystemClock.elapsedRealtime() > UPTIME_THRESHOLD_MS

            try {
                mutableReSukiSuInstalled.value = app.packageManager
                    .getLaunchIntentForPackage("com.resukisu.resukisu") != null
                val kernelSuStatus = NativeProbe.kernelSuStatus()
                val probe = NativeProbe.run()
                if (kernelSuStatus.isActive) {
                    val rootTransport = findAvailableRootTransport()
                    mutableState.value = InstallUiState(
                        phase = InstallPhase.Installed,
                        message = app.getString(R.string.status_ksu_active),
                        probeOutput = probe,
                        log = buildString {
                            appendLine(probe)
                            appendLine(
                                "KernelSU UAPI root-profile grant for this app: " +
                                        kernelSuStatus.appRootGranted,
                            )
                            append(
                                rootTransport?.let {
                                    "[+] Unroot root transport verified: ${it.label}"
                                } ?: "[!] No usable root transport for Unroot",
                            )
                        },
                        canUnrootCurrentSession = rootTransport != null,
                    )
                    return@launch
                }
                val deviceInfo = NativeProbe.readDeviceSnapshot()
                val snapshot = DeviceSnapshot(
                    kernelRelease = deviceInfo.kernelRelease,
                    kernelVersion = deviceInfo.kernelVersion,
                    buildDisplay = deviceInfo.buildDisplay,
                    sdkVersion = deviceInfo.sdkVersion,
                    abi = deviceInfo.abi,
                    pageSize = deviceInfo.pageSize,
                    model = deviceInfo.model,
                    device = deviceInfo.device,
                )

                when (val result = resolveTargetUseCase(snapshot)) {
                    is Result.Success -> {
                        val profile = result.data
                        mutableState.value = InstallUiState(
                            phase = InstallPhase.Ready,
                            message = app.getString(R.string.status_not_installed),
                            probeOutput = probe,
                            log = buildString {
                                appendLine(probe)
                                appendLine("Matched profile: ${profile.profileId}")
                                appendLine("Device: ${deviceInfo.model} (${deviceInfo.device})")
                                appendLine("Kernel: ${deviceInfo.kernelRelease}")
                                appendLine("Build: ${deviceInfo.buildDisplay}")
                                appendLine("SDK: ${deviceInfo.sdkVersion}  ABI: ${deviceInfo.abi}")
                            },
                        )
                    }
                    is Result.Error -> {
                        mutableState.value = InstallUiState(
                            phase = InstallPhase.Failed,
                            message = app.getString(R.string.status_support_failed),
                            probeOutput = probe,
                            log = "$probe\n[-] ${result.error.message}",
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Failed,
                    message = app.getString(R.string.status_support_failed),
                    log = "[-] ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    fun install() {
        val intent = Intent(app, InstallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
    }

    fun softReboot() {
        viewModelScope.launch(Dispatchers.IO) {
            val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
            if (!helper.exists()) return@launch

            try {
                val result = runCatching {
                    val process = ProcessBuilder(
                        helper.absolutePath, "-c",
                        "killall -9 system_server 2>/dev/null; true"
                    ).redirectErrorStream(true).start()
                    process.inputStream.bufferedReader().use { it.readText() }
                    process.waitFor()
                }
                val output = result.getOrDefault("daemon unreachable")
                android.util.Log.i("RootMyPixel", "[softReboot] $output")
            } catch (_: Exception) { }
        }
    }

    fun exportLog() {
        val logFile = File(app.filesDir, "exploit.log")
        if (!logFile.exists()) return

        val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", logFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserIntent = Intent.createChooser(shareIntent, "Export exploit.log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(chooserIntent)
    }

    private data class ShizukuServiceHandle(
        val service: IExploitService,
        val conn: ServiceConnection,
    )

    private enum class RootTransport(val label: String) {
        AppSu("ReSukiSU app su"),
        AppCveHelper("current-install CVE helper"),
        ShizukuCveSu("current-install CVE su via Shizuku"),
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun bindExploitService(): ShizukuServiceHandle? {
        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ExploitService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("exploit_service")
            .version(1)

        var service: IExploitService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IExploitService.Stub.asInterface(binder)
                synchronized(this) {
                    (this as Object).notifyAll()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        Shizuku.bindUserService(args, conn)

        synchronized(conn as Object) {
            if (service == null) {
                try {
                    (conn as Object).wait(5000)
                } catch (_: InterruptedException) {
                }
            }
        }

        val svc = service ?: run {
            Shizuku.unbindUserService(args, conn, true)
            return null
        }
        return ShizukuServiceHandle(svc, conn)
    }

    private fun unbindExploitService(handle: ShizukuServiceHandle) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ExploitService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("exploit_service")
            .version(1)
        Shizuku.unbindUserService(args, handle.conn, true)
    }

    fun unrootAndReboot() {
        if (mutableState.value.busy ||
            mutableState.value.phase != InstallPhase.Installed ||
            !mutableState.value.canUnrootCurrentSession
        ) return

        viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = mutableState.value.copy(
                phase = InstallPhase.Checking,
                message = app.getString(R.string.status_unrooting),
                unrootWarning = null,
            )
            appendUnrootLog("[*] Starting verified unroot cleanup...")

            val script = runCatching {
                app.assets.open("unroot.sh").bufferedReader().use { it.readText() }
            }.getOrElse {
                showUnrootWarning(listOf(UnrootIssue.Unknown))
                return@launch
            }

            val outcome = executeUnrootScript(script)
            if (outcome.cleanupComplete && outcome.rebootRequested) {
                appendUnrootLog("[+] Cleanup complete; reboot requested")
                delay(3000.milliseconds)
                refresh()
            } else {
                val issues = outcome.issues.toMutableList()
                if (outcome.cleanupComplete && !outcome.rebootRequested) {
                    issues += UnrootIssue.Reboot
                }
                showUnrootWarning(issues)
            }
        }
    }

    fun continueUnrootReboot() {
        if (mutableState.value.unrootWarning == null) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = mutableState.value.copy(
                phase = InstallPhase.Checking,
                message = app.getString(R.string.status_unrooting),
                unrootWarning = null,
            )
            appendUnrootLog("[*] User requested reboot despite incomplete cleanup")
            if (!requestReboot()) {
                showUnrootWarning(listOf(UnrootIssue.Reboot))
            }
        }
    }

    fun cancelUnrootReboot() {
        mutableState.value = mutableState.value.copy(
            phase = InstallPhase.Installed,
            message = app.getString(R.string.status_unroot_incomplete),
            unrootWarning = null,
        )
        appendUnrootLog("[*] Reboot cancelled by user")
    }

    private fun executeUnrootScript(script: String): UnrootCommandOutcome {
        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        fun parseAttempt(transport: String, output: String): UnrootCommandOutcome? {
            val outcome = UnrootCommandOutcome.parse(output)
            appendUnrootLog("[*] $transport output:\n${output.ifBlank { "no output" }}")
            return if (outcome.cleanupComplete ||
                (outcome.hasStructuredOutput && !outcome.transportUnavailable)
            ) outcome else null
        }

        runCatching { runCommand(listOf("su", "-c", script)).output }
            .getOrNull()
            ?.let { parseAttempt("su", it) }
            ?.let { return it }

        if (helper.exists()) {
            runCatching { runCommand(listOf(helper.absolutePath, "-c", script)).output }
                .getOrNull()
                ?.let { parseAttempt("CVE helper", it) }
                ?.let { return it }
        }

        if (isShizukuShellActive()) {
            val handle = runCatching { bindExploitService() }.getOrNull()
            if (handle != null) {
                try {
                    val command = "$SHIZUKU_CVE_SU -c ${shellQuote(script)}"
                    parseAttempt("Shizuku CVE su", handle.service.exec(command))?.let { return it }
                } catch (error: Exception) {
                    appendUnrootLog("[-] Shizuku CVE unroot error: ${error.message}")
                } finally {
                    unbindExploitService(handle)
                }
            }
        }

        return UnrootCommandOutcome(
            cleanupComplete = false,
            rebootRequested = false,
            transportUnavailable = true,
            issues = UnrootIssue.affectedByMissingTransport,
            hasStructuredOutput = true,
        )
    }

    private fun requestReboot(): Boolean {
        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        val commands = buildList {
            add(listOf("su", "-c", REBOOT_COMMAND))
            if (helper.exists()) add(listOf(helper.absolutePath, "-c", REBOOT_COMMAND))
        }
        commands.forEach { command ->
            val output = runCatching { runCommand(command).output }.getOrDefault("")
            appendUnrootLog("[*] Reboot attempt: ${output.ifBlank { "no output" }}")
            if (output.contains("UNROOT_REBOOT_REQUESTED")) return true
        }

        if (isShizukuShellActive()) {
            val handle = runCatching { bindExploitService() }.getOrNull() ?: return false
            try {
                val output = handle.service.exec(
                    "$SHIZUKU_CVE_SU -c ${shellQuote(REBOOT_COMMAND)}",
                )
                appendUnrootLog("[*] Shizuku CVE reboot attempt: $output")
                return output.contains("UNROOT_REBOOT_REQUESTED")
            } catch (error: Exception) {
                appendUnrootLog("[-] Shizuku CVE reboot error: ${error.message}")
            } finally {
                unbindExploitService(handle)
            }
        }
        return false
    }

    private fun findAvailableRootTransport(): RootTransport? {
        val suResult = runCatching {
            runCommand(listOf("su", "-c", ROOT_ID_COMMAND), ROOT_PROBE_TIMEOUT_SECONDS)
        }.getOrNull()
        if (suResult != null && RootShellProbe.isRoot(suResult.code, suResult.output)) {
            return RootTransport.AppSu
        }

        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        if (helper.exists()) {
            val helperResult = runCatching {
                runCommand(
                    listOf(helper.absolutePath, "-c", ROOT_ID_COMMAND),
                    ROOT_PROBE_TIMEOUT_SECONDS,
                )
            }.getOrNull()
            if (helperResult != null &&
                RootShellProbe.isRoot(helperResult.code, helperResult.output)
            ) {
                return RootTransport.AppCveHelper
            }
        }

        if (!File(SHIZUKU_CVE_SU).exists() ||
            !File(SHIZUKU_CVE_SOCKET).exists() ||
            !isShizukuShellActive()
        ) return null

        val handle = runCatching { bindExploitService() }.getOrNull() ?: return null
        return try {
            val output = handle.service.exec("$SHIZUKU_CVE_SU -c '$ROOT_ID_COMMAND'")
            if (RootShellProbe.isRoot(0, output)) RootTransport.ShizukuCveSu else null
        } catch (_: Exception) {
            null
        } finally {
            unbindExploitService(handle)
        }
    }

    private fun isShizukuShellActive(): Boolean = try {
        Shizuku.pingBinder() &&
                Shizuku.isPreV11().not() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                Shizuku.getUid() == 2000
    } catch (_: Exception) {
        false
    }

    private fun runCommand(
        command: List<String>,
        timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS,
    ): CommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        return CommandResult(
            code = if (finished) process.exitValue() else COMMAND_TIMEOUT_CODE,
            output = process.inputStream.bufferedReader().use { it.readText() }.trim(),
        )
    }

    private fun showUnrootWarning(issues: List<UnrootIssue>) {
        val outcome = UnrootCommandOutcome(
            cleanupComplete = false,
            rebootRequested = false,
            transportUnavailable = UnrootIssue.RootTransport in issues,
            issues = issues.distinct(),
            hasStructuredOutput = true,
        )
        mutableState.value = mutableState.value.copy(
            phase = InstallPhase.Installed,
            message = app.getString(R.string.status_unroot_incomplete),
            canUnrootCurrentSession = findAvailableRootTransport() != null,
            unrootWarning = UnrootWarningUi(outcome.failedItemsText(app)),
        )
        appendUnrootLog("[!] Cleanup incomplete:\n${outcome.failedItemsText(app)}")
    }

    private fun appendUnrootLog(message: String) {
        android.util.Log.i("RootMyPixel", "[unroot] $message")
        mutableState.value = mutableState.value.copy(
            log = (mutableState.value.log + "\n" + message).trim(),
        )
    }

    companion object {
        private const val SHIZUKU_PERMISSION_CODE = 101
        private const val UPTIME_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
        private const val COMMAND_TIMEOUT_SECONDS = 90L
        private const val COMMAND_TIMEOUT_CODE = 124
        private const val ROOT_PROBE_TIMEOUT_SECONDS = 10L
        private const val ROOT_ID_COMMAND = "id -u"
        private const val SHIZUKU_CVE_SU = "/data/local/tmp/su"
        private const val SHIZUKU_CVE_SOCKET = "/data/local/tmp/temp_su.sock"
        private const val REBOOT_COMMAND =
            "sync; if svc power reboot || reboot; then " +
                    "echo UNROOT_REBOOT_REQUESTED; else echo UNROOT_FAIL:reboot:${'$'}?; fi"
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    private data class CommandResult(val code: Int, val output: String)
}
