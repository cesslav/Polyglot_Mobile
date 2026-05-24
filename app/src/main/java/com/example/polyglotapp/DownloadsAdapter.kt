package com.example.polyglotapp
// This file is distributed under the open license AGPLv3, source code: https://github.com/cesslav/Polyglot_Mobile.
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class DownloadsAdapter(
    private val items: List<ModelInfo>,
    private val installedStems: MutableSet<String>,
    private val onDownload: (ModelInfo) -> Unit,
    private val onDelete: (ModelInfo) -> Unit,
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    private val progressMap = mutableMapOf<String, Int>()
    private val installingSet = mutableSetOf<String>()
    private data class ProgressPayload(val progress: Int)
    private object InstallingPayload

    inner class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val nameText: TextView = root.findViewById(R.id.item_model_name)
        val sizeText: TextView = root.findViewById(R.id.item_model_size)
        val downloadBtn: MaterialButton = root.findViewById(R.id.item_download_btn)
        val progressBar: ProgressBar = root.findViewById(R.id.item_progress)
        val progressText: TextView = root.findViewById(R.id.item_progress_text)
        val progressContainer: View = root.findViewById(R.id.item_progress_container)
        val deleteBtn: MaterialButton = root.findViewById(R.id.item_delete_btn)

        fun bind(model: ModelInfo) {
            nameText.text = model.name
            sizeText.text = "${model.size_mb} МБ"

            val stem = model.file.removeSuffix(".zip")
            when {
                stem in installedStems -> setInstalled(model)
                model.file in installingSet -> setInstalling()
                progressMap.containsKey(model.file) -> setDownloading(progressMap[model.file]!!)
                else -> setIdle(model)
            }
        }

        fun applyPayload(payload: Any) {
            when (payload) {
                is ProgressPayload -> {
                    if (progressContainer.visibility != View.VISIBLE) {
                        progressContainer.visibility = View.VISIBLE
                        downloadBtn.visibility = View.GONE
                        deleteBtn.visibility = View.GONE
                        progressBar.isIndeterminate = false
                    }
                    progressBar.progress = payload.progress
                    progressText.text = "${payload.progress}%"
                }
                InstallingPayload -> {
                    progressContainer.visibility = View.VISIBLE
                    downloadBtn.visibility = View.GONE
                    deleteBtn.visibility = View.GONE
                    progressBar.isIndeterminate = true
                    progressText.text = "Установка..."
                }
            }
        }

        private fun setInstalled(model: ModelInfo) {
            progressContainer.visibility = View.GONE
            downloadBtn.visibility = View.GONE
            deleteBtn.visibility = View.VISIBLE
            deleteBtn.isEnabled= true
            deleteBtn.setOnClickListener { onDelete(model) }
        }

        private fun setIdle(model: ModelInfo) {
            progressContainer.visibility = View.GONE
            deleteBtn.visibility = View.GONE
            downloadBtn.visibility = View.VISIBLE
            downloadBtn.text = "Скачать"
            downloadBtn.isEnabled = true
            downloadBtn.setOnClickListener {
                downloadBtn.isEnabled = false
                onDownload(model)
            }
        }

        private fun setDownloading(progress: Int) {
            progressContainer.visibility = View.VISIBLE
            downloadBtn.visibility = View.GONE
            deleteBtn.visibility = View.GONE
            progressBar.isIndeterminate = false
            progressBar.progress = progress
            progressText.text = "$progress%"
        }

        private fun setInstalling() {
            progressContainer.visibility = View.VISIBLE
            downloadBtn.visibility = View.GONE
            deleteBtn.visibility = View.GONE
            progressBar.isIndeterminate = true
            progressText.text = "Установка..."
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(items[position])

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) { holder.bind(items[position]); return }
        payloads.forEach { holder.applyPayload(it) }
    }

    override fun getItemCount() = items.size

    fun updateProgress(file: String, progress: Int) {
        installingSet.remove(file)
        progressMap[file] = progress
        val i = items.indexOfFirst { it.file == file }
        if (i != -1) notifyItemChanged(i, ProgressPayload(progress))
    }

    fun setInstalling(file: String) {
        progressMap.remove(file)
        installingSet.add(file)
        val i = items.indexOfFirst { it.file == file }
        if (i != -1) notifyItemChanged(i, InstallingPayload)
    }

    fun markDone(file: String) {
        progressMap.remove(file)
        installingSet.remove(file)
        val i = items.indexOfFirst { it.file == file }
        if (i != -1) notifyItemChanged(i)
    }

    fun markInstalled(file: String) {
        progressMap.remove(file)
        installingSet.remove(file)
        installedStems.add(file.removeSuffix(".zip"))
        val i = items.indexOfFirst { it.file == file }
        if (i != -1) notifyItemChanged(i)
    }

    fun removeInstalled(file: String) {
        installedStems.remove(file.removeSuffix(".zip"))
        val i = items.indexOfFirst { it.file == file }
        if (i != -1) notifyItemChanged(i)
    }
}
