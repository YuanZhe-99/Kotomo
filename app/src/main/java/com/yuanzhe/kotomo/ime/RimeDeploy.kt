package com.yuanzhe.kotomo.ime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import com.yuanzhe.kotomo.engine.Rime
import java.io.File
import java.io.IOException

/**
 * Lays out librime's files under filesDir/rime and records what was deployed.
 *
 * Notes: `shared/` holds the data extracted from the assets, `user/` the deployed `build/` the
 * keyboard reads plus the user dictionary, `staging/` a deploy in progress. `deployed` names the
 * app version whose data `user/build` holds; `deploy.txt` the last deploy's time and memory.
 */
class RimeFiles(context: Context) {
    val root = File(context.filesDir, "rime")
    val shared = File(root, "shared")
    val user = File(root, "user")
    val staging = File(root, "staging")
    private val deployed = File(root, "deployed")
    private val report = File(root, "deploy.txt")

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
        val start = SystemClock.uptimeMillis()
        val ok = try {
            files.shared.deleteRecursively()
            copyAssets("rime", files.shared)
            files.staging.deleteRecursively()
            files.staging.mkdirs()
            val deployed = Rime.initialize(files.shared.path, files.staging.path) && Rime.deploy()
            Rime.shutdown()
            deployed
        } catch (_: IOException) {
            false
        }
        files.writeReport(SystemClock.uptimeMillis() - start, peakRssKb(), ok)
        sendBroadcast(Intent(ACTION_DONE).setPackage(packageName).putExtra(EXTRA_OK, ok))
    }

    /** Reads this process's peak resident memory (VmHWM) in kilobytes, or 0. */
    private fun peakRssKb(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong()
    }.getOrDefault(0L)

    /** Copies the asset tree at [path] into [dest]. */
    private fun copyAssets(path: String, dest: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (child in children) copyAssets("$path/$child", File(dest, child))
        }
    }

    companion object {
        const val ACTION_DONE = "com.yuanzhe.kotomo.RIME_DEPLOYED"
        const val EXTRA_OK = "ok"
    }
}
