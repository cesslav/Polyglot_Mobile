package com.example.polyglotapp
// This file is distributed under the open license AGPLv3, source code: https://github.com/cesslav/Polyglot_Mobile.
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelDownloadManager {

    private const val TAG      = "ModelDownloadManager"
    const val BASE_URL         = "http://igorpet.ru:9100"

    private val gson = Gson()

    fun fetchModelList(): List<ModelInfo> {
        val url  = URL("$BASE_URL/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout    = 30_000
        try {
            conn.connect()
            check(conn.responseCode == 200) {
                "Сервер вернул ${conn.responseCode}!!!"
            }

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
        val url  = URL("$BASE_URL/models/${model.file}")
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.connect()

            val totalBytes = conn.contentLengthLong
            val zipFile = File(destDir.parentFile, model.file)

            conn.inputStream.buffered().use { input ->
                zipFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var readBytes = 0L
                    var n: Int

                    onProgress(0, false)

                    while (input.read(buffer).also { n = it } != -1) {
                        output.write(buffer, 0, n)
                        readBytes += n

                        if (totalBytes > 0) {
                            val progress = ((readBytes * 100) / totalBytes)
                                .toInt()
                                .coerceIn(0, 100)

                            onProgress(progress, false)
                        }
                    }
                }
            }

            onProgress(null, true)

            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry

                while (entry != null) {
                    val outFile = File(destDir, entry.name)

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()

                        outFile.outputStream().buffered().use { out ->
                            val buffer = ByteArray(8 * 1024)
                            var n: Int
                            while (zis.read(buffer).also { n = it } != -1) {
                                out.write(buffer, 0, n)
                            }
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            zipFile.delete()

        } finally {
            conn.disconnect()
        }
    }
}
