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
    private val onDelete: (ModelInfo) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    private val progressMap = mutableMapOf<String, Int>()

    inner class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val nameText: TextView       = root.findViewById(R.id.item_model_name)
        val sizeText: TextView       = root.findViewById(R.id.item_model_size)
        val downloadBtn: MaterialButton = root.findViewById(R.id.item_download_btn)
        val progressBar: ProgressBar = root.findViewById(R.id.item_progress)
        val progressText: TextView   = root.findViewById(R.id.item_progress_text)
        val progressContainer: View = root.findViewById(R.id.item_progress_container)
        val deleteBtn: MaterialButton = root.findViewById(R.id.item_delete_btn)


        fun bind(model: ModelInfo) {
            nameText.text = model.name
            sizeText.text = "${model.size_mb} МБ"

            val stem = model.file.removeSuffix(".zip")
            val progress = progressMap[model.file]

            when {
                stem in installedStems -> setInstalled(model)
                progress != null -> setDownloading(progress)
                else -> setIdle(model)
            }
        }

        private fun setInstalled(model: ModelInfo) {
            progressContainer.visibility = View.GONE

            downloadBtn.visibility = View.GONE

            deleteBtn.visibility = View.VISIBLE
            deleteBtn.isEnabled = true

            deleteBtn.setOnClickListener {
                onDelete(model)
            }
        }

        private fun setIdle(model: ModelInfo) {
            progressContainer.visibility = View.GONE
            downloadBtn.visibility = View.VISIBLE
            downloadBtn.text        = "Скачать"
            downloadBtn.isEnabled   = true

            downloadBtn.setOnClickListener {
                downloadBtn.isEnabled = false
                onDownload(model)
            }
        }

        private fun setDownloading(progress: Int) {
            progressContainer.visibility = View.VISIBLE
            downloadBtn.visibility = View.GONE

            progressBar.progress = progress
            progressText.text = "$progress%"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    fun updateProgress(file: String, progress: Int) {
        progressMap[file] = progress
        notifyItemChanged(items.indexOfFirst { it.file == file })
    }

    fun markDone(file: String) {
        progressMap.remove(file)
        notifyItemChanged(items.indexOfFirst { it.file == file })
    }

    fun removeInstalled(file: String) {
        installedStems.remove(file.removeSuffix(".zip"))
        notifyItemChanged(items.indexOfFirst { it.file == file })
    }
}