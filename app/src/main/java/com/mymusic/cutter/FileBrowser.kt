package com.mymusic.cutter

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The app's own file manager (window.MYmusicFiles): lists the folders and the audio/video files
 * of the internal storage and SD cards directly from the file system, so browsing is fast.
 * Sorting and the different views are done by the page.
 */
class FileBrowser(private val activity: MainActivity, private val webView: WebView) {
    private val exec = Executors.newSingleThreadExecutor()

    companion object {
        val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "wma", "aiff", "aif", "mka", "3ga", "mid", "midi")
        val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "3gp", "3gpp", "mov", "avi", "ts", "m2ts", "mts", "flv", "wmv", "mpg", "mpeg", "ogv", "vob", "divx")
        fun kindOf(name: String): String? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) { in AUDIO -> "audio"; in VIDEO -> "video"; else -> null }
        }
    }

    private fun send(fn: String, job: Int, data: String) {
        webView.post { webView.evaluateJavascript("window.$fn&&window.$fn($job,$data)", null) }
    }

    @JavascriptInterface
    fun hasPermission(): Boolean = activity.hasMediaPermission()

    @JavascriptInterface
    fun requestPermission() {
        activity.runOnUiThread { activity.requestMediaPermission() }
    }

    /** Internal storage and every SD card / USB drive that is mounted. */
    @JavascriptInterface
    fun roots(): String {
        val arr = JSONArray()
        val seen = HashSet<String>()
        fun add(name: String, dir: File?) {
            if (dir == null || !dir.exists()) return
            val p = dir.absolutePath
            if (!seen.add(p)) return
            arr.put(JSONObject().put("name", name).put("path", p)
                .put("free", dir.freeSpace).put("total", dir.totalSpace))
        }
        @Suppress("DEPRECATION")
        add("Internal storage", Environment.getExternalStorageDirectory())
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val sm = activity.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                for (v in sm.storageVolumes) {
                    if (v.isPrimary) continue
                    add(v.getDescription(activity) ?: "SD card", v.directory)
                }
            } else {
                for (d in activity.getExternalFilesDirs(null)) {
                    if (d == null) continue
                    val p = d.absolutePath
                    val i = p.indexOf("/Android/")
                    if (i > 0) add("SD card", File(p.substring(0, i)))
                }
            }
        } catch (_: Exception) {}
        return arr.toString()
    }

    /** One folder: its sub-folders and its audio/video files (answer goes to window.__fmList). */
    @JavascriptInterface
    fun list(path: String, job: Int, showHidden: Boolean) {
        exec.execute {
            val out = JSONObject()
            try {
                val dir = File(path)
                out.put("path", dir.absolutePath)
                out.put("parent", dir.parentFile?.absolutePath ?: "")
                val items = JSONArray()
                val children = dir.listFiles()
                if (children == null) out.put("error", "This folder cannot be opened (no permission).")
                children?.forEach { f ->
                    val name = f.name
                    if (!showHidden && name.startsWith(".")) return@forEach
                    if (f.isDirectory) {
                        items.put(JSONObject().put("name", name).put("path", f.absolutePath).put("dir", true)
                            .put("mtime", f.lastModified()).put("count", f.list()?.size ?: 0))
                    } else {
                        val k = kindOf(name) ?: return@forEach
                        items.put(JSONObject().put("name", name).put("path", f.absolutePath).put("dir", false)
                            .put("mtime", f.lastModified()).put("size", f.length()).put("kind", k))
                    }
                }
                out.put("items", items)
            } catch (e: Exception) {
                out.put("error", e.message ?: "This folder cannot be opened.")
                out.put("items", JSONArray())
            }
            send("__fmList", job, out.toString())
        }
    }

    /** All audio/video files inside the given folders (and files), optionally with sub-folders. */
    @JavascriptInterface
    fun collect(pathsJson: String, recursive: Boolean, job: Int) {
        exec.execute {
            val result = JSONArray()
            var count = 0
            fun addFile(f: File) {
                val k = kindOf(f.name) ?: return
                if (count >= 5000) return
                result.put(JSONObject().put("name", f.name).put("path", f.absolutePath)
                    .put("size", f.length()).put("mtime", f.lastModified()).put("kind", k))
                count++
            }
            val cmp = Comparator<File> { a, b -> naturalCompare(a.name, b.name) }
            fun walk(dir: File, depth: Int) {
                val kids = dir.listFiles() ?: return
                val files = kids.filter { it.isFile && !it.name.startsWith(".") }.sortedWith(cmp)
                for (f in files) addFile(f)
                if (recursive && depth < 20) {
                    for (d in kids.filter { it.isDirectory && !it.name.startsWith(".") }.sortedWith(cmp)) walk(d, depth + 1)
                }
            }
            try {
                val arr = JSONArray(pathsJson)
                for (i in 0 until arr.length()) {
                    val f = File(arr.getString(i))
                    if (f.isDirectory) walk(f, 0) else if (f.isFile) addFile(f)
                }
            } catch (_: Exception) {}
            send("__fmCollected", job, result.toString())
        }
    }

    private fun naturalCompare(a: String, b: String): Int {
        val re = Regex("(\\d+)|(\\D+)")
        val ra = re.findAll(a.lowercase()).map { it.value }.toList()
        val rb = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }
}
