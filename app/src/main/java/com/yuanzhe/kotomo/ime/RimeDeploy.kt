package com.yuanzhe.kotomo.ime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import com.yuanzhe.kotomo.engine.Rime
import java.io.File

/**
 * Lays out librime's files under filesDir/rime and records what was deployed.
 *
 * Notes: `shared/` holds the data extracted from the assets, in device-protected storage so the
 * keyboard can read it before the first unlock (Direct Boot, PLAN §8.1); [lockedUser] is the
 * throwaway user directory librime uses until then. `user/` the deployed `build/` the
 * keyboard reads plus the user dictionary, `staging/` a deploy in progress. `deployed` names the
 * app version whose data `user/build` holds; `deploy.txt` the last deploy's time and memory;
 * `status.txt` what the deploy process is doing or why it failed (no typed text, only step
 * names and error messages).
 */
class RimeFiles(context: Context) {
    val root = File(context.filesDir, "rime")
    private val deviceRoot = File(context.createDeviceProtectedStorageContext().filesDir, "rime")
    val shared = File(deviceRoot, "shared")
    /** librime's user directory before the first unlock: device-protected, emptied each time, nothing learned. */
    val lockedUser = File(deviceRoot, "locked-user")
    val user = File(root, "user")
    val staging = File(root, "staging")
    private val deployed = File(root, "deployed")
    private val report = File(root, "deploy.txt")
    private val statusFile = File(root, "status.txt")

    /** Where a deploy stands, as the status file records it. */
    sealed interface Status {
        data object Running : Status
        data object Done : Status
        /** The app shipped compiled data; it was unpacked, nothing compiled on the phone. */
        data object Prebuilt : Status
        /** The build works, but some deploy tasks failed ([tasks] names them). */
        data class Partial(val tasks: String) : Status
        data class Failed(val reason: String) : Status
    }

    /** Records the deploy's [status] (deploy process). */
    fun writeStatus(status: Status) {
        root.mkdirs()
        statusFile.writeText(
            when (status) {
                Status.Running -> "running"
                Status.Done -> "done"
                Status.Prebuilt -> "prebuilt"
                is Status.Partial -> "partial ${status.tasks}"
                is Status.Failed -> "failed ${status.reason}"
            },
        )
    }

    /** Reads the recorded deploy status, or null before the first deploy. */
    fun readStatus(): Status? = runCatching { statusFile.readText() }.getOrNull()?.let {
        when {
            it == "running" -> Status.Running
            it == "done" -> Status.Done
            it == "prebuilt" -> Status.Prebuilt
            it.startsWith("partial ") -> Status.Partial(it.removePrefix("partial "))
            it.startsWith("failed ") -> Status.Failed(it.removePrefix("failed "))
            else -> null
        }
    }

    private val sharedStamp = File(shared, ".stamp")
    private val checkFile = File(root, "check.txt")

    /** Records what the keyboard found when it opened librime (keyboard process). */
    fun writeCheck(text: String) {
        root.mkdirs()
        checkFile.writeText(text)
    }

    /** Reads the keyboard's last librime check, or null. */
    fun readCheck(): String? = runCatching { checkFile.readText() }.getOrNull()

    /** Whether shared/ holds data compiled at build time (the app ships it when prebuilt). */
    val prebuilt get() = File(shared, "build/default.yaml").isFile

    /**
     * Unpacks the bundled data into shared/ unless this version is already there.
     * Returns: the milliseconds spent, or 0 when nothing had to be unpacked.
     * Side effects: replaces shared/; when the data is prebuilt, removes a user/build compiled on
     * the phone earlier, which librime would otherwise read first.
     */
    fun unpack(context: Context): Long {
        if (sharedStamp.isFile && sharedStamp.readText() == stamp) return 0
        val start = android.os.SystemClock.uptimeMillis()
        shared.deleteRecursively()
        // Builds before Direct Boot kept the shared data in credential-protected storage.
        File(root, "shared").deleteRecursively()
        copyAssets(context, "rime", shared)
        sharedStamp.writeText(stamp)
        if (prebuilt) {
            File(user, "build").deleteRecursively()
            deployed.delete()
        }
        return android.os.SystemClock.uptimeMillis() - start
    }

    /** Copies the asset tree at [path] into [dest]. */
    private fun copyAssets(context: Context, path: String, dest: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (child in children) copyAssets(context, "$path/$child", File(dest, child))
        }
    }

    /** Identifies the bundled data: the app's last update time, which changes with every install. */
    val stamp: String = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()

    /** Whether `user/build` exists, deployed from any version. */
    val hasBuild get() = File(user, "build").isDirectory

    /** Whether `user/build` holds this version's data. */
    val current get() = hasBuild && deployed.isFile && deployed.readText() == stamp

    /**
     * Moves a finished staged deploy into `user/` (engine thread, with librime shut down).
     * Returns: whether a staged build was there to move.
     */
    fun promote(): Boolean {
        val built = File(staging, "build")
        if (!built.isDirectory) return false
        val target = File(user, "build")
        target.deleteRecursively()
        user.mkdirs()
        if (!built.renameTo(target)) return false
        deployed.writeText(stamp)
        staging.deleteRecursively()
        return true
    }

    /** Writes the deploy report: milliseconds and the deploy process's peak memory in kilobytes. */
    fun writeReport(ms: Long, pssKb: Long, ok: Boolean) = report.writeText("$ms $pssKb $ok")

    /** Reads the deploy report as (milliseconds, peak memory in kilobytes), or null. */
    fun readReport(): Pair<Long, Long>? = runCatching {
        val (ms, pss) = report.readText().trim().split(" ")
        ms.toLong() to pss.toLong()
    }.getOrNull()
}

/**
 * Deploys librime's data in its own process (`:deploy`), so the keyboard keeps typing Chinese with
 * the previous data meanwhile and the deploy's memory peak stays out of the keyboard's process
 * (PLAN §4.2).
 *
 * Notes: the keyboard binds to it when the bundled data changed; the work starts on bind, runs on a
 * worker thread (this process's only librime caller) and ends with a broadcast [ACTION_DONE] to
 * this package. It extracts the assets into `shared/`, deploys into `staging/` and writes the
 * report; the keyboard then promotes the staged build.
 */
class RimeDeployService : Service() {
    private var started = false

    override fun onBind(intent: Intent): IBinder {
        if (!started) {
            started = true
            Thread(::deploy, "kotomo-deploy").start()
        }
        return Binder()
    }

    private fun deploy() {
        val files = RimeFiles(this)
        files.writeStatus(RimeFiles.Status.Running)
        val start = SystemClock.uptimeMillis()
        // Each step names itself, so a failure on a device can be read off the diagnostics page.
        var step = "extract"
        var failedTasks = ""
        val failure: String? = try {
            files.unpack(this)
            step = "load"
            files.staging.deleteRecursively()
            // librime creates build/ with std::filesystem before compiling default.yaml and only
            // logs a failure; on a device build/ stayed empty, so it is created here first.
            File(files.staging, "build").mkdirs()
            // initialize may fail to open a session before anything is deployed; deploy anyway.
            Rime.initialize(files.shared.path, files.staging.path)
            step = "deploy"
            Rime.deploy()
            failedTasks = Rime.deployFailures()
            Rime.shutdown()
            // The keyboard needs the compiled main schema; a failed side task (cleanup, user
            // dictionary upgrade) does not stop it from typing.
            if (File(files.staging, "build/$SCHEMA.schema.yaml").isFile) {
                null
            } else {
                // What librime produced and what it read from tell which deploy step broke.
                fun list(dir: File) = dir.listFiles().orEmpty().sortedBy { it.name }
                    .joinToString(", ") { if (it.isDirectory) "${it.name}/" else "${it.name} ${it.length()}" }
                    .ifEmpty { "empty" }
                "no compiled $SCHEMA (failed tasks: ${failedTasks.ifEmpty { "none" }}); " +
                    "build: ${list(File(files.staging, "build"))}; shared: ${list(files.shared)}"
            }
        } catch (e: Throwable) {
            "$step: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        }
        files.writeReport(SystemClock.uptimeMillis() - start, peakRssKb(), failure == null)
        files.writeStatus(
            when {
                failure != null -> RimeFiles.Status.Failed(failure)
                failedTasks.isNotEmpty() -> RimeFiles.Status.Partial(failedTasks)
                else -> RimeFiles.Status.Done
            },
        )
        sendBroadcast(Intent(ACTION_DONE).setPackage(packageName).putExtra(EXTRA_OK, failure == null))
    }

    /** Reads this process's peak resident memory (VmHWM) in kilobytes, or 0. */
    private fun peakRssKb(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong()
    }.getOrDefault(0L)

    companion object {
        const val ACTION_DONE = "com.yuanzhe.kotomo.RIME_DEPLOYED"
        const val EXTRA_OK = "ok"
        /** The schema the keyboard opens (core/rime/data/default.custom.yaml). */
        const val SCHEMA = "luna_pinyin_simp"
    }
}
