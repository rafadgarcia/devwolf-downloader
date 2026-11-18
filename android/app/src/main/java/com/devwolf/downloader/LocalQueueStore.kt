package com.devwolf.downloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Thread-safe persistent JSON store for local Android download jobs so queue and history
 * survive app backgrounding or process restart.
 */
class LocalQueueStore internal constructor(
    private val queueFile: File,
    private val shareFile: File,
) {
    constructor(context: Context) : this(
        queueFile = File(context.applicationContext.filesDir, "devwolf_local_queue.json"),
        shareFile = File(context.applicationContext.filesDir, "devwolf_pending_share.json"),
    )

    private val lock = Any()

    fun upsertJob(snapshot: JSONObject) {
        synchronized(lock) {
            val jobs = loadMapLocked()
            val jobId = snapshot.optString("jobId")
            if (jobId.isNotEmpty()) {
                snapshot.put("updatedAt", Instant.now().toString())
                jobs[jobId] = snapshot
                persistMapLocked(jobs)
            }
        }
    }

    fun getJob(jobId: String): JSONObject? {
        synchronized(lock) {
            return loadMapLocked()[jobId]?.let { JSONObject(it.toString()) }
        }
    }

    fun listJobs(): JSONArray {
        synchronized(lock) {
            val list = loadMapLocked().values.sortedByDescending { it.optString("updatedAt", "") }
            val arr = JSONArray()
            for (item in list.take(100)) {
                arr.put(item)
            }
            return arr
        }
    }

    fun markInterruptedJobsOnStartup() {
        synchronized(lock) {
            val jobs = loadMapLocked()
            var changed = false
            for ((_, job) in jobs) {
                val status = job.optString("status")
                if (status in ACTIVE_STATUSES) {
                    job.put("status", "failed")
                    job.put("phaseLabel", "Falhou")
                    job.put("error", "Download interrompido pelo sistema. Toque para tentar novamente.")
                    job.put("errorCode", "EXTRACTION_FAILED")
                    job.put("technicalReason", "Processo interrompido durante execução em segundo plano.")
                    job.put("updatedAt", Instant.now().toString())
                    changed = true
                }
            }
            if (changed) {
                persistMapLocked(jobs)
            }
        }
    }

    /**
     * Clears persisted download history entries from `devwolf_local_queue.json`,
     * optionally preserving currently active in-flight jobs.
     */
    fun clearHistory(keepActiveJobs: Boolean = true): Int {
        synchronized(lock) {
            val jobs = loadMapLocked()
            if (jobs.isEmpty()) return 0
            val remaining = if (keepActiveJobs) {
                jobs.filterValues { it.optString("status") in ACTIVE_STATUSES }
            } else {
                emptyMap()
            }
            val removedCount = jobs.size - remaining.size
            if (removedCount > 0 || !keepActiveJobs) {
                persistMapLocked(remaining)
            }
            return removedCount
        }
    }

    fun savePendingShareIntent(payload: JSONObject) {
        synchronized(lock) {
            writeAtomically(shareFile, payload.toString())
        }
    }

    fun getPendingShareIntent(): JSONObject? {
        synchronized(lock) {
            if (!shareFile.exists()) return null
            return try {
                JSONObject(shareFile.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun clearPendingShareIntent(id: String?) {
        synchronized(lock) {
            if (!shareFile.exists()) return
            if (id.isNullOrBlank()) {
                shareFile.delete()
                return
            }
            val current = getPendingShareIntent()
            if (current == null || current.optString("id") == id) {
                shareFile.delete()
            }
        }
    }

    private fun loadMapLocked(): MutableMap<String, JSONObject> {
        val result = LinkedHashMap<String, JSONObject>()
        if (!queueFile.exists()) return result
        try {
            val arr = JSONArray(queueFile.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("jobId")
                if (id.isNotEmpty()) {
                    result[id] = obj
                }
            }
        } catch (_: Exception) {
            // Corrupted store resets cleanly
        }
        return result
    }

    private fun persistMapLocked(jobs: Map<String, JSONObject>) {
        val arr = JSONArray()
        val sorted = jobs.values.sortedByDescending { it.optString("updatedAt", "") }.take(MAX_PERSISTED_JOBS)
        sorted.forEachIndexed { index, job ->
            if (index < MAX_JOBS_WITH_THUMBNAILS) {
                arr.put(job)
            } else {
                arr.put(stripThumbnailsCopy(job))
            }
        }
        writeAtomically(queueFile, arr.toString())
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_PERSISTED_JOBS = 100
        const val MAX_JOBS_WITH_THUMBNAILS = 24
        internal val ACTIVE_STATUSES = setOf("queued", "resolving", "downloading", "processing")

        internal fun stripThumbnailsCopy(job: JSONObject): JSONObject {
            val copy = JSONObject(job.toString())
            copy.remove("thumbnailDataUrl")
            for (arrayKey in listOf("files", "items")) {
                val items = copy.optJSONArray(arrayKey) ?: continue
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.remove("thumbnailDataUrl")
                }
            }
            return copy
        }
    }
}
