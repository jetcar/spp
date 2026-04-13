package com.spp.spotify.ui.search

import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.spp.spotify.databinding.FragmentSearchBinding
import com.spp.spotify.ui.adapter.AlbumAdapter
import com.spp.spotify.ui.adapter.GenreAdapter
import com.spp.spotify.ui.adapter.PlaylistAdapter
import com.spp.spotify.ui.adapter.TrackAdapter
import com.spp.spotify.ui.player.PlayerViewModel

class SearchFragment : Fragment() {

    private var _b: FragmentSearchBinding? = null
    private val b get() = _b!!
    private val vm: SearchViewModel by viewModels()
    private val playerVm: PlayerViewModel by activityViewModels()

    private val genreAdapter = GenreAdapter {}
    private val trackAdapter = TrackAdapter { track -> playerVm.playTrack(track) }
    private val albumAdapter = AlbumAdapter {}
    private val playlistAdapter = PlaylistAdapter {}

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSearchBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.rvCategories.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = genreAdapter
        }
        b.rvTracks.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
        }
        b.rvAlbums.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = albumAdapter
        }
        b.rvPlaylists.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = playlistAdapter
        }

        b.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean { q?.let { vm.search(it) }; return true }
            override fun onQueryTextChange(q: String?): Boolean {
                if (q.isNullOrBlank()) vm.clearSearch() else vm.search(q); return true
            }
        })

        vm.categories.observe(viewLifecycleOwner) { genreAdapter.submitList(it) }
        vm.results.observe(viewLifecycleOwner) { results ->
            if (results == null) {
                b.categoriesLayout.visibility = View.VISIBLE
                b.resultsLayout.visibility = View.GONE
            } else {
                b.categoriesLayout.visibility = View.GONE
                b.resultsLayout.visibility = View.VISIBLE
                results.tracks?.items?.let { trackAdapter.submitList(it) }
                results.albums?.items?.let { albumAdapter.submitList(it) }
                results.playlists?.items?.let { playlistAdapter.submitList(it) }
                b.sectionTracks.visibility = if ((results.tracks?.items?.isNotEmpty()) == true) View.VISIBLE else View.GONE
                b.sectionAlbums.visibility = if ((results.albums?.items?.isNotEmpty()) == true) View.VISIBLE else View.GONE
                b.sectionPlaylists.visibility = if ((results.playlists?.items?.isNotEmpty()) == true) View.VISIBLE else View.GONE
            }
        }
        vm.isLoading.observe(viewLifecycleOwner) { b.progressBar.visibility = if (it) View.VISIBLE else View.GONE }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
