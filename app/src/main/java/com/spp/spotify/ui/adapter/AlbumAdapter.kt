package com.spp.spotify.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spp.spotify.R
import com.spp.spotify.data.model.Album
import com.spp.spotify.databinding.ItemAlbumBinding

class AlbumAdapter(
    private val onClick: (Album) -> Unit
) : ListAdapter<Album, AlbumAdapter.VH>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemAlbumBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(album: Album) {
            b.tvAlbumName.text = album.name
            b.tvAlbumArtist.text = album.artists.joinToString(", ") { it.name }
            b.tvAlbumYear.text = album.releaseDate.take(4)
            Glide.with(b.root.context).load(album.images.firstOrNull()?.url)
                .placeholder(R.drawable.placeholder_music).into(b.ivAlbumImage)
            b.root.setOnClickListener { onClick(album) }
        }
    }

    class Diff : DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(o: Album, n: Album) = o.id == n.id
        override fun areContentsTheSame(o: Album, n: Album) = o == n
    }
}
