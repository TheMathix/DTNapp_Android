package com.example.mydtnapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mydtnapp.model.BundleInfo

class BundleAdapter(
    private val onDelete: (bundleId: String) -> Unit,
    private val onPlay: (path: String) -> Unit = {}
) : ListAdapter<BundleInfo, BundleAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BundleInfo>() {
            override fun areItemsTheSame(old: BundleInfo, new: BundleInfo) = old.bundleId == new.bundleId
            override fun areContentsTheSame(old: BundleInfo, new: BundleInfo) = old == new
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bundle, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvBundleId  = itemView.findViewById<TextView>(R.id.tvBundleId)
        private val tvBirdNames = itemView.findViewById<TextView>(R.id.tvBirdNames)
        private val tvAck       = itemView.findViewById<TextView>(R.id.tvAck)
        private val btnPlay     = itemView.findViewById<Button>(R.id.btnPlayAudio)
        private val btnDelete   = itemView.findViewById<Button>(R.id.btnDeleteBundle)

        fun bind(info: BundleInfo) {
            tvBundleId.text  = info.bundleId
            tvBirdNames.text = info.birdNames.joinToString(", ")
            tvAck.text       = if (info.ackSent) "ACK enviado" else "Pendente"

            // destaca o item em verde claro quando ACK recebido
            itemView.setBackgroundResource(
                if (info.ackSent) R.color.bundle_ack_background
                else android.R.color.transparent
            )

            val path = info.audioPath
            if (path != null) {
                btnPlay.visibility = View.VISIBLE
                btnPlay.setOnClickListener { onPlay(path) }
            } else {
                btnPlay.visibility = View.GONE
                btnPlay.setOnClickListener(null)
            }

            btnDelete.setOnClickListener { onDelete(info.bundleId) }
        }
    }
}
