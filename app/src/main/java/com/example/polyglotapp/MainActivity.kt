package com.example.polyglotapp

import android.content.ContentValues.TAG
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tokenizer: UnigramTokenizer
    private lateinit var model: OnnxTransformer
    private var isReady = false

    private lateinit var inputEdit: EditText
    private lateinit var runButton: MaterialButton
    private lateinit var outputText: TextView
    private lateinit var label: TextView
    private lateinit var translateMenuButton: MaterialButton
    private lateinit var downloadsMenuButton: MaterialButton
    private lateinit var settingsMenuButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout)

        inputEdit   = findViewById(R.id.input_text)
        runButton   = findViewById(R.id.button)
        outputText  = findViewById(R.id.output_text)
        label  = findViewById(R.id.main_label)
        translateMenuButton = findViewById(R.id.translate_menu_button)
        downloadsMenuButton = findViewById(R.id.downloads_menu_button)
        settingsMenuButton = findViewById(R.id.settings_menu_button)



        runButton.isEnabled = false
        outputText.text = "Загрузка модели…"

        // Инициализация в фоне — модели могут весить сотни MB
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                tokenizer = UnigramTokenizer(applicationContext)
                model     = OnnxTransformer(applicationContext)
                isReady   = true
                withContext(Dispatchers.Main) {
                    runButton.isEnabled = true
                    outputText.text = "Готово. Введите текст и нажмите кнопку."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    outputText.text = "Ошибка загрузки модели: ${e.message}"
                }
            }
        }

        runButton.setOnClickListener {
            Log.d(TAG, "Button clicked!")
            if (!isReady) return@setOnClickListener
            val text = inputEdit.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            runButton.isEnabled = false
            outputText.text = "Обрабатывается…"

            lifecycleScope.launch(Dispatchers.IO) {
                val result: String = runInference(text)
                outputText.text = result
            }
            runButton.isEnabled = true
        }

        translateMenuButton.setOnClickListener {
            label.text = "Полиглот"
            translateMenuButton.setBackgroundColor(resources.getColor(R.color.dark))
            settingsMenuButton.setBackgroundColor(resources.getColor(R.color.light))
            downloadsMenuButton.setBackgroundColor(resources.getColor(R.color.light))
            inputEdit.visibility = View.VISIBLE
            runButton.visibility = View.VISIBLE
            outputText.visibility = View.VISIBLE
        }

        settingsMenuButton.setOnClickListener {
            label.text = "Настройки"
            translateMenuButton.setBackgroundColor(resources.getColor(R.color.light))
            settingsMenuButton.setBackgroundColor(resources.getColor(R.color.dark))
            downloadsMenuButton.setBackgroundColor(resources.getColor(R.color.light))
            inputEdit.visibility = View.GONE
            runButton.visibility = View.GONE
            outputText.visibility = View.GONE
        }

        downloadsMenuButton.setOnClickListener {
            label.text = "Загрузки"
            translateMenuButton.setBackgroundColor(resources.getColor(R.color.light))
            settingsMenuButton.setBackgroundColor(resources.getColor(R.color.light))
            downloadsMenuButton.setBackgroundColor(resources.getColor(R.color.dark))
            inputEdit.visibility = View.GONE
            runButton.visibility = View.GONE
            outputText.visibility = View.GONE
        }
    }

    private fun runInference(text: String): String {
        val maxSrcLen = 256

        // 1. Токенизация → LongArray(maxSrcLen), дополненный PAD-ами
        val srcTokens = tokenizer.encode(text, maxSrcLen)

        // 2. Энкодер → FloatArray размером maxSrcLen * modelDim
        val memory   = model.encode(srcTokens, maxSrcLen)
        val modelDim = memory.size / maxSrcLen

        // 3. Beam Search с декодером
        val outTokens = BeamSearch.search(
            model    = model,
            memory   = memory,
            srcLen   = maxSrcLen,
            modelDim = modelDim,
            beamSize = 4,
            maxLen   = 128,
            bosId    = tokenizer.bosId.toLong(),
            eosId    = tokenizer.eosId.toLong()
        )

        // 4. Детокенизация
        return tokenizer.decode(outTokens)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReady) model.close()
    }
}