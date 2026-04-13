package com.spp.spotify.ui.library

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.spp.spotify.BuildConfig
import com.spp.spotify.R
import com.spp.spotify.databinding.FragmentLibraryBinding
import com.spp.spotify.ui.adapter.AlbumAdapter
import com.spp.spotify.ui.adapter.PlaylistAdapter
import com.spp.spotify.ui.adapter.TrackAdapter
import com.spp.spotify.ui.player.PlayerViewModel

class LibraryFragment : Fragment() {

    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!
    private val vm: LibraryViewModel by viewModels()
    private val playerVm: PlayerViewModel by activityViewModels()

    private val playlistAdapter = PlaylistAdapter {}
    private val albumAdapter = AlbumAdapter {}
    private val trackAdapter = TrackAdapter { track -> playerVm.playTrack(track) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentLibraryBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.recyclerView.layoutManager = LinearLayoutManager(context)
        b.recyclerView.adapter = playlistAdapter

        b.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { vm.selectTab(LibraryViewModel.Tab.PLAYLISTS); b.recyclerView.adapter = playlistAdapter }
                    1 -> { vm.selectTab(LibraryViewModel.Tab.ALBUMS);    b.recyclerView.adapter = albumAdapter }
                    2 -> { vm.selectTab(LibraryViewModel.Tab.LIKED);     b.recyclerView.adapter = trackAdapter }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        vm.playlists.observe(viewLifecycleOwner)   { playlistAdapter.submitList(it) }
        vm.albums.observe(viewLifecycleOwner)      { albumAdapter.submitList(it) }
        vm.likedTracks.observe(viewLifecycleOwner) { trackAdapter.submitList(it) }
        vm.isLoading.observe(viewLifecycleOwner)   { b.progressBar.visibility = if (it) View.VISIBLE else View.GONE }

        b.tvVersion.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
