package com.example.polyglotapp
// This file is distributed under the open license AGPLv3, source code: https://github.com/cesslav/Polyglot_Mobile.
import android.content.ContentValues.TAG
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private var tokenizer: UnigramTokenizer? = null
    private var model: OnnxTransformer? = null
    private var isReady = false

    private lateinit var modelSpinner: Spinner
    private lateinit var inputEdit:    EditText
    private lateinit var runButton:    MaterialButton
    private lateinit var outputText:   TextView

    private lateinit var downloadsList:   RecyclerView
    private lateinit var downloadsStatus: TextView

    private lateinit var label:               TextView
    private lateinit var translateMenuButton: MaterialButton
    private lateinit var downloadsMenuButton: MaterialButton
    private lateinit var settingsMenuButton:  MaterialButton

    private lateinit var settingsContainer:  LinearLayout
    private lateinit var seekbarSrcLen:      SeekBar
    private lateinit var seekbarMaxLen:      SeekBar
    private lateinit var settingSrcLenValue: TextView
    private lateinit var settingMaxLenValue: TextView

    private var maxSrcLen: Int = DEFAULT_SRC_LEN
    private var maxLen:    Int = DEFAULT_MAX_LEN

    private val installedModels = mutableListOf<Pair<String, String>>()
    private var selectedModelStem: String? = null
    private lateinit var downloadsAdapter: DownloadsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("LICENSE", "// This file is distributed under the open license AGPLv3, source code: https://github.com/cesslav/Polyglot_Mobile.")
        setContentView(R.layout.layout)

        modelSpinner         = findViewById(R.id.model_spinner)
        inputEdit            = findViewById(R.id.input_text)
        runButton            = findViewById(R.id.button)
        outputText           = findViewById(R.id.output_text)
        label                = findViewById(R.id.main_label)
        translateMenuButton  = findViewById(R.id.translate_menu_button)
        downloadsMenuButton  = findViewById(R.id.downloads_menu_button)
        settingsMenuButton   = findViewById(R.id.settings_menu_button)
        downloadsList        = findViewById(R.id.downloads_list)
        downloadsStatus      = findViewById(R.id.downloads_status)

        settingsContainer    = findViewById(R.id.settings_container)
        seekbarSrcLen        = findViewById(R.id.seekbar_src_len)
        seekbarMaxLen        = findViewById(R.id.seekbar_max_len)
        settingSrcLenValue   = findViewById(R.id.setting_src_len_value)
        settingMaxLenValue   = findViewById(R.id.setting_max_len_value)

        downloadsList.layoutManager = LinearLayoutManager(this)

        runButton.isEnabled = false
        modelSpinner.setEnabled(false)

        loadSettings()
        setupSettingsScreen()

        translateMenuButton.setOnClickListener { showTranslateScreen() }
        settingsMenuButton.setOnClickListener  { showSettingsScreen()  }
        downloadsMenuButton.setOnClickListener { showDownloadsScreen() }

        runButton.setOnClickListener {
            Log.d(TAG, "Button clicked!")
            if (!isReady) return@setOnClickListener
            val text = inputEdit.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            runButton.isEnabled = false
            modelSpinner.setEnabled(false)
            outputText.text = "Обрабатывается…"

            lifecycleScope.launch(Dispatchers.IO) {
                val result = runInference(text)
                withContext(Dispatchers.Main) {
                    outputText.text = result
                    runButton.isEnabled = true
                    modelSpinner.setEnabled(true)
                }
            }
        }

        showTranslateScreen()
    }


    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        maxSrcLen = prefs.getInt(KEY_SRC_LEN, DEFAULT_SRC_LEN)
        maxLen    = prefs.getInt(KEY_MAX_LEN, DEFAULT_MAX_LEN)
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SRC_LEN, maxSrcLen)
            .putInt(KEY_MAX_LEN, maxLen)
            .apply()
    }


    /**
     * SeekBar progress → actual value mapping:
     *   srcLen  : progress ∈ [0..7] → value = (progress + 1) * 64  → [64, 128, 192, 256, 320, 384, 448, 512]
     *   maxLen  : progress ∈ [0..7] → value = (progress + 1) * 32  → [32,  64,  96, 128, 160, 192, 224, 256]
     */
    private fun srcLenFromProgress(p: Int) = (p + 1) * 64
    private fun srcLenToProgress(v: Int)   = (v / 64 - 1).coerceIn(0, 7)
    private fun maxLenFromProgress(p: Int) = (p + 1) * 32
    private fun maxLenToProgress(v: Int)   = (v / 32 - 1).coerceIn(0, 7)

    private fun setupSettingsScreen() {
        seekbarSrcLen.progress = srcLenToProgress(maxSrcLen)
        seekbarMaxLen.progress = maxLenToProgress(maxLen)
        settingSrcLenValue.text = maxSrcLen.toString()
        settingMaxLenValue.text = maxLen.toString()

        seekbarSrcLen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val value = srcLenFromProgress(progress)
                settingSrcLenValue.text = value.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                maxSrcLen = srcLenFromProgress(sb.progress)
                saveSettings()
                Log.d(TAG, "maxSrcLen changed to $maxSrcLen")
            }
        })

        seekbarMaxLen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val value = maxLenFromProgress(progress)
                settingMaxLenValue.text = value.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                maxLen = maxLenFromProgress(sb.progress)
                saveSettings()
                Log.d(TAG, "maxLen changed to $maxLen")
            }
        })
    }

    private fun showTranslateScreen() {
        label.text = "Полиглот"
        translateMenuButton.setBackgroundColor(resources.getColor(R.color.dark))
        settingsMenuButton.setBackgroundColor(resources.getColor(R.color.light))
        downloadsMenuButton.setBackgroundColor(resources.getColor(R.color.light))

        downloadsList.visibility    = View.GONE
        downloadsStatus.visibility  = View.GONE
        settingsContainer.visibility = View.GONE

        inputEdit.visibility   = View.VISIBLE
        runButton.visibility   = View.VISIBLE
        outputText.visibility  = View.VISIBLE

        refreshSpinner()
    }

    private fun showSettingsScreen() {
        label.text = "Настройки"
        translateMenuButton.setBackgroundColor(resources.getColor(R.color.light))
        settingsMenuButton.setBackgroundColor(resources.getColor(R.color.dark))
        downloadsMenuButton.setBackgroundColor(resources.getColor(R.color.light))

        modelSpinner.visibility      = View.GONE
        inputEdit.visibility         = View.GONE
        runButton.visibility         = View.GONE
        outputText.visibility        = View.GONE
        downloadsList.visibility     = View.GONE
        downloadsStatus.visibility   = View.GONE

        settingsContainer.visibility = View.VISIBLE
    }

    private fun showDownloadsScreen() {
        label.text = "Загрузки"
        translateMenuButton.setBackgroundColor(resources.getColor(R.color.light))
        settingsMenuButton.setBackgroundColor(resources.getColor(R.color.light))
        downloadsMenuButton.setBackgroundColor(resources.getColor(R.color.dark))

        modelSpinner.visibility      = View.GONE
        inputEdit.visibility         = View.GONE
        runButton.visibility         = View.GONE
        outputText.visibility        = View.GONE
        settingsContainer.visibility = View.GONE

        downloadsList.visibility    = View.VISIBLE
        downloadsStatus.visibility  = View.VISIBLE

        loadDownloadsList()
    }

    private fun refreshSpinner() {
        val found = mutableListOf<Pair<String, String>>()

        filesDir.listFiles()
            ?.filter { it.isDirectory && isModelDir(it) }
            ?.sortedBy { it.name }
            ?.forEach { found.add(it.name to makeDisplayName(it.name)) }

        if (isModelDir(filesDir)) {
            found.add(0, "" to "Модель по умолчанию(RU -> EN)")
        }

        installedModels.clear()
        installedModels.addAll(found)

        if (installedModels.isEmpty()) {
            modelSpinner.visibility = View.GONE
            outputText.text = "Модели не установлены. Перейдите в «Загрузки»."
            runButton.isEnabled = false
            modelSpinner.setEnabled(false)
            return
        }

        modelSpinner.visibility = View.VISIBLE

        val displayNames = installedModels.map { it.second }
        val adapter = ArrayAdapter(this, R.layout.spinner_list, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        modelSpinner.onItemSelectedListener = null
        modelSpinner.adapter = adapter

        val restoredPos = installedModels.indexOfFirst { it.first == selectedModelStem }
            .takeIf { it >= 0 } ?: 0
        modelSpinner.setSelection(restoredPos, false)

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val stem = installedModels[pos].first
                if (stem != selectedModelStem) {
                    selectedModelStem = stem
                    loadModel(stem)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        val targetStem = installedModels[restoredPos].first
        if (targetStem != selectedModelStem || !isReady) {
            selectedModelStem = targetStem
            loadModel(targetStem)
        } else {
            outputText.text = "Готово. Введите текст и нажмите кнопку."
            runButton.isEnabled = true
            modelSpinner.setEnabled(true)
        }
    }

    private fun isModelDir(dir: File): Boolean =
        File(dir, "encoder.onnx").exists() &&
        File(dir, "decoder.onnx").exists() &&
        File(dir, "tokenizer/tokenizer.json").exists()

    private fun loadModel(stem: String) {
        isReady = false
        runButton.isEnabled = false
        modelSpinner.setEnabled(false)
        outputText.text = "Загрузка модели…"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                model?.close()
                val modelDir = if (stem.isEmpty()) filesDir else File(filesDir, stem)
                tokenizer = UnigramTokenizer(applicationContext, modelDir)
                model     = OnnxTransformer(applicationContext, modelDir)
                isReady   = true
                withContext(Dispatchers.Main) {
                    runButton.isEnabled = true
                    modelSpinner.setEnabled(true)
                    outputText.text = "Готово. Введите текст и нажмите кнопку."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    outputText.text = "Ошибка загрузки модели: ${e.message}"
                    Log.e(TAG, "loadModel($stem) failed", e)
                }
            }
        }
    }

    private fun makeDisplayName(stem: String): String {
        val parts = stem.split("-")
        return if (parts.size >= 2 && parts.take(2).all { it.length <= 3 && it.all(Char::isLetter) }) {
            val arrow  = "${parts[0].uppercase()} → ${parts[1].uppercase()}"
            val suffix = parts.drop(2).joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
            if (suffix.isNotEmpty()) "$arrow $suffix" else arrow
        } else {
            parts.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        }
    }

    private fun loadDownloadsList() {
        downloadsStatus.text = "Загрузка списка моделей…"
        downloadsList.adapter = null

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val serverModels = ModelDownloadManager.fetchModelList()
                Log.d("Downloads", "$serverModels")

                val installedStems = buildInstalledStems().toMutableSet()

                withContext(Dispatchers.Main) {
                    downloadsStatus.text = if (serverModels.isEmpty()) {
                        "Доступных моделей нет."
                    } else {
                        "Доступные модели:"
                    }
                    downloadsAdapter = DownloadsAdapter(
                        items = serverModels,
                        installedStems = installedStems,
                        onDownload = ::startDownload,
                        onDelete = ::deleteModel
                    )

                    downloadsList.adapter = downloadsAdapter
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadsStatus.text = "Ошибка: ${e.message}"
                    Log.e("Downloads", "fetchModelList failed", e)
                }
            }
        }
    }

    private fun buildInstalledStems(): Set<String> {
        val stems = mutableSetOf<String>()
        if (isModelDir(filesDir)) stems.add("")
        filesDir.listFiles()
            ?.filter { it.isDirectory && isModelDir(it) }
            ?.forEach { stems.add(it.name) }
        return stems
    }

    private fun startDownload(modelInfo: ModelInfo) {
        val stem = modelInfo.file.removeSuffix(".zip")
        val destDir = File(filesDir, stem).also { it.mkdirs() }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ModelDownloadManager.downloadAndExtract(
                    model = modelInfo,
                    destDir = destDir,
                    onProgress = { progress ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            downloadsAdapter.updateProgress(modelInfo.file, progress)
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    downloadsAdapter.markDone(modelInfo.file)
                    Toast.makeText(this@MainActivity,
                        "Модель «${modelInfo.name}» установлена",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadsAdapter.markDone(modelInfo.file)
                    Toast.makeText(this@MainActivity,
                        "Ошибка загрузки: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }


    }

    private fun deleteModel(modelInfo: ModelInfo) {
        val stem = modelInfo.file.removeSuffix(".zip")
        val dir = File(filesDir, stem)

        lifecycleScope.launch(Dispatchers.IO) {
            dir.deleteRecursively()

            withContext(Dispatchers.Main) {
                downloadsAdapter.removeInstalled(modelInfo.file)
                Toast.makeText(
                    this@MainActivity,
                    "Модель удалена",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun runInference(text: String): String {
        val tok = tokenizer ?: return "Токенайзер не загружен"
        val mdl = model     ?: return "Модель не загружена"

        val srcTokens = tok.encode(text, maxSrcLen)
        val memory    = mdl.encode(srcTokens, maxSrcLen)
        val modelDim  = memory.size / maxSrcLen
        val outTokens = GreedySearch.search(
            model    = mdl,
            memory   = memory,
            srcLen   = maxSrcLen,
            modelDim = modelDim,
            maxLen   = maxLen,
            bosId    = tok.bosId.toLong(),
            eosId    = tok.eosId.toLong()
        )
        return tok.decode(outTokens)
    }

    override fun onDestroy() {
        super.onDestroy()
        model?.close()
    }

    companion object {
        private const val PREFS_NAME      = "polyglot_settings"
        private const val KEY_SRC_LEN     = "max_src_len"
        private const val KEY_MAX_LEN     = "max_len"
        private const val DEFAULT_SRC_LEN = 256
        private const val DEFAULT_MAX_LEN = 128
    }
}
