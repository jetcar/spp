package com.spp.spotify.ui.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.*
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.spp.spotify.R
import com.spp.spotify.databinding.FragmentNowPlayingBinding
import java.util.concurrent.TimeUnit

class NowPlayingFragment : Fragment() {

    private var _b: FragmentNowPlayingBinding? = null
    private val b get() = _b!!
    private val vm: PlayerViewModel by activityViewModels()

    private var userSeeking = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentNowPlayingBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm.startPolling()
        setupControls()
        setupObservers()
    }

    private fun setupControls() {
        b.btnBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        b.btnPlayPause.setOnClickListener { vm.togglePlayPause() }
        b.btnNext.setOnClickListener { vm.skipToNext() }
        b.btnPrevious.setOnClickListener { vm.skipToPrevious() }
        b.btnShuffle.setOnClickListener { vm.toggleShuffle() }
        b.btnRepeat.setOnClickListener { vm.cycleRepeat() }
        b.btnHeart.setOnClickListener { vm.toggleSave() }

        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = vm.durationMs.value ?: 0L
                    b.tvPosition.text = formatMs(progress.toLong() * dur / 100L)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                sb?.let {
                    val dur = vm.durationMs.value ?: 0L
                    vm.seekToPosition(it.progress.toLong() * dur / 100L)
                }
            }
        })
    }

    private fun setupObservers() {
        vm.currentTrack.observe(viewLifecycleOwner) { track ->
            track ?: return@observe
            b.tvTrackName.text = track.name
            b.tvArtistName.text = track.artists.joinToString(", ") { it.name }
            b.tvDuration.text = formatMs(track.durationMs)
            val url = track.album?.images?.firstOrNull()?.url
            Glide.with(this).asBitmap().load(url)
                .transform(RoundedCorners(32))
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, t: Transition<in Bitmap>?) {
                        b.ivAlbumArt.setImageBitmap(resource)
                        Palette.from(resource).generate { p ->
                            val dark = p?.getDarkMutedColor(Color.parseColor("#121212"))
                                ?: Color.parseColor("#121212")
                            b.root.setBackgroundColor(dark)
                        }
                    }
                    override fun onLoadCleared(p: Drawable?) {}
                })
        }

        vm.isPlaying.observe(viewLifecycleOwner) { playing ->
            b.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_arrow_24)
        }

        vm.progressMs.observe(viewLifecycleOwner) { ms ->
            if (!userSeeking) {
                b.tvPosition.text = formatMs(ms)
                val dur = vm.durationMs.value ?: 0L
                b.seekBar.progress = if (dur > 0) (ms * 100L / dur).toInt() else 0
            }
        }

        vm.shuffleState.observe(viewLifecycleOwner) { b.btnShuffle.alpha = if (it) 1f else 0.4f }

        vm.repeatState.observe(viewLifecycleOwner) { state ->
            b.btnRepeat.alpha = if (state != "off") 1f else 0.4f
            b.btnRepeat.setImageResource(
                if (state == "track") R.drawable.ic_repeat_one_24 else R.drawable.ic_repeat_24
            )
        }

        vm.isSaved.observe(viewLifecycleOwner) { saved ->
            b.btnHeart.setImageResource(if (saved) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24)
        }
    }

    private fun formatMs(ms: Long): String {
        val m = TimeUnit.MILLISECONDS.toMinutes(ms)
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return "%d:%02d".format(m, s)
    }

    override fun onDestroyView() { super.onDestroyView(); vm.stopPolling(); _b = null }
}
