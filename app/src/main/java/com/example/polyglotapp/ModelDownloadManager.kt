package com.example.polyglotapp
// This file is distributed under the open license AGPLv3, source code: https://github.com/cesslav/Polyglot_Mobile.

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelDownloadManager {
    private const val TAG = "ModelDownloadManager"
    const val DEFAULT_BASE_URL = "http://igorpet.ru:9100"
    var BASE_URL = DEFAULT_BASE_URL
    private val gson = Gson()


    fun ping(url: String): Boolean {
        val conn = (URL("$url/ping").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            conn.connect()
            if (conn.responseCode != 200) return false
            val body = conn.inputStream.bufferedReader().readText()
            val map = gson.fromJson(body, Map::class.java)
            map["answer"] == "available"
        } catch (e: Exception) {
            Log.e(TAG, "ping($url) failed", e)
            false
        } finally {
            conn.disconnect()
        }
    }

    fun fetchModelList(): List<ModelInfo> {
        val conn = (URL("$BASE_URL/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            conn.connect()
            check(conn.responseCode == 200) { "Сервер вернул ${conn.responseCode}" }
            val body = conn.inputStream.bufferedReader().readText()
            val type = object : TypeToken<List<ModelInfo>>() {}.type
            return gson.fromJson(body, type)
        } finally {
            conn.disconnect()
        }
    }

    fun downloadAndExtract(
        model: ModelInfo,
        destDir: File,
        onProgress: (progress: Int?, isInstalling: Boolean) -> Unit
    ) {
        val parentDir = destDir.parentFile ?: destDir
        val partFile = File(parentDir, "${model.file}.part")
        val resumeFrom = if (partFile.exists()) partFile.length() else 0L

        val conn = (URL("$BASE_URL/models/${model.file}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (resumeFrom > 0L) {
                setRequestProperty("Range", "bytes=$resumeFrom-")
                Log.d(TAG, "Attempting resume from byte $resumeFrom for ${model.file}")
            }
        }

        try {
            conn.connect()

            val code = conn.responseCode
            val totalBytes: Long
            val appendMode: Boolean

            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val contentRange = conn.getHeaderField("Content-Range")
                    totalBytes = contentRange
                        ?.substringAfterLast('/')
                        ?.trim()
                        ?.toLongOrNull()
                        ?: (resumeFrom + conn.contentLengthLong)
                    appendMode = true
                    Log.i(TAG, "Resume supported. Already have $resumeFrom / $totalBytes bytes.")
                }
                HttpURLConnection.HTTP_OK -> {
                    partFile.delete()
                    totalBytes = conn.contentLengthLong
                    appendMode = false
                    Log.i(TAG, "No resume support. Fresh download, total = $totalBytes bytes.")
                }
                else -> throw IOException("HTTP $code: ${conn.responseMessage}")
            }

            var downloaded = if (appendMode) resumeFrom else 0L

            FileOutputStream(partFile, appendMode).buffered(BUF_SIZE).use { out ->
                conn.inputStream.buffered(BUF_SIZE).use { inp ->
                    val buf = ByteArray(BUF_SIZE)
                    var n: Int
                    onProgress(
                        if (totalBytes > 0L) ((downloaded * 100) / totalBytes).toInt() else 0,
                        false
                    )
                    while (inp.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        downloaded += n
                        if (totalBytes > 0L) {
                            onProgress(
                                ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100),
                                false
                            )
                        }
                    }
                }
            }

            onProgress(null, true)

            ZipInputStream(partFile.inputStream().buffered(BUF_SIZE)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name).canonicalFile
                    require(outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator) ||
                            outFile.canonicalPath == destDir.canonicalPath) {
                        "Zip Slip detected: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().buffered(BUF_SIZE).use { out ->
                            val buf = ByteArray(BUF_SIZE)
                            var n: Int
                            while (zis.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            partFile.delete()
            Log.i(TAG, "Download & extract complete: ${model.file}")

        } finally {
            conn.disconnect()
        }
    }
    private const val BUF_SIZE = 32 * 1024
}
