package com.example.polyglotapp
// This file is distributed under the open license AGPLv3, source code: https://github.com/cesslav/Polyglot_Mobile.
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var inputCharCounter: TextView
    private lateinit var runButton:    MaterialButton
    private lateinit var outputText:   TextView
    private lateinit var header:    LinearLayout
    private lateinit var downloadsList:   RecyclerView
    private lateinit var downloadsStatus: TextView

    private lateinit var translateMenuButton: MaterialButton
    private lateinit var downloadsMenuButton: MaterialButton
    private lateinit var settingsMenuButton:  MaterialButton

    private lateinit var settingsContainer:        ScrollView
    private lateinit var seekbarSrcLen:            SeekBar
    private lateinit var seekbarMaxLen:            SeekBar
    private lateinit var settingSrcLenValue:       TextView
    private lateinit var settingMaxLenValue:       TextView
    private lateinit var settingServerUrl:         EditText
    private lateinit var settingServerButton:      MaterialButton
    private lateinit var settingResetButton:       MaterialButton
    //private lateinit var settingStreamingSwitch:   SwitchCompat

    private var maxSrcLen: Int     = DEFAULT_SRC_LEN
    private var maxLen:    Int     = DEFAULT_MAX_LEN
    private var streamingEnabled: Boolean = DEFAULT_STREAMING

    private val installedModels = mutableListOf<Pair<String, String>>()
    private var selectedModelStem: String? = null
    private lateinit var downloadsAdapter: DownloadsAdapter

    private val COLOR_NEON_GREEN  = Color.parseColor("#5cb84b")
    private val COLOR_NAV_ACTIVE  = Color.parseColor("#1F3A1F")
    private val COLOR_NAV_INACTIVE = Color.parseColor("#000000")
    private val COLOR_TEXT_INACTIVE = Color.parseColor("#FFFFFF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("LICENSE",
            "// This file is distributed under the open license AGPLv3, " +
            "source code: https://github.com/cesslav/Polyglot_Mobile.")
        setContentView(R.layout.layout)
        WindowCompat.setDecorFitsSystemWindows(window, false)


        modelSpinner          = findViewById(R.id.model_spinner)
        inputEdit             = findViewById(R.id.input_text)
        inputCharCounter      = findViewById(R.id.input_char_counter)
        runButton             = findViewById(R.id.button)
        outputText            = findViewById(R.id.output_text)
        header                = findViewById(R.id.header)
        translateMenuButton   = findViewById(R.id.translate_menu_button)
        downloadsMenuButton   = findViewById(R.id.downloads_menu_button)
        settingsMenuButton    = findViewById(R.id.settings_menu_button)
        downloadsList         = findViewById(R.id.downloads_list)
        downloadsStatus       = findViewById(R.id.downloads_status)

        settingsContainer     = findViewById(R.id.settings_container)
        seekbarSrcLen         = findViewById(R.id.seekbar_src_len)
        seekbarMaxLen         = findViewById(R.id.seekbar_max_len)
        settingSrcLenValue    = findViewById(R.id.setting_src_len_value)
        settingMaxLenValue    = findViewById(R.id.setting_max_len_value)
        settingServerUrl      = findViewById(R.id.setting_server_url)
        settingServerButton   = findViewById(R.id.setting_server_url_button)
        settingResetButton    = findViewById(R.id.setting_reset_button)
        //settingStreamingSwitch = findViewById(R.id.setting_streaming_switch)

        downloadsList.layoutManager = LinearLayoutManager(this)

        applySystemBarInsets()


        runButton.isEnabled    = false
        modelSpinner.isEnabled = false

        loadSettings()
        setupSettingsScreen()
        setupInputCounter()


        translateMenuButton.setOnClickListener { showTranslateScreen() }
        settingsMenuButton.setOnClickListener  { showSettingsScreen()  }
        downloadsMenuButton.setOnClickListener { showDownloadsScreen() }

        runButton.setOnClickListener {
            if (!isReady) return@setOnClickListener
            val text = inputEdit.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            Log.d(TAG, "Button clicked!")

            runButton.isEnabled    = false
            modelSpinner.isEnabled = false
            outputText.text        = "Обрабатывается…"

            lifecycleScope.launch(Dispatchers.IO) {
                val onPartial: ((String) -> Unit)? = if (streamingEnabled) { partial ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        outputText.text = partial
                    }
                } else null

                val result = runInference(text, onPartial)

                withContext(Dispatchers.Main) {
                    outputText.text    = result
                    runButton.isEnabled    = true
                    modelSpinner.isEnabled = true
                }
            }
        }

        showTranslateScreen()
    }

    private fun applySystemBarInsets() {
        val rootView = findViewById<ConstraintLayout>(R.id.main)

        val labelPadTop    = header.paddingTop
        val labelPadBottom = header.paddingBottom
        val labelPadStart  = header.paddingStart
        val labelPadEnd    = header.paddingEnd

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar    = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            header.setPadding(
                labelPadStart,
                labelPadTop + statusBar.top,
                labelPadEnd,
                labelPadBottom
            )

            for (btn in listOf(translateMenuButton, downloadsMenuButton, settingsMenuButton)) {
                (btn.layoutParams as ConstraintLayout.LayoutParams).apply {
                    bottomMargin = navBar.bottom
                    btn.layoutParams = this
                }
            }

            insets
        }
    }



    private fun setupInputCounter() {
        updateCharCounter(inputEdit.text.length)
        applyInputLengthFilter()

        inputEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                updateCharCounter(s.length)
            }
        })
    }

    private fun updateCharCounter(currentLen: Int) {
        inputCharCounter.text = "$currentLen/$maxSrcLen"
        val ratio = if (maxSrcLen > 0) currentLen.toFloat() / maxSrcLen else 0f
        inputCharCounter.setTextColor(
            when {
                ratio >= 1.0f -> Color.parseColor("#FF4444")
                ratio >= 0.8f -> Color.parseColor("#FFA500")
                else          -> Color.parseColor("#777777")
            }
        )
    }

    private fun applyInputLengthFilter() {
        inputEdit.filters = arrayOf(InputFilter.LengthFilter(maxSrcLen))
    }
    private fun normalizeServerUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return url

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }

        val protocolEnd = url.indexOf("://") + 3
        val afterProtocol = url.substring(protocolEnd)

        val slashIdx = afterProtocol.indexOf('/')
        val authority = if (slashIdx == -1) afterProtocol else afterProtocol.substring(0, slashIdx)
        val path      = if (slashIdx == -1) ""             else afterProtocol.substring(slashIdx)

        val normalizedAuthority = if (':' !in authority) "$authority:9100" else authority

        return url.substring(0, protocolEnd) + normalizedAuthority + path
    }

    private fun setNavActive(button: MaterialButton) {
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(COLOR_NAV_ACTIVE)
        button.setTextColor(COLOR_NEON_GREEN)
    }

    private fun setNavInactive(button: MaterialButton) {
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(COLOR_NAV_INACTIVE)
        button.setTextColor(COLOR_TEXT_INACTIVE)
    }


    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        maxSrcLen        = prefs.getInt(KEY_SRC_LEN,    DEFAULT_SRC_LEN)
        maxLen           = prefs.getInt(KEY_MAX_LEN,    DEFAULT_MAX_LEN)
        streamingEnabled = prefs.getBoolean(KEY_STREAMING, DEFAULT_STREAMING)
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SRC_LEN, maxSrcLen)
            .putInt(KEY_MAX_LEN, maxLen)
            .putBoolean(KEY_STREAMING, streamingEnabled)
            .apply()
    }

    private fun srcLenFromProgress(p: Int) = (p + 1) * 64
    private fun srcLenToProgress(v: Int)   = (v / 64 - 1).coerceIn(0, 15)
    private fun maxLenFromProgress(p: Int) = (p + 1) * 64
    private fun maxLenToProgress(v: Int)   = (v / 64 - 1).coerceIn(0, 15)

    private fun setupSettingsScreen() {
        seekbarSrcLen.progress  = srcLenToProgress(maxSrcLen)
        seekbarMaxLen.progress  = maxLenToProgress(maxLen)
        settingSrcLenValue.text = maxSrcLen.toString()
        settingMaxLenValue.text = maxLen.toString()

        seekbarSrcLen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                settingSrcLenValue.text = srcLenFromProgress(progress).toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                maxSrcLen = srcLenFromProgress(sb.progress)
                saveSettings()
                applyInputLengthFilter()
                updateCharCounter(inputEdit.text.length)
                Log.d(TAG, "maxSrcLen changed to $maxSrcLen")
            }
        })

        seekbarMaxLen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                settingMaxLenValue.text = maxLenFromProgress(progress).toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                maxLen = maxLenFromProgress(sb.progress)
                saveSettings()
                Log.d(TAG, "maxLen changed to $maxLen")
            }
        })

        //settingStreamingSwitch.isChecked = streamingEnabled
        //settingStreamingSwitch.setOnCheckedChangeListener { _, isChecked ->
        //    streamingEnabled = isChecked
        //    saveSettings()
        //    Log.d(TAG, "Streaming output: $streamingEnabled")
        //}

        settingServerUrl.hint = ModelDownloadManager.BASE_URL
        settingServerUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val raw = settingServerUrl.text.toString()
                if (raw.isNotEmpty()) {
                    val normalized = normalizeServerUrl(raw)
                    if (normalized != raw) {
                        settingServerUrl.setText(normalized)
                        settingServerUrl.setSelection(normalized.length)
                    }
                }
            }
        }

        settingServerButton.setOnClickListener {
            val raw        = settingServerUrl.text.toString().trimEnd('/')
            val normalized = if (raw.isEmpty()) "" else normalizeServerUrl(raw)
            if (normalized.isEmpty()) return@setOnClickListener

            if (normalized != raw) {
                settingServerUrl.setText(normalized)
                settingServerUrl.setSelection(normalized.length)
            }

            settingServerButton.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = ModelDownloadManager.ping(normalized)
                withContext(Dispatchers.Main) {
                    settingServerButton.isEnabled = true
                    if (ok) {
                        ModelDownloadManager.BASE_URL = normalized
                        settingServerUrl.text.clear()
                        settingServerUrl.hint = normalized
                        Toast.makeText(this@MainActivity, "Адрес сервера обновлён", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity,
                            "Сервер недоступен по адресу: $normalized", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        settingResetButton.setOnClickListener {
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.CustomDialog)
                .setTitle("Сброс настроек")
                .setMessage("Вернуть все настройки к значениям по умолчанию?")
                .setPositiveButton("Сбросить") { _, _ ->
                    maxSrcLen        = DEFAULT_SRC_LEN
                    maxLen           = DEFAULT_MAX_LEN
                    streamingEnabled = DEFAULT_STREAMING
                    ModelDownloadManager.BASE_URL = ModelDownloadManager.DEFAULT_BASE_URL

                    seekbarSrcLen.progress  = srcLenToProgress(maxSrcLen)
                    seekbarMaxLen.progress  = maxLenToProgress(maxLen)
                    settingSrcLenValue.text = maxSrcLen.toString()
                    settingMaxLenValue.text = maxLen.toString()
                    //settingStreamingSwitch.isChecked = DEFAULT_STREAMING
                    settingServerUrl.text.clear()
                    settingServerUrl.hint = ModelDownloadManager.DEFAULT_BASE_URL

                    saveSettings()
                    applyInputLengthFilter()
                    updateCharCounter(inputEdit.text.length)
                    Toast.makeText(this, "Настройки сброшены", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
            val messageView = dialog.findViewById<android.widget.TextView>(android.R.id.message)
            messageView?.setTextColor(android.graphics.Color.WHITE)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(COLOR_NEON_GREEN)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(COLOR_NEON_GREEN)

            val background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.BLACK)
                setStroke(
                    (2 * resources.displayMetrics.density).toInt(),
                    COLOR_NEON_GREEN
                )
                cornerRadius = (12 * resources.displayMetrics.density)
            }
            val inset = android.graphics.drawable.InsetDrawable(
                background,
                (24 * resources.displayMetrics.density).toInt()
            )
            dialog.window?.setBackgroundDrawable(inset)
        }
    }


    private fun showTranslateScreen() {
        setNavActive(translateMenuButton)
        setNavInactive(settingsMenuButton)
        setNavInactive(downloadsMenuButton)

        downloadsList.visibility     = View.GONE
        downloadsStatus.visibility   = View.GONE
        settingsContainer.visibility = View.GONE

        inputEdit.visibility        = View.VISIBLE
        inputCharCounter.visibility = View.VISIBLE
        runButton.visibility        = View.VISIBLE
        outputText.visibility       = View.VISIBLE
        modelSpinner.visibility     = View.VISIBLE

        updateCharCounter(inputEdit.text.length)
        refreshSpinner()
    }

    private fun showSettingsScreen() {
        setNavInactive(translateMenuButton)
        setNavActive(settingsMenuButton)
        setNavInactive(downloadsMenuButton)

        modelSpinner.visibility      = View.GONE
        inputEdit.visibility         = View.GONE
        inputCharCounter.visibility  = View.GONE
        runButton.visibility         = View.GONE
        outputText.visibility        = View.GONE
        downloadsList.visibility     = View.GONE
        downloadsStatus.visibility   = View.GONE

        settingsContainer.visibility = View.VISIBLE
    }

    private fun showDownloadsScreen() {
        setNavInactive(translateMenuButton)
        setNavInactive(settingsMenuButton)
        setNavActive(downloadsMenuButton)

        modelSpinner.visibility      = View.GONE
        inputEdit.visibility         = View.GONE
        inputCharCounter.visibility  = View.GONE
        runButton.visibility         = View.GONE
        outputText.visibility        = View.GONE
        settingsContainer.visibility = View.GONE

        downloadsList.visibility   = View.VISIBLE
        downloadsStatus.visibility = View.VISIBLE

        loadDownloadsList()
    }

    private fun refreshSpinner() {
        val found = mutableListOf<Pair<String, String>>()

        filesDir.listFiles()
            ?.filter { it.isDirectory && isModelDir(it) }
            ?.sortedBy { it.name }
            ?.forEach { found.add(it.name to makeDisplayName(it.name)) }

        if (isModelDir(filesDir)) {
            found.add(0, "" to "язык по умолчанию (RU -> EN)")
        }

        installedModels.clear()
        installedModels.addAll(found)

        if (installedModels.isEmpty()) {
            modelSpinner.visibility = View.GONE
            outputText.text  = "Язык не установлен, зайдите в 'загрузки' и установите нужные языковые пакеты."
            runButton.isEnabled    = false
            modelSpinner.isEnabled = false
            return
        }

        modelSpinner.visibility = View.VISIBLE

        val displayNames = installedModels.map { it.second }
        val adapter = ArrayAdapter(this, R.drawable.spinner_list, displayNames)
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
            runButton.isEnabled    = true
            modelSpinner.isEnabled = true
        }
    }

    private fun isModelDir(dir: File): Boolean =
        File(dir, "encoder.onnx").exists() &&
        File(dir, "decoder.onnx").exists() &&
        File(dir, "tokenizer/tokenizer.json").exists()

    private fun loadModel(stem: String) {
        isReady = false
        runButton.isEnabled    = false
        modelSpinner.isEnabled = false
        outputText.text = "Загрузка языкового пакета…"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                model?.close()
                val modelDir = if (stem.isEmpty()) filesDir else File(filesDir, stem)
                tokenizer = UnigramTokenizer(applicationContext, modelDir)
                model     = OnnxTransformer(applicationContext, modelDir)
                isReady   = true
                withContext(Dispatchers.Main) {
                    runButton.isEnabled    = true
                    modelSpinner.isEnabled = true
                    outputText.text = "Готово. Введите текст и нажмите кнопку."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    outputText.text = "Ошибка загрузки языкового пакета: ${e.message}"
                    Log.e(TAG, "loadModel($stem) failed", e)
                }
            }
        }
    }

    private fun makeDisplayName(stem: String): String {
        val parts = stem.split("-")
        return if (parts.size >= 2 && parts.take(2).all { it.length <= 3 && it.all(Char::isLetter) }) {
            val arrow  = "${parts[0].uppercase()} -> ${parts[1].uppercase()}"
            val suffix = parts.drop(2).joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
            if (suffix.isNotEmpty()) "$arrow $suffix" else arrow
        } else {
            parts.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        }
    }

    private fun getTotalRamGb(): Double {
        val am      = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    private fun loadDownloadsList() {
        val ramGb = getTotalRamGb()
        Log.d(TAG, "Device RAM: ${"%.2f".format(ramGb)} GB")

        val installedStems = buildInstalledStems().toMutableSet()
        val localOnlyItems = buildLocalOnlyModels(installedStems)

        downloadsStatus.text = if (localOnlyItems.isEmpty())
            "Загрузка списка языковых пакетов…"
        else
            "Загрузка списка языковых пакетов…\nУстановленные языковые пакеты:"

        downloadsAdapter = DownloadsAdapter(
            items          = localOnlyItems.toMutableList(),
            installedStems = installedStems,
            onDownload     = ::startDownload,
            onDelete       = ::deleteModel,
            ramGb          = ramGb,
        )
        downloadsList.adapter = downloadsAdapter

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val serverModels = ModelDownloadManager.fetchModelList()
                Log.d("Downloads", "Server models: $serverModels")

                val serverFiles      = serverModels.map { it.file.removeSuffix(".zip") }.toSet()
                val localNotOnServer = localOnlyItems.filter { it.file.removeSuffix(".zip") !in serverFiles }
                val mergedList       = serverModels + localNotOnServer

                withContext(Dispatchers.Main) {
                    downloadsStatus.text = buildStatusText(
                        serverCount = serverModels.size,
                        localExtra  = localNotOnServer.size,
                        error       = null
                    )
                    downloadsAdapter = DownloadsAdapter(
                        items          = mergedList,
                        installedStems = installedStems,
                        onDownload     = ::startDownload,
                        onDelete       = ::deleteModel,
                        ramGb          = ramGb,
                    )
                    downloadsList.adapter = downloadsAdapter
                }
            } catch (e: Exception) {
                Log.e("Downloads", "fetchModelList failed", e)
                withContext(Dispatchers.Main) {
                    downloadsStatus.text = buildStatusText(
                        serverCount = 0,
                        localExtra  = localOnlyItems.size,
                        error       = e.message
                    )
                }
            }
        }
    }

    private fun buildLocalOnlyModels(installedStems: Set<String>): List<ModelInfo> {
        val result = mutableListOf<ModelInfo>()
        for (stem in installedStems) {
            val dir    = if (stem.isEmpty()) filesDir else File(filesDir, stem)
            val sizeMb = (dirSizeBytes(dir) / (1024L * 1024L)).toInt()
            val name   = if (stem.isEmpty()) "Языковой пакет по умолчанию (RU -> EN)"
            else makeDisplayName(stem)
            result.add(ModelInfo(name = name, file = "$stem.zip", size_mb = sizeMb))
        }
        return result
    }

    private fun dirSizeBytes(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun buildStatusText(serverCount: Int, localExtra: Int, error: String?): String {
        val sb = StringBuilder()
        if (error != null) sb.append("Ошибка подключения, попробуйте проверить подключение к интернету или немного подождать.\n")
        when {
            serverCount > 0 && localExtra > 0 -> sb.append("Доступные и установленные языковые пакеты:")
            serverCount > 0                   -> sb.append("Доступные языковые пакеты:")
            localExtra > 0                    -> sb.append("Установленные языковые пакеты:")
            error != null                     -> sb.append("Нет установленных языковых пакетов и нет подключения к серверу.")
            else                              -> sb.append("Доступных языковых пакетов нет.")
        }
        return sb.toString()
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
        val stem    = modelInfo.file.removeSuffix(".zip")
        val destDir = File(filesDir, stem).also { it.mkdirs() }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ModelDownloadManager.downloadAndExtract(
                    model   = modelInfo,
                    destDir = destDir
                ) { progress, isInstalling ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (isInstalling) {
                            downloadsAdapter.setInstalling(modelInfo.file)
                        } else if (progress != null) {
                            downloadsAdapter.updateProgress(modelInfo.file, progress)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    downloadsAdapter.markDone(modelInfo.file)
                    Toast.makeText(this@MainActivity,
                        "Языковой пакет «${modelInfo.name}» установлен!", Toast.LENGTH_LONG).show()
                }
                downloadsAdapter.markInstalled(modelInfo.file)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadsAdapter.markDone(modelInfo.file)
                    Toast.makeText(this@MainActivity,
                        "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

        }
    }

    private fun deleteModel(modelInfo: ModelInfo) {
        val stem = modelInfo.file.removeSuffix(".zip")
        val dir  = if (stem.isEmpty()) filesDir else File(filesDir, stem)

        lifecycleScope.launch(Dispatchers.IO) {
            if (stem.isEmpty()) {
                listOf("encoder.onnx", "decoder.onnx", "tokenizer").forEach { name ->
                    File(dir, name).deleteRecursively()
                }
            } else {
                dir.deleteRecursively()
            }
            withContext(Dispatchers.Main) {
                downloadsAdapter.removeInstalled(modelInfo.file)
                Toast.makeText(this@MainActivity, "Языковой пакет удалён", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun runInference(
        text: String,
        onPartial: ((String) -> Unit)? = null
    ): String {
        val tok = tokenizer ?: return "Токенайзер не загружен"
        val mdl = model     ?: return "Языковой пакет не загружен"

        val startTime = System.currentTimeMillis()

        val srcTokens = tok.encode(text, maxSrcLen)
        val memory    = mdl.encode(srcTokens, maxSrcLen)
        val modelDim  = memory.size / maxSrcLen

        var firstTokenTimeMs: Long? = null
        var tokenCount = 0

        val outTokens = GreedySearch.search(
            model    = mdl,
            memory   = memory,
            srcLen   = maxSrcLen,
            modelDim = modelDim,
            maxLen   = maxLen,
            bosId    = tok.bosId.toLong(),
            eosId    = tok.eosId.toLong()
        ) { tokens, len ->
            tokenCount++
            val now = System.currentTimeMillis()
            if (firstTokenTimeMs == null) firstTokenTimeMs = now

            onPartial?.let { cb ->
                val partial = tok.decode(tokens, len)
                cb(partial)
            }
        }

        val endTime        = System.currentTimeMillis()
        val totalTime      = endTime - startTime
        val timeToFirst    = (firstTokenTimeMs ?: endTime) - startTime
        val generationMs   = endTime - (firstTokenTimeMs ?: endTime)
        val tokensPerSec   = if (generationMs > 0) tokenCount * 1000.0 / generationMs else 0.0

        Log.i(TAG_PERF, "═══════════════════════════════════")
        Log.i(TAG_PERF, "Время до первого токена : ${timeToFirst} мс")
        Log.i(TAG_PERF, "Токенов в секунду       : ${"%.2f".format(tokensPerSec)} т/с  ($tokenCount токенов(а) за ${generationMs} мс)")
        Log.i(TAG_PERF, "Общее время запроса     : ${totalTime} мс")
        Log.i(TAG_PERF, "═══════════════════════════════════")

        return tok.decode(outTokens, tokenCount)
    }

    override fun onDestroy() {
        super.onDestroy()
        model?.close()
    }

    companion object {
        private const val TAG       = "MainActivity"
        private const val TAG_PERF  = "InferencePerf"

        private const val PREFS_NAME      = "polyglot_settings"
        private const val KEY_SRC_LEN     = "max_src_len"
        private const val KEY_MAX_LEN     = "max_len"
        private const val KEY_STREAMING   = "streaming_enabled"

        private const val DEFAULT_SRC_LEN  = 1024
        private const val DEFAULT_MAX_LEN  = 1024
        private const val DEFAULT_STREAMING = true
    }
}
