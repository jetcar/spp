package com.spp.spotify.ui.home

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.spp.spotify.R
import com.spp.spotify.databinding.FragmentHomeBinding
import com.spp.spotify.ui.adapter.AlbumAdapter
import com.spp.spotify.ui.adapter.PlaylistAdapter
import com.spp.spotify.ui.adapter.TrackAdapter
import com.spp.spotify.ui.player.PlayerViewModel

class HomeFragment : Fragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!
    private val vm: HomeViewModel by viewModels()
    private val playerVm: PlayerViewModel by activityViewModels()

    private val featuredAdapter = PlaylistAdapter {}
    private val newReleasesAdapter = AlbumAdapter {}
    private val recentAdapter = TrackAdapter { track -> playerVm.playTrack(track) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentHomeBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.rvFeaturedPlaylists.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = featuredAdapter
        }
        b.rvNewReleases.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = newReleasesAdapter
        }
        b.rvRecentlyPlayed.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAdapter
        }

        vm.user.observe(viewLifecycleOwner) { user ->
            b.tvGreeting.text = if (user != null) "Hello, ${user.displayName ?: "there"}!" else "Good music awaits!"
        }
        vm.featuredPlaylists.observe(viewLifecycleOwner) { list ->
            featuredAdapter.submitList(list)
            b.sectionFeatured.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
        }
        vm.newReleases.observe(viewLifecycleOwner) { list ->
            newReleasesAdapter.submitList(list)
            b.sectionNewReleases.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
        }
        vm.recentlyPlayed.observe(viewLifecycleOwner) { list ->
            recentAdapter.submitList(list)
            b.sectionRecentlyPlayed.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
        }
        vm.isLoading.observe(viewLifecycleOwner) { b.swipeRefresh.isRefreshing = it }
        b.swipeRefresh.setOnRefreshListener { vm.loadAll() }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
