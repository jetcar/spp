package com.spp.spotify.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spp.spotify.data.model.Category
import com.spp.spotify.databinding.ItemGenreBinding

class GenreAdapter(
    private val onClick: (Category) -> Unit
) : ListAdapter<Category, GenreAdapter.VH>(Diff()) {

    private val colors = listOf(
        "#E8115B", "#509BF5", "#1DB954", "#FF6437",
        "#B02897", "#477D95", "#E91429", "#608108"
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemGenreBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position), colors[position % colors.size])

    inner class VH(private val b: ItemGenreBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(cat: Category, color: String) {
            b.tvCategoryName.text = cat.name
            b.root.setCardBackgroundColor(Color.parseColor(color))
            Glide.with(b.root.context).load(cat.icons.firstOrNull()?.url).into(b.ivCategoryImage)
            b.root.setOnClickListener { onClick(cat) }
        }
    }

    class Diff : DiffUtil.ItemCallback<Category>() {
        override fun areItemsTheSame(o: Category, n: Category) = o.id == n.id
        override fun areContentsTheSame(o: Category, n: Category) = o == n
    }
}
