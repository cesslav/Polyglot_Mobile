package com.example.polyglotapp
// This file is distributed under the open license AGPLv3, source code: https://github.com/cesslav/Polyglot_Mobile.
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
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
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingPartial: String? = null
    private val partialRunnable = Runnable {
        pendingPartial?.let { outputText.text = it }
    }

    private lateinit var modelSpinner: android.widget.Spinner
    private lateinit var inputEdit: EditText
    private lateinit var inputCharCounter: TextView
    private lateinit var runButton: MaterialButton
    private lateinit var outputText: TextView
    private lateinit var header: LinearLayout

    private lateinit var translateMenuButton: MaterialButton
    private lateinit var downloadsMenuButton: MaterialButton
    private lateinit var settingsMenuButton: MaterialButton
    private lateinit var downloadsContainer: NestedScrollView
    private lateinit var downloadsList: RecyclerView
    private lateinit var downloadsConnectionStatus: TextView
    private lateinit var downloadsStatus: TextView
    private lateinit var settingServerUrl: EditText
    private lateinit var settingServerButton: MaterialButton
    private lateinit var settingResetButton: MaterialButton
    private lateinit var settingsContainer: ScrollView
    private lateinit var aboutWebsiteButton: MaterialButton
    private lateinit var aboutAppRepoButton: MaterialButton
    private lateinit var aboutToolsRepoButton: MaterialButton
    private val installedModels = mutableListOf<Pair<String, String>>()
    private var selectedModelStem: String? = null
    private lateinit var downloadsAdapter: DownloadsAdapter
    private var serverModelList: List<ModelInfo> = emptyList()
    private var downloadService: DownloadService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            downloadService = (binder as DownloadService.LocalBinder).getService()
            updateServiceCallbacks()
            if (::downloadsAdapter.isInitialized) {
                downloadService?.getActiveStates()?.forEach { (file, state) ->
                    if (state.installing) downloadsAdapter.setInstalling(file)
                    else downloadsAdapter.updateProgress(file, state.progress)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            downloadService = null
        }
    }

    private var colorMain: Int = 0
    private var colorSecondary: Int = 0
    private var colorOrange: Int = 0
    private var colorRed: Int = 0
    private var colorWhite: Int = 0
    private var colorDark: Int = 0
    private var colorBlack: Int = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("LICENSE",
            "This file is distributed under the open license AGPLv3, " +
                    "source code: https://github.com/cesslav/Polyglot_Mobile.")
        setContentView(R.layout.layout)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        colorMain = ContextCompat.getColor(this, R.color.main)
        colorSecondary = ContextCompat.getColor(this, R.color.secondary)
        colorOrange = ContextCompat.getColor(this, R.color.orange)
        colorRed = ContextCompat.getColor(this, R.color.red)
        colorWhite = ContextCompat.getColor(this, R.color.white)
        colorDark = ContextCompat.getColor(this, R.color.dark)
        colorBlack = ContextCompat.getColor(this, R.color.black)

        modelSpinner = findViewById(R.id.model_spinner)
        inputEdit = findViewById(R.id.input_text)
        inputCharCounter = findViewById(R.id.input_char_counter)
        runButton = findViewById(R.id.button)
        outputText = findViewById(R.id.output_text)
        header = findViewById(R.id.header)

        translateMenuButton = findViewById(R.id.translate_menu_button)
        downloadsMenuButton = findViewById(R.id.downloads_menu_button)
        settingsMenuButton = findViewById(R.id.settings_menu_button)

        downloadsContainer = findViewById(R.id.downloads_container)
        downloadsList = findViewById(R.id.downloads_list)
        downloadsConnectionStatus = findViewById(R.id.downloads_connection_status)
        downloadsStatus = findViewById(R.id.downloads_status)
        settingServerUrl = findViewById(R.id.setting_server_url)
        settingServerButton = findViewById(R.id.setting_server_url_button)
        settingResetButton = findViewById(R.id.setting_reset_button)

        settingsContainer = findViewById(R.id.settings_container)
        aboutWebsiteButton = findViewById(R.id.about_website_button)
        aboutAppRepoButton = findViewById(R.id.about_app_repo_button)
        aboutToolsRepoButton = findViewById(R.id.about_tools_repo_button)

        downloadsList.layoutManager = LinearLayoutManager(this)

        applySystemBarInsets()

        runButton.isEnabled = false
        modelSpinner.isEnabled = false

        setupAboutScreen()
        setupDownloadsServerSection()
        setupInputCounter()

        translateMenuButton.setOnClickListener { showTranslateScreen() }
        settingsMenuButton.setOnClickListener { showAboutScreen() }
        downloadsMenuButton.setOnClickListener { showDownloadsScreen() }

        runButton.setOnClickListener {
            if (!isReady) return@setOnClickListener
            val text = inputEdit.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            runButton.isEnabled = false
            modelSpinner.isEnabled = false
            outputText.text = "Обрабатывается…"

            val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_INFERENCE_TAG)
            wakeLock.acquire(WAKELOCK_INFERENCE_TIMEOUT_MS)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val onPartial: (String) -> Unit = { partial ->
                        pendingPartial = partial
                        mainHandler.removeCallbacks(partialRunnable)
                        mainHandler.post(partialRunnable)
                    }
                    val result = runInference(text, onPartial)
                    mainHandler.removeCallbacks(partialRunnable)
                    withContext(Dispatchers.Main) {
                        outputText.text = result
                        runButton.isEnabled = true
                        modelSpinner.isEnabled = true
                    }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }

        showTranslateScreen()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, DownloadService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        isServiceBound = true
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            downloadService?.onProgress = null
            downloadService?.onComplete = null
            unbindService(serviceConnection)
            isServiceBound = false
            downloadService = null
        }
    }

    override fun onDestroy() {
        model?.close()
        super.onDestroy()
    }

    private fun updateServiceCallbacks() {
        val svc = downloadService ?: return

        svc.onProgress = { file, progress, isInstalling ->
            runOnUiThread {
                if (::downloadsAdapter.isInitialized) {
                    if (isInstalling) downloadsAdapter.setInstalling(file)
                    else if (progress != null) downloadsAdapter.updateProgress(file, progress)
                }
            }
        }

        svc.onComplete = { file, success, error ->
            runOnUiThread {
                if (::downloadsAdapter.isInitialized) {
                    downloadsAdapter.markDone(file)
                    if (success) {
                        downloadsAdapter.markInstalled(file)
                        val name = serverModelList.find { it.file == file }?.name
                            ?: file.removeSuffix(".zip")
                        Toast.makeText(
                            this,
                            "Языковой пакет «$name» установлен!",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            "Ошибка загрузки: $error",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }


    private fun applySystemBarInsets() {
        val rootView = findViewById<ConstraintLayout>(R.id.main)
        val labelPadTop = header.paddingTop
        val labelPadBot = header.paddingBottom
        val labelPadStart = header.paddingStart
        val labelPadEnd = header.paddingEnd

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            header.setPadding(labelPadStart, labelPadTop + statusBar.top, labelPadEnd, labelPadBot)

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
        inputEdit.filters = arrayOf(InputFilter.LengthFilter(MAX_CHAR_INPUT))
        updateCharCounter(inputEdit.text.length)
        inputEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int)     = Unit
            override fun afterTextChanged(s: Editable) { updateCharCounter(s.length) }
        })
    }

    private fun updateCharCounter(currentLen: Int) {
        inputCharCounter.text = "$currentLen/$MAX_CHAR_INPUT"
        val ratio = currentLen.toFloat() / MAX_CHAR_INPUT
        inputCharCounter.setTextColor(
            when {
                ratio >= 1.0f -> colorRed
                ratio >= 0.8f -> colorOrange
                else -> colorDark
            }
        )
    }

    private fun setupAboutScreen() {
        aboutWebsiteButton.setOnClickListener { openUrl("http://igorpet.ru:9090") }
        aboutAppRepoButton.setOnClickListener { openUrl("https://github.com/cesslav/Polyglot_Mobile") }
        aboutToolsRepoButton.setOnClickListener { openUrl("https://github.com/cesslav/polyglot") }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку: $url", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupDownloadsServerSection() {
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
            val raw = settingServerUrl.text.toString().trimEnd('/')
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
            val dialog = AlertDialog.Builder(this, R.style.CustomDialog)
                .setTitle("Сброс адреса сервера")
                .setMessage("Вернуть адрес сервера к значению по умолчанию?")
                .setPositiveButton("Сбросить") { _, _ ->
                    ModelDownloadManager.BASE_URL = ModelDownloadManager.DEFAULT_BASE_URL
                    settingServerUrl.text.clear()
                    settingServerUrl.hint = ModelDownloadManager.DEFAULT_BASE_URL
                    Toast.makeText(this, "Адрес сервера сброшен", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()

            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(colorWhite)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(colorMain)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(colorMain)

            val dp2 = (2 * resources.displayMetrics.density).toInt()
            val dp24 = (24 * resources.displayMetrics.density).toInt()
            val bg = GradientDrawable().apply {
                setColor(colorBlack)
                setStroke(dp2, colorMain)
            }
            dialog.window?.setBackgroundDrawable(InsetDrawable(bg, dp24))
        }
    }

    private fun normalizeServerUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"

        val protocolEnd = url.indexOf("://") + 3
        val afterProtocol = url.substring(protocolEnd)
        val slashIdx = afterProtocol.indexOf('/')
        val authority = if (slashIdx == -1) afterProtocol else afterProtocol.substring(0, slashIdx)
        val path = if (slashIdx == -1) "" else afterProtocol.substring(slashIdx)
        val normAuthority = if (':' !in authority) "$authority:9100" else authority
        return url.substring(0, protocolEnd) + normAuthority + path
    }

    private fun setNavActive(button: MaterialButton) {
        button.backgroundTintList = ColorStateList.valueOf(colorSecondary)
        button.setTextColor(colorMain)
    }

    private fun setNavInactive(button: MaterialButton) {
        button.backgroundTintList = ColorStateList.valueOf(colorBlack)
        button.setTextColor(colorWhite)
    }

    private fun showTranslateScreen() {
        setNavActive(translateMenuButton)
        setNavInactive(settingsMenuButton)
        setNavInactive(downloadsMenuButton)

        downloadsContainer.visibility = View.GONE
        settingsContainer.visibility = View.GONE

        inputEdit.visibility = View.VISIBLE
        inputCharCounter.visibility = View.VISIBLE
        runButton.visibility = View.VISIBLE
        outputText.visibility = View.VISIBLE
        modelSpinner.visibility = View.VISIBLE

        updateCharCounter(inputEdit.text.length)
        refreshSpinner()
    }

    private fun showAboutScreen() {
        setNavInactive(translateMenuButton)
        setNavActive(settingsMenuButton)
        setNavInactive(downloadsMenuButton)

        modelSpinner.visibility = View.GONE
        inputEdit.visibility = View.GONE
        inputCharCounter.visibility = View.GONE
        runButton.visibility = View.GONE
        outputText.visibility = View.GONE
        downloadsContainer.visibility = View.GONE

        settingsContainer.visibility = View.VISIBLE
    }

    private fun showDownloadsScreen() {
        setNavInactive(translateMenuButton)
        setNavInactive(settingsMenuButton)
        setNavActive(downloadsMenuButton)

        modelSpinner.visibility = View.GONE
        inputEdit.visibility = View.GONE
        inputCharCounter.visibility = View.GONE
        runButton.visibility = View.GONE
        outputText.visibility = View.GONE
        settingsContainer.visibility = View.GONE

        downloadsContainer.visibility = View.VISIBLE
        loadDownloadsList()
    }

    private fun refreshSpinner() {
        val found = mutableListOf<Pair<String, String>>()

        filesDir.listFiles()
            ?.filter { it.isDirectory && isModelDir(it) }
            ?.sortedBy { it.name }
            ?.forEach { found.add(it.name to makeDisplayName(it.name)) }

        if (isModelDir(filesDir)) found.add(0, "" to "язык по умолчанию (RU -> EN)")

        installedModels.clear()
        installedModels.addAll(found)

        if (installedModels.isEmpty()) {
            modelSpinner.visibility = View.GONE
            outputText.text = "Язык не установлен, зайдите в 'загрузки' и установите нужные языковые пакеты."
            runButton.isEnabled = false
            modelSpinner.isEnabled = false
            return
        }

        modelSpinner.visibility = View.VISIBLE

        val adapter = ArrayAdapter(this, R.drawable.spinner_list, installedModels.map { it.second })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.onItemSelectedListener = null
        modelSpinner.adapter = adapter

        val restoredPos = installedModels.indexOfFirst { it.first == selectedModelStem }
            .takeIf { it >= 0 } ?: 0
        modelSpinner.setSelection(restoredPos, false)

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val stem = installedModels[pos].first
                if (stem != selectedModelStem) { selectedModelStem = stem; loadModel(stem) }
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
            modelSpinner.isEnabled = true
        }
    }

    private fun isModelDir(dir: File): Boolean =
        File(dir, "encoder.onnx").exists() &&
                File(dir, "decoder.onnx").exists() &&
                File(dir, "tokenizer/tokenizer.json").exists()

    private fun loadModel(stem: String) {
        isReady = false
        runButton.isEnabled = false
        modelSpinner.isEnabled = false
        outputText.text = "Загрузка языкового пакета…"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                model?.close()
                val modelDir = if (stem.isEmpty()) filesDir else File(filesDir, stem)
                tokenizer = UnigramTokenizer(applicationContext, modelDir)
                model = OnnxTransformer(applicationContext, modelDir)
                isReady = true
                withContext(Dispatchers.Main) {
                    runButton.isEnabled = true
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
            val arrow = "${parts[0].uppercase()} -> ${parts[1].uppercase()}"
            val suffix = parts.drop(2).joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
            if (suffix.isNotEmpty()) "$arrow $suffix" else arrow
        } else {
            parts.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        }
    }


    private fun loadDownloadsList() {
        val installedStems = buildInstalledStems().toMutableSet()
        val localOnlyItems = buildLocalOnlyModels(installedStems)

        setDownloadsConnectionStatus(loading = true, error = null)
        setDownloadsPacksStatus(serverCount = 0, localExtra = localOnlyItems.size, loading = true)

        downloadsAdapter = DownloadsAdapter(
            items = localOnlyItems.toMutableList(),
            installedStems = installedStems,
            onDownload = ::startDownload,
            onDelete = ::deleteModel,
        )
        downloadsList.adapter = downloadsAdapter

        updateServiceCallbacks()
        downloadService?.getActiveStates()?.forEach { (file, state) ->
            if (state.installing) downloadsAdapter.setInstalling(file)
            else downloadsAdapter.updateProgress(file, state.progress)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val serverModels = ModelDownloadManager.fetchModelList()
                val serverFiles = serverModels.map { it.file.removeSuffix(".zip") }.toSet()
                val localNotOnServer = localOnlyItems.filter { it.file.removeSuffix(".zip") !in serverFiles }
                val mergedList = serverModels + localNotOnServer

                withContext(Dispatchers.Main) {
                    serverModelList = serverModels

                    setDownloadsConnectionStatus(loading = false, error = null)
                    setDownloadsPacksStatus(
                        serverCount = serverModels.size,
                        localExtra = localNotOnServer.size,
                        loading = false
                    )
                    downloadsAdapter = DownloadsAdapter(
                        items = mergedList,
                        installedStems = installedStems,
                        onDownload = ::startDownload,
                        onDelete = ::deleteModel,
                    )
                    downloadsList.adapter = downloadsAdapter

                    updateServiceCallbacks()
                    downloadService?.getActiveStates()?.forEach { (file, state) ->
                        if (state.installing) downloadsAdapter.setInstalling(file)
                        else downloadsAdapter.updateProgress(file, state.progress)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchModelList failed", e)
                withContext(Dispatchers.Main) {
                    setDownloadsConnectionStatus(loading = false, error = e.message)
                    setDownloadsPacksStatus(
                        serverCount = 0,
                        localExtra= localOnlyItems.size,
                        loading = false
                    )
                }
            }
        }
    }

    private fun setDownloadsConnectionStatus(loading: Boolean, error: String?) {
        when {
            loading -> {
                downloadsConnectionStatus.text = "Подключение к серверу…"
                downloadsConnectionStatus.setTextColor(colorMain)
                downloadsConnectionStatus.setBackgroundResource(R.drawable.bg_neon_card)
                downloadsConnectionStatus.visibility = View.VISIBLE
            }
            error != null -> {
                downloadsConnectionStatus.text = "Ошибка подключения к серверу. Проверьте интернет или адрес сервера."
                downloadsConnectionStatus.setTextColor(colorOrange)
                downloadsConnectionStatus.setBackgroundResource(R.drawable.bg_warning_card)
                downloadsConnectionStatus.visibility = View.VISIBLE
            }
            else -> downloadsConnectionStatus.visibility = View.GONE
        }
    }

    private fun setDownloadsPacksStatus(serverCount: Int, localExtra: Int, loading: Boolean) {
        if (loading) { downloadsStatus.visibility = View.GONE; return }
        val text = when {
            serverCount > 0 && localExtra > 0 -> "Доступные и установленные языковые пакеты:"
            serverCount > 0                   -> "Доступные языковые пакеты:"
            localExtra > 0                    -> "Установленные языковые пакеты:"
            else                              -> "Языковых пакетов нет."
        }
        downloadsStatus.text = text
        downloadsStatus.setTextColor(colorWhite)
        downloadsStatus.setBackgroundResource(R.drawable.bg_neon_card)
        downloadsStatus.visibility = View.VISIBLE
    }

    private fun buildLocalOnlyModels(installedStems: Set<String>): List<ModelInfo> {
        return installedStems.map { stem ->
            val dir    = if (stem.isEmpty()) filesDir else File(filesDir, stem)
            val sizeMb = (dirSizeBytes(dir) / (1024L * 1024L)).toInt()
            val name   = if (stem.isEmpty()) "Языковой пакет по умолчанию (RU -> EN)" else makeDisplayName(stem)
            ModelInfo(name = name, file = "$stem.zip", size_mb = sizeMb)
        }
    }

    private fun dirSizeBytes(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

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

        val svc = downloadService
        if (svc != null) {
            svc.enqueueDownload(modelInfo, destDir)
        } else {
            val intent = Intent(this, DownloadService::class.java)
            startService(intent)
            if (!isServiceBound) {
                bindService(intent, serviceConnection, BIND_AUTO_CREATE)
                isServiceBound = true
            }
            mainHandler.postDelayed({
                val bound = downloadService
                if (bound != null) bound.enqueueDownload(modelInfo, destDir)
                else Toast.makeText(this, "Сервис недоступен, попробуйте ещё раз", Toast.LENGTH_SHORT).show()
            }, 500)
        }
    }

    private fun deleteModel(modelInfo: ModelInfo) {
        val stem = modelInfo.file.removeSuffix(".zip")
        val dir = if (stem.isEmpty()) filesDir else File(filesDir, stem)

        lifecycleScope.launch(Dispatchers.IO) {
            if (stem.isEmpty()) {
                listOf("encoder.onnx", "decoder.onnx", "tokenizer")
                    .forEach { File(dir, it).deleteRecursively() }
            } else {
                dir.deleteRecursively()
            }
            File(filesDir, "${modelInfo.file}.part").delete()

            withContext(Dispatchers.Main) {
                downloadsAdapter.removeInstalled(modelInfo.file)
                Toast.makeText(this@MainActivity, "Языковой пакет удалён", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun resolveTokenLen(rawTokens: LongArray, padId: Long): Int {
        val actual = rawTokens.indexOfLast { it != padId }.let { if (it == -1) 1 else it + 1 }
        return TOKEN_LEN_STEPS.firstOrNull { it >= actual } ?: TOKEN_LEN_STEPS.last()
    }

    private fun runInference(text: String, onPartial: ((String) -> Unit)? = null): String {
        val tok = tokenizer ?: return "Токенайзер не загружен"
        val mdl = model ?: return "Языковой пакет не загружен"

        val startTime = System.currentTimeMillis()

        val rawTokens = tok.encode(text, TOKEN_LEN_STEPS.last())
        val srcLen = resolveTokenLen(rawTokens, tok.padId.toLong())
        val srcTokens = rawTokens.copyOf(srcLen).also { arr ->
            for (i in arr.size until srcLen) arr[i] = tok.padId.toLong()
        }

        val memory = mdl.encode(srcTokens, srcLen)
        val modelDim = memory.size / srcLen

        var firstTokenTimeMs: Long? = null
        var tokenCount = 0

        val outTokens = GreedySearch.search(
            model = mdl,
            memory = memory,
            srcLen = srcLen,
            modelDim = modelDim,
            maxLen = MAX_OUTPUT_LEN,
            bosId = tok.bosId.toLong(),
            eosId = tok.eosId.toLong()
        ) { tokens, len ->
            tokenCount++
            val now = System.currentTimeMillis()
            if (firstTokenTimeMs == null) firstTokenTimeMs = now
            onPartial?.invoke(tok.decode(tokens, len))
        }

        val endTime = System.currentTimeMillis()
        val generationMs = endTime - (firstTokenTimeMs ?: endTime)
        val tokensPerSec = if (generationMs > 0) tokenCount * 1000.0 / generationMs else 0.0

        Log.i(TAG_PERF, "Токенов на входе: $srcLen")
        Log.i(TAG_PERF, "Время до первого токена: ${(firstTokenTimeMs ?: endTime) - startTime} мс")
        Log.i(TAG_PERF, "Скорость: ${"%.2f".format(tokensPerSec)} т/с ($tokenCount за ${generationMs} мс)")
        Log.i(TAG_PERF, "Общее время: ${endTime - startTime} мс")

        return tok.decode(outTokens, outTokens.size)
    }


    companion object {
        private const val TAG = "MainActivity"
        private const val TAG_PERF = "InferencePerf"

        private const val WAKELOCK_INFERENCE_TAG = "polyglot:inference"
        private const val WAKELOCK_INFERENCE_TIMEOUT_MS = 5 * 60 * 1000L

        const val MAX_CHAR_INPUT = 2000
        const val MAX_OUTPUT_LEN = 512

        val TOKEN_LEN_STEPS = intArrayOf(
            128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 480, 512
        )
    }
}
