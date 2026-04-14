package com.spp.spotify.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.spp.spotify.R
import com.spp.spotify.auth.LoginActivity
import com.spp.spotify.databinding.FragmentProfileBinding

class ProfileFragment : Fragment() {

    private var _b: FragmentProfileBinding? = null
    private val b get() = _b!!
    private val vm: ProfileViewModel by viewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentProfileBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        vm.isLoading.observe(viewLifecycleOwner) { loading ->
            b.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            b.ivAvatar.visibility = if (loading) View.GONE else View.VISIBLE
        }

        vm.user.observe(viewLifecycleOwner) { user ->
            if (user == null) return@observe
            b.tvDisplayName.text = user.displayName ?: user.id
            b.tvEmail.text = user.email ?: ""
            b.tvFollowers.text = (user.followers?.total ?: 0).toString()
            b.tvPlan.text = user.product ?: "free"
            val imageUrl = user.images?.firstOrNull()?.url
            Glide.with(this)
                .load(imageUrl)
                .transform(CircleCrop())
                .placeholder(R.drawable.placeholder_music)
                .into(b.ivAvatar)
        }

        vm.logoutComplete.observe(viewLifecycleOwner) { done ->
            if (!done) return@observe
            val activity = requireActivity()
            activity.startActivity(Intent(activity, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity.finish()
        }

        b.btnLogout.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_message)
                .setPositiveButton(R.string.logout_confirm_yes) { _, _ -> vm.logout() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
