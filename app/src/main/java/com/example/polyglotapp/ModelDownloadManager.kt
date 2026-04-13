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
        onProgress: (Int) -> Unit = {}
    ) {
        val url  = URL("$BASE_URL/models/${model.file}")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 60_000

        try {
            conn.connect()
            check(conn.responseCode == 200) {
                "Сервер вернул ${conn.responseCode} для ${model.file}"
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0 }
            var readBytes  = 0L

            ZipInputStream(conn.inputStream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.isDirectory) {
                        File(destDir, entry.name).mkdirs()
                    } else {
                        val outFile = File(destDir, entry.name)
                        require(outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                            "Подозрительный путь в архиве: ${entry.name}"
                        }
                        outFile.parentFile?.mkdirs()

                        outFile.outputStream().buffered().use { out ->
                            val buf = ByteArray(8 * 1024)
                            var n: Int
                            while (zis.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                readBytes += n
                                if (totalBytes != null) {
                                    onProgress((readBytes * 100 / totalBytes).toInt())
                                }
                            }
                        }

                        Log.d(TAG, "Распакован: ${outFile.name}")
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            onProgress(100)
            Log.d(TAG, "Модель '${model.name}' успешно установлена в $destDir")

        } finally {
            conn.disconnect()
        }
    }
}
