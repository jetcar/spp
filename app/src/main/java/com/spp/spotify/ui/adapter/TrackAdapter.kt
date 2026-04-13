package com.spp.spotify.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spp.spotify.R
import com.spp.spotify.data.model.Track
import com.spp.spotify.databinding.ItemTrackBinding
import java.util.concurrent.TimeUnit

class TrackAdapter(
    private val onClick: (Track) -> Unit
) : ListAdapter<Track, TrackAdapter.VH>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(track: Track) {
            b.tvTrackName.text = track.name
            b.tvArtistName.text = track.artists.joinToString(", ") { it.name }
            b.tvDuration.text = formatMs(track.durationMs)
            Glide.with(b.root.context).load(track.album?.images?.firstOrNull()?.url)
                .placeholder(R.drawable.placeholder_music).into(b.ivTrackImage)
            b.root.setOnClickListener { onClick(track) }
        }

        private fun formatMs(ms: Long): String {
            val m = TimeUnit.MILLISECONDS.toMinutes(ms)
            val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            return "%d:%02d".format(m, s)
        }
    }

    class Diff : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(o: Track, n: Track) = o.id == n.id
        override fun areContentsTheSame(o: Track, n: Track) = o == n
    }
}
