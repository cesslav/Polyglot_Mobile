package com.example.polyglotapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class DownloadsAdapter(
    private val items: List<ModelInfo>,
    private val installedStems: Set<String>,
    private val onDownload: (ModelInfo, ViewHolder) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    inner class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val nameText:     TextView      = root.findViewById(R.id.item_model_name)
        val sizeText:     TextView      = root.findViewById(R.id.item_model_size)
        val downloadBtn:  MaterialButton = root.findViewById(R.id.item_download_btn)
        val progressBar:  ProgressBar   = root.findViewById(R.id.item_progress)
        val progressText: TextView      = root.findViewById(R.id.item_progress_text)

        fun setInstalled() {
            progressBar.visibility  = View.GONE
            progressText.visibility = View.GONE
            downloadBtn.visibility  = View.VISIBLE
            downloadBtn.text        = "Готово ✓"
            downloadBtn.isEnabled   = false
        }

        fun setIdle() {
            progressBar.visibility  = View.GONE
            progressText.visibility = View.GONE
            downloadBtn.visibility  = View.VISIBLE
            downloadBtn.text        = "Скачать"
            downloadBtn.isEnabled   = true
        }

        fun setDownloading(progress: Int) {
            downloadBtn.visibility  = View.GONE
            progressBar.visibility  = View.VISIBLE
            progressText.visibility = View.VISIBLE
            progressBar.progress    = progress
            progressText.text       = "$progress%"
        }

        fun setDone() {
            progressBar.visibility  = View.GONE
            progressText.visibility = View.GONE
            downloadBtn.visibility  = View.VISIBLE
            downloadBtn.text        = "Готово ✓"
            downloadBtn.isEnabled   = false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = items[position]
        holder.nameText.text = model.name
        holder.sizeText.text = "${model.size_mb} МБ"

        val stem = model.file.removeSuffix(".zip")
        if (stem in installedStems) {
            holder.setInstalled()
        } else {
            holder.setIdle()
            holder.downloadBtn.setOnClickListener {
                holder.downloadBtn.isEnabled = false
                onDownload(model, holder)
            }
        }
    }

    override fun getItemCount() = items.size
}
