package com.alex193a.rootmypixel.feature.install

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alex193a.rootmypixel.R
import com.alex193a.rootmypixel.core.Result
import com.alex193a.rootmypixel.domain.model.DeviceSnapshot
import com.alex193a.rootmypixel.domain.model.InstallPhase
import com.alex193a.rootmypixel.domain.model.InstallUiState
import com.alex193a.rootmypixel.domain.model.PayloadExecutionError
import com.alex193a.rootmypixel.domain.model.PayloadReason
import com.alex193a.rootmypixel.domain.model.PayloadResultParser
import com.alex193a.rootmypixel.domain.model.TargetProfile
import com.alex193a.rootmypixel.domain.model.UnrootWarningUi
import com.alex193a.rootmypixel.domain.model.VerifiedPayloads
import com.alex193a.rootmypixel.domain.usecase.DownloadPayloadsUseCase
import com.alex193a.rootmypixel.domain.usecase.ResolveTargetUseCase
import com.alex193a.rootmypixel.shizuku.ExploitService
import com.alex193a.rootmypixel.shizuku.IExploitService
import com.alex193a.rootmypixel.utils.KernelSuInstallChecks
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

data class TargetCatalogUiState(
    val loading: Boolean = false,
    val profiles: List<TargetProfile> = emptyList(),
    val error: String? = null,
)

class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val resolveTargetUseCase: ResolveTargetUseCase by lazy {
        get(ResolveTargetUseCase::class.java)
    }
    private val downloadPayloadsUseCase: DownloadPayloadsUseCase by lazy {
        get(DownloadPayloadsUseCase::class.java)
    }

    private val mutableState = MutableStateFlow(InstallUiState())
    private val mutableTargetCatalog = MutableStateFlow(TargetCatalogUiState())
    private var discoveryJob: Job? = null
    private var installJob: Job? = null
    private var unrootCapabilityJob: Job? = null

    val state: StateFlow<InstallUiState> = mutableState.asStateFlow()
    val targetCatalog: StateFlow<TargetCatalogUiState> = mutableTargetCatalog.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (installJob?.isActive == true) return
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val probe = NativeProbe.run()
                val deviceInfo = NativeProbe.readDeviceSnapshot()
                val kernelSuStatus = NativeProbe.kernelSuStatus()
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
                val result = resolveTargetUseCase(snapshot)
                when (result) {
                    is Result.Success -> {
                        val profile = result.data
                        mutableState.value = InstallUiState(
                            phase = InstallPhase.Ready,
                            message = app.getString(R.string.status_not_installed),
                            probeOutput = probe,
                            log = "$probe\n${app.getString(
                                R.string.log_profile, profile.profileId)}",
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

    fun install(profileId: String? = null, permissiveOnly: Boolean = false) {
        if (installJob?.isActive == true ||
            mutableState.value.phase == InstallPhase.Installed) return
        discoveryJob?.cancel()

        installJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = InstallUiState(
                phase = InstallPhase.Checking,
                probeOutput = mutableState.value.probeOutput,
            )
            try {
                setPhase(InstallPhase.Checking, app.getString(R.string.status_checking))
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

                val profile = when {
                    profileId != null -> {
                        when (val r = resolveTargetUseCase(profileId)) {
                            is Result.Success -> r.data
                            is Result.Error ->
                                throw IllegalStateException(r.error.message)
                        }
                    }
                    else -> {
                        when (val r = resolveTargetUseCase(snapshot)) {
                            is Result.Success -> r.data
                            is Result.Error ->
                                throw IllegalStateException(r.error.message)
                        }
                    }
                }
                appendLog(app.getString(R.string.log_profile, profile.profileId))

                setPhase(InstallPhase.Downloading, "Preparing payloads…")
                val payloads = when (
                    val r = downloadPayloadsUseCase(profile) { appendLog("[*] $it") }
                ) {
                    is Result.Success -> r.data
                    is Result.Error ->
                        throw IllegalStateException(r.error.message)
                }
                appendLog("Payloads extracted from APK")

                val useShizuku = hasShizukuPermission()
                require(useShizuku) {
                    app.getString(R.string.error_shizuku_required)
                }
                appendLog("[*] Using Shizuku shell access: $useShizuku")

                setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit))
                when (val exploitResult = executeExploit(payloads)) {
                    is Result.Success -> Unit
                    is Result.Error -> {
                        val error = exploitResult.error
                        appendLog("[-] ${error.message}")
                        mutableState.value = mutableState.value.copy(
                            phase = InstallPhase.Failed,
                            message = error.message,
                            retryAllowed = error.retryable,
                        )
                        return@launch
                    }
                }

                if (permissiveOnly) {
                    setPhase(InstallPhase.Installed, "SELinux permissive + root shell ready")
                    appendLog("Install complete — permissive mode, KernelSU skipped")
                    val rootTransport = findAvailableRootTransport()
                    mutableState.value = mutableState.value.copy(
                        canUnrootCurrentSession = rootTransport != null,
                    )
                    appendLog(
                        rootTransport?.let {
                            "[+] Unroot root transport verified: ${it.label}"
                        } ?: "[!] Current-install root shell is unavailable",
                    )
                } else {
                    setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_loading_ksu))
                    installKernelSu(payloads)

                    setPhase(InstallPhase.Installed, app.getString(R.string.status_ksu_active))
                    appendLog(app.getString(R.string.log_install_complete))
                    val rootTransport = findAvailableRootTransport()
                    mutableState.value = mutableState.value.copy(
                        canUnrootCurrentSession = rootTransport != null,
                    )
                    appendLog(
                        if (rootTransport != null) {
                            "[+] Unroot root transport verified: ${rootTransport.label}"
                        } else {
                            "[!] No usable root transport for Unroot; grant this app root in ReSukiSU Manager"
                        },
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                appendLog("[-] ${error.message ?: error.javaClass.simpleName}")
                setPhase(InstallPhase.Failed, app.getString(R.string.status_install_failed))
            }
        }
    }

    // --- Shizuku UserService helpers ---

    private data class ShizukuServiceHandle(
        val service: IExploitService,
        val conn: ServiceConnection,
    )

    private enum class RootTransport(val label: String) {
        AppSu("ReSukiSU app su"),
        AppCveHelper("current-install CVE helper"),
        ShizukuCveSu("current-install CVE su via Shizuku"),
    }

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

        // Wait up to 5 seconds for connection
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

    // --- Exploit execution ---

    private suspend fun executeExploit(
        payloads: VerifiedPayloads,
    ): Result<Unit, PayloadExecutionError> {
        return when (val result = executeExploitViaShizuku(payloads)) {
            is Result.Success -> {
                appendLog(app.getString(R.string.log_bootstrap_root))
                Result.Success(Unit)
            }
            is Result.Error -> result
        }
    }

    private suspend fun executeExploitViaShizuku(
        payloads: VerifiedPayloads,
    ): Result<Unit, PayloadExecutionError> {
        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        require(helper.exists()) { app.getString(R.string.error_helper_unavailable) }

        val handle = bindExploitService()
            ?: throw IllegalStateException("Failed to bind Shizuku UserService")

        try {
            val logPrefix = mutableState.value.log
            handle.service.startExploit(
                payloads.exploit.readBytes(),
                helper.readBytes(),
                "/data/local/tmp/exploit.log",
            )

            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""

            while (handle.service.isRunning) {
                val remoteLog = handle.service.getLog()
                val fileLog = handle.service.exec("cat /data/local/tmp/exploit.log 2>/dev/null || true")
                val currentLog = if (fileLog.length > remoteLog.length) fileLog else remoteLog

                if (currentLog != lastRawLog) {
                    publishLog(logPrefix, currentLog)
                    lastRawLog = currentLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                val now = SystemClock.elapsedRealtime()
                require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                    app.getString(R.string.error_exploit_stalled)
                }
                require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                    app.getString(R.string.error_exploit_timeout)
                }
                delay(LOG_POLL_INTERVAL)
            }

            val exitCode = handle.service.waitFor()
            val remoteLog = handle.service.getLog()
            val fileLog = handle.service.exec("cat /data/local/tmp/exploit.log 2>/dev/null || true")
            val finalLog = listOf(remoteLog, fileLog)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("\n")
            if (finalLog.isNotBlank()) {
                publishLog(logPrefix, finalLog)
            }

            if (exitCode != 0) {
                return Result.Error(
                    PayloadExecutionError(
                        message = app.getString(R.string.error_payload_exit, exitCode, ""),
                        retryable = true,
                    ),
                )
            }

            val outcome = when (val parsed = PayloadResultParser.parse(finalLog)) {
                is Result.Success -> parsed.data
                is Result.Error -> {
                    if (PayloadResultParser.hasLegacySuccessMarkers(finalLog)) {
                        return Result.Success(Unit)
                    }
                    return Result.Error(
                        PayloadExecutionError(
                            message = parsed.error.message,
                            retryable = false,
                        ),
                    )
                }
            }
            if (!outcome.success) {
                val message = if (outcome.reason == PayloadReason.ROUTE_DISABLED) {
                    app.getString(R.string.error_route_disabled)
                } else {
                    "Payload failed: ${outcome.reason.name}"
                }
                return Result.Error(
                    PayloadExecutionError(
                        message = message,
                        retryable = outcome.retryable,
                        reason = outcome.reason,
                    ),
                )
            }
            if (!PayloadResultParser.hasLegacySuccessMarkers(finalLog)) {
                return Result.Error(
                    PayloadExecutionError(
                        message = app.getString(R.string.error_success_marker),
                        retryable = false,
                        reason = outcome.reason,
                    ),
                )
            }
            return Result.Success(Unit)
        } finally {
            unbindExploitService(handle)
        }
    }

    // Shizuku helpers

    private fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() &&
            Shizuku.isPreV11().not() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
            Shizuku.getUid() == 2000
        } catch (_: Exception) {
            false
        }
    }

    // --- KernelSU ---

    private fun installKernelSu(payloads: VerifiedPayloads) {
        val ksudSource = payloads.kernelSu.absolutePath
        val ksudDest = "/data/local/tmp/ksud-pixel"
        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")

        // 1. Wait for daemon to be ready
        awaitDaemonSocket()
        diagnoseDaemon()

        // 2. Stage ksud via daemon root (cp + chmod + chown)
        appendLog("[*] Staging ReSukiSU binary...")
        val stageCmd = "cp '$ksudSource' $ksudDest && chmod 755 $ksudDest && " +
            "chown root:root $ksudDest"
        var stageSuccess = false
        for (attempt in 1..5) {
            val result = runHelper(helper, "-c", stageCmd)
            if (result.code == 0) {
                val verify = runHelper(helper, "-c", "ls -la $ksudDest")
                if (verify.output.contains("rwxr-xr-x") ||
                    verify.output.contains("-rwxr-xr-x")) {
                    appendLog("ReSukiSU staged: ${verify.output.trim()}")
                    stageSuccess = true
                    break
                }
            }
            appendLog("[!] Stage attempt $attempt: code=${result.code} ${result.output.take(120)}")
            Thread.sleep(1000)
        }
        require(stageSuccess) {
            app.getString(R.string.error_ksu_stage, "stage failed after 5 attempts")
        }

        // 3. Execute late-load via daemon root
        appendLog("[*] Triggering KernelSU late-load (kmi=${payloads.kmi})...")
        val lateResult = runHelper(helper, "-c",
            "$ksudDest late-load --kmi ${payloads.kmi}")
        if (lateResult.output.isNotBlank()) {
            appendLog(lateResult.output.take(2000))
        }

        // 4. Verify the driver itself. ReSukiSU LKM mode does not create the
        // legacy filesystem paths that were previously probed here.
        verifyKernelSuLoaded(helper, ksudDest, lateResult)

        // 5. Register only a known ReSukiSU production manager signature.
        // Package name alone is not a sufficient trust boundary for a root manager.
        registerManager(helper, ksudDest)

        appendLog(app.getString(R.string.log_ksu_control_verified))
    }

    private fun verifyKernelSuLoaded(
        helper: File,
        ksudDest: String,
        lateResult: CommandResult,
    ) {
        var nativeStatus = NativeProbe.KernelSuStatus()
        var debugResult = CommandResult(-1, "not attempted")
        var moduleResult = CommandResult(-1, "not attempted")

        for (attempt in 1..10) {
            nativeStatus = NativeProbe.kernelSuStatus()
            if (nativeStatus.isActive) {
                appendLog(
                    "[+] KernelSU verified through UAPI (attempt $attempt): " +
                        "version=${nativeStatus.version} flags=0x${nativeStatus.flags.toString(16)} " +
                        "uapi=${nativeStatus.uapiVersion}",
                )
                return
            }

            debugResult = runHelper(helper, "-c", "$ksudDest debug info")
            if (debugResult.code == 0 &&
                KernelSuInstallChecks.debugInfoShowsActiveKernelSu(debugResult.output)
            ) {
                appendLog(
                    "[+] KernelSU verified through ksud (attempt $attempt):\n" +
                        debugResult.output.take(500),
                )
                return
            }

            moduleResult = runHelper(helper, "-c", "grep '^kernelsu ' /proc/modules")
            if (moduleResult.code == 0 &&
                KernelSuInstallChecks.procModulesShowsActiveKernelSu(moduleResult.output)
            ) {
                appendLog(
                    "[+] KernelSU verified through /proc/modules (attempt $attempt): " +
                        moduleResult.output.take(200),
                )
                return
            }

            Thread.sleep(500)
        }

        val diagnostics = buildString {
            append("late-load output: ")
            append(lateResult.output.ifBlank { "<empty>" }.take(300))
            append("; native probe: present=${nativeStatus.driverPresent}, ")
            append("responsive=${nativeStatus.driverResponsive}, version=${nativeStatus.version}")
            append("; ksud debug (${debugResult.code}): ")
            append(debugResult.output.ifBlank { "<empty>" }.take(300))
            append("; /proc/modules (${moduleResult.code}): ")
            append(moduleResult.output.ifBlank { "<empty>" }.take(200))
        }
        throw IllegalStateException(
            app.getString(R.string.error_ksu_verify, lateResult.code, diagnostics),
        )
    }

    private fun registerManager(helper: File, ksudDest: String) {
        val apkPath = runCatching {
            app.packageManager.getApplicationInfo(
                RESUKISU_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(0),
            ).sourceDir
        }.getOrNull()

        if (apkPath.isNullOrBlank()) {
            appendLog("[!] ReSukiSU manager not installed — skipping registration")
            return
        }

        appendLog("[*] Verifying the installed ReSukiSU manager signature...")
        val signatureResult = runHelper(
            helper,
            "-c",
            "$ksudDest debug get-sign ${shellQuote(apkPath)}",
        )
        if (signatureResult.code != 0) {
            appendLog(
                "[!] Manager signature verification failed (${signatureResult.code}): " +
                    signatureResult.output.ifBlank { "no output" }.take(200),
            )
            return
        }

        val signature = KernelSuInstallChecks.parseManagerSignature(signatureResult.output)
        if (signature == null || !KernelSuInstallChecks.isTrustedManagerSignature(signature)) {
            appendLog(
                "[!] Installed manager signature is not trusted; registration skipped: " +
                    signatureResult.output.ifBlank { "unrecognised output" }.take(200),
            )
            return
        }

        appendLog("[*] Registering the ReSukiSU manager with the module...")
        val setResult = runHelper(
            helper,
            "-c",
            "$ksudDest kernel dynamic-manager set ${signature.size} ${signature.hash}",
        )
        if (setResult.code != 0) {
            appendLog(
                "[!] Manager registration failed (${setResult.code}): " +
                    setResult.output.ifBlank { "no output" }.take(200),
            )
            return
        }

        val getResult = runHelper(helper, "-c", "$ksudDest kernel dynamic-manager get")
        val registeredSignature = if (getResult.code == 0) {
            KernelSuInstallChecks.parseManagerSignature(getResult.output)
        } else {
            null
        }
        if (registeredSignature != signature) {
            appendLog(
                "[!] Manager registration could not be confirmed (${getResult.code}): " +
                    getResult.output.ifBlank { "no output" }.take(200),
            )
            return
        }

        appendLog("[+] ReSukiSU manager registered and verified")
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    private fun runHelper(helper: File, vararg arguments: String): CommandResult {
        for (attempt in 1..5) {
            val process = ProcessBuilder(listOf(helper.absolutePath) + arguments)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor()
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val result = CommandResult(
                if (finished) process.exitValue() else COMMAND_TIMEOUT_CODE,
                output.trim(),
            )

            val transient = result.output.contains("No such file or directory") ||
                result.output.contains("Connection refused") ||
                result.code == 127
            if (!transient || attempt == 5) {
                return result
            }
            Thread.sleep(1500)
        }
        return CommandResult(1, "runHelper: exhausted retries")
    }

    fun refreshUnrootAvailability() {
        if (installJob?.isActive == true ||
            mutableState.value.phase != InstallPhase.Installed ||
            unrootCapabilityJob?.isActive == true
        ) return

        unrootCapabilityJob = viewModelScope.launch(Dispatchers.IO) {
            val rootTransport = findAvailableRootTransport()
            mutableState.value = mutableState.value.copy(
                canUnrootCurrentSession = rootTransport != null,
            )
            appendLog(
                rootTransport?.let {
                    "[+] Unroot root transport verified: ${it.label}"
                }
                    ?: "[!] No usable root transport for Unroot; grant this app root in ReSukiSU Manager",
            )
        }
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
            !hasShizukuPermission()
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

    fun unrootCurrentSession() {
        if (mutableState.value.phase != InstallPhase.Installed ||
            !mutableState.value.canUnrootCurrentSession
        ) return

        viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = mutableState.value.copy(
                phase = InstallPhase.Checking,
                message = app.getString(R.string.status_unrooting),
                canUnrootCurrentSession = false,
            )
            appendLog("[*] Verifying an Unroot root transport...")

            val rootTransport = findAvailableRootTransport()
            if (rootTransport == null) {
                appendLog("[-] Neither the app su grant nor the current CVE root shell is available")
                showUnrootWarning(UnrootIssue.affectedByMissingTransport, canRetry = false)
                return@launch
            }
            appendLog("[+] Using ${rootTransport.label}")

            val command = runCatching {
                app.assets.open("unroot.sh").bufferedReader().use { it.readText() }
            }.getOrElse {
                showUnrootWarning(listOf(UnrootIssue.Unknown))
                return@launch
            }
            appendLog("[*] Removing privileged root state...")
            val outcome = executeUnrootScript(command)
            if (outcome.cleanupComplete && outcome.rebootRequested) {
                appendLog("[+] Cleanup complete; reboot requested")
            } else {
                val issues = outcome.issues.toMutableList()
                if (outcome.cleanupComplete && !outcome.rebootRequested) {
                    issues += UnrootIssue.Reboot
                }
                if (!outcome.hasStructuredOutput) issues += UnrootIssue.Unknown
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
            appendLog("[*] User requested reboot despite incomplete cleanup")

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
        appendLog("[*] Reboot cancelled by user")
    }

    private fun showUnrootWarning(
        issues: List<UnrootIssue>,
        canRetry: Boolean? = null,
    ) {
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
            canUnrootCurrentSession = canRetry ?: (findAvailableRootTransport() != null),
            unrootWarning = UnrootWarningUi(outcome.failedItemsText(app)),
        )
    }

    private fun executeUnrootScript(script: String): UnrootCommandOutcome {
        fun parseAttempt(transport: String, result: CommandResult): UnrootCommandOutcome? {
            val outcome = UnrootCommandOutcome.parse(result.output)
            appendLog(
                "[*] $transport output (exit=${result.code}):\n" +
                        result.output.ifBlank { "no output" },
            )
            return if (outcome.cleanupComplete ||
                (outcome.hasStructuredOutput && !outcome.transportUnavailable)
            ) outcome else null
        }

        runCatching { runCommand(listOf("su", "-c", script)) }
            .getOrNull()
            ?.let { parseAttempt("ReSukiSU app su", it) }
            ?.let { return it }

        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        if (helper.exists()) {
            runCatching { runCommand(listOf(helper.absolutePath, "-c", script)) }
                .getOrNull()
                ?.let { parseAttempt("current-install CVE helper", it) }
                ?.let { return it }
        }

        if (hasShizukuPermission() &&
            File(SHIZUKU_CVE_SU).exists() &&
            File(SHIZUKU_CVE_SOCKET).exists()
        ) {
            val handle = runCatching { bindExploitService() }.getOrNull()
            if (handle != null) {
                try {
                    val output = handle.service.exec(
                        "$SHIZUKU_CVE_SU -c ${shellQuote(script)}",
                    )
                    parseAttempt("current-install CVE su via Shizuku", CommandResult(0, output))
                        ?.let { return it }
                } catch (error: Exception) {
                    appendLog("[-] Shizuku CVE unroot error: ${error.message}")
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
            appendLog("[*] Reboot attempt: ${output.ifBlank { "no output" }}")
            if (output.contains("UNROOT_REBOOT_REQUESTED")) return true
        }

        if (!hasShizukuPermission() ||
            !File(SHIZUKU_CVE_SU).exists() ||
            !File(SHIZUKU_CVE_SOCKET).exists()
        ) return false

        val handle = runCatching { bindExploitService() }.getOrNull() ?: return false
        return try {
            val output = handle.service.exec(
                "$SHIZUKU_CVE_SU -c ${shellQuote(REBOOT_COMMAND)}",
            )
            appendLog("[*] Shizuku CVE reboot attempt: $output")
            output.contains("UNROOT_REBOOT_REQUESTED")
        } catch (error: Exception) {
            appendLog("[-] Shizuku CVE reboot error: ${error.message}")
            false
        } finally {
            unbindExploitService(handle)
        }
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

    private fun awaitDaemonSocket() {
        val sock = File("/data/local/tmp/temp_su.sock")
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sock.exists()) return
            Thread.sleep(500)
        }
    }

    private fun diagnoseDaemon() {
        try {
            val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
            if (!helper.exists()) {
                appendLog("[diag] helper binary missing")
                return
            }
            val suCheck = runHelper(helper, "-c",
                "ls -la /apex/com.android.virt/bin/su /data/local/tmp/su 2>/dev/null || echo 'not found'")
            appendLog("[diag] su binaries: ${suCheck.output.take(200)}")

            val sockCheck = File("/data/local/tmp/temp_su.sock")
            appendLog("[diag] socket file: ${if (sockCheck.exists()) "present" else "NOT FOUND"}")

            val logCheck = runHelper(helper, "-c",
                "cat /data/local/tmp/su_daemon.log 2>/dev/null || echo 'empty'")
            appendLog("[diag] daemon log: ${logCheck.output.take(300)}")
        } catch (e: Exception) {
            appendLog("[diag] error: ${e.message}")
        }
    }

    fun softReboot() {
        viewModelScope.launch(Dispatchers.IO) {
            val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
            if (!helper.exists()) return@launch
            val result = runHelper(helper, "-c",
                "killall -9 system_server 2>/dev/null; true")
            appendLog("[*] Soft reboot triggered (exit ${result.code})")
        }
    }

    // --- UI helpers ---

    private fun setPhase(phase: InstallPhase, message: String) {
        mutableState.value = mutableState.value.copy(phase = phase, message = message)
        appendLog("[*] $message")
    }

    private fun publishLog(prefix: String, rawLog: String) {
        val sanitizedLog = PayloadResultParser.sanitizeForDisplay(rawLog)
        mutableState.value = mutableState.value.copy(
            log = listOf(prefix, sanitizedLog)
                .filter(String::isNotBlank)
                .joinToString("\n")
                .takeLast(MAX_LOG_CHARS),
        )
    }

    private fun appendLog(line: String) {
        val cleanLine = line.trim()
        if (cleanLine.isBlank()) return
        mutableState.value = mutableState.value.copy(
            log = (mutableState.value.log + "\n" + cleanLine)
                .trim()
                .takeLast(MAX_LOG_CHARS),
        )
    }

    data class CommandResult(val code: Int, val output: String)

    companion object {
        private const val EXPLOIT_STALL_MILLIS = 600_000L
        private const val EXPLOIT_TOTAL_MILLIS = 1_800_000L
        private const val MAX_LOG_CHARS = 5 * 1024 * 1024
        private const val COMMAND_TIMEOUT_SECONDS = 90L
        private const val COMMAND_TIMEOUT_CODE = 124
        private const val ROOT_PROBE_TIMEOUT_SECONDS = 10L
        private const val ROOT_ID_COMMAND = "id -u"
        private const val SHIZUKU_CVE_SU = "/data/local/tmp/su"
        private const val SHIZUKU_CVE_SOCKET = "/data/local/tmp/temp_su.sock"
        private val LOG_POLL_INTERVAL = 250.milliseconds
        private const val RESUKISU_PACKAGE = "com.resukisu.resukisu"
        private const val REBOOT_COMMAND =
            "sync; if svc power reboot || reboot; then " +
                    "echo UNROOT_REBOOT_REQUESTED; else echo UNROOT_FAIL:reboot:${'$'}?; fi"
    }
}
