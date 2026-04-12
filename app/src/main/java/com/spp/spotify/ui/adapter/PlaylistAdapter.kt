package com.spp.spotify.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spp.spotify.R
import com.spp.spotify.data.model.Playlist
import com.spp.spotify.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val onClick: (Playlist) -> Unit
) : ListAdapter<Playlist, PlaylistAdapter.VH>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemPlaylistBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: Playlist) {
            b.tvPlaylistName.text = p.name
            b.tvPlaylistInfo.text = "${p.tracks?.total ?: 0} songs"
            Glide.with(b.root.context).load(p.images.firstOrNull()?.url)
                .placeholder(R.drawable.placeholder_music).into(b.ivPlaylistImage)
            b.root.setOnClickListener { onClick(p) }
        }
    }

    class Diff : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(o: Playlist, n: Playlist) = o.id == n.id
        override fun areContentsTheSame(o: Playlist, n: Playlist) = o == n
    }
}
