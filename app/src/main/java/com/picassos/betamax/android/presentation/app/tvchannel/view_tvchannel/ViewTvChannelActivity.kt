package com.picassos.betamax.android.presentation.app.tvchannel.view_tvchannel

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager.LayoutParams
import android.widget.ImageView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.ExoTrackSelection
import com.picassos.betamax.android.core.utilities.Helper
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import com.google.android.material.chip.Chip
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import com.picassos.betamax.android.R
import com.picassos.betamax.android.core.configuration.Config
import com.picassos.betamax.android.core.utilities.Coroutines.collectLatestOnLifecycleStarted
import com.picassos.betamax.android.core.utilities.Helper.getSerializable
import com.picassos.betamax.android.core.utilities.Response
import com.picassos.betamax.android.databinding.ActivityViewTvchannelBinding
import com.picassos.betamax.android.domain.listener.OnTvChannelClickListener
import com.picassos.betamax.android.domain.model.Genres
import com.picassos.betamax.android.domain.model.TvChannelPlayerContent
import com.picassos.betamax.android.domain.model.TvChannels
import com.picassos.betamax.android.presentation.app.tvchannel.related_tvchannels.RelatedTvChannelsAdapter
import com.picassos.betamax.android.presentation.app.tvchannel.tvchannel_player.TvChannelPlayerActivity
import com.picassos.betamax.android.presentation.app.video_quality.video_quality_chooser.VideoQualityChooserBottomSheetModal
import com.picassos.betamax.android.presentation.app.video_quality.video_quality_chooser.VideoQualityChooserViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ViewTvChannelActivity : AppCompatActivity() {
    private lateinit var layout: ActivityViewTvchannelBinding
    private val viewTvChannelViewModel: ViewTvChannelViewModel by viewModels()
    private val videoQualityChooserViewModel: VideoQualityChooserViewModel by viewModels()

    private var exoPlayer: SimpleExoPlayer? = null

    private lateinit var tvChannel: TvChannels.TvChannel
    private var selectedGenre = 0
    private var selectedUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Helper.darkMode(this)

        layout = DataBindingUtil.setContentView(this, R.layout.activity_view_tvchannel)

        window.apply {
            addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!Config.DEVELOPMENT_BUILD) {
                setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE)
            }
        }

        layout.goBack.setOnClickListener { finish() }

        getSerializable(this@ViewTvChannelActivity, "tv_channel", TvChannels.TvChannel::class.java).also { tvChannel ->
            viewTvChannelViewModel.apply {
                this@ViewTvChannelActivity.tvChannel = tvChannel
                requestTvChannel(tvChannel.tvChannelId)
            }
        }

        collectLatestOnLifecycleStarted(viewTvChannelViewModel.viewTvChannel) { state ->
            if (state.isLoading) {
                exoPlayer.apply {
                    this?.let { player ->
                        player.stop()
                        player.clearMediaItems()
                    }
                }

                layout.apply {
                    recyclerRelatedTv.visibility = View.VISIBLE
                    internetConnection.root.visibility = View.GONE
                }
            }
            if (state.response != null) {
                val tvChannelDetails = state.response.tvChannelDetails.tvChannels[0]
                when (state.response.videoQuality) {
                    1 -> initializePlayer(url = tvChannelDetails.sdUrl)
                    2 -> initializePlayer(url = tvChannelDetails.hdUrl)
                    3 -> initializePlayer(url = tvChannelDetails.fhdUrl)
                }
            }
            if (state.error != null) {
                layout.apply {
                    recyclerRelatedTv.visibility = View.GONE
                    internetConnection.root.visibility = View.VISIBLE
                    internetConnection.tryAgain.setOnClickListener {
                        viewTvChannelViewModel.apply {
                            requestTvChannel(tvChannel.tvChannelId)
                            requestTvGenres()
                            when (selectedGenre) {
                                0 -> requestTvChannels()
                                else -> requestTvChannelsByGenre(selectedGenre)
                            }
                        }
                    }
                }
                if (state.error == Response.MALFORMED_REQUEST_EXCEPTION) {
                    Firebase.crashlytics.log("Request returned a malformed request or response.")
                }
            }
        }

        viewTvChannelViewModel.requestTvGenres()
        collectLatestOnLifecycleStarted(viewTvChannelViewModel.tvGenres) { state ->
            if (state.response != null) {
                state.response.genres.forEach { genre ->
                    layout.genresList.addView(createGenreChip(genre))
                }
            }
        }

        viewTvChannelViewModel.requestTvChannels()
        collectLatestOnLifecycleStarted(viewTvChannelViewModel.tvChannels) { state ->
            if (state.isLoading) {
                layout.progressbar.visibility = View.VISIBLE
            }
            if (state.response != null) {
                layout.progressbar.visibility = View.GONE

                val relatedTvChannelsAdapter = RelatedTvChannelsAdapter(tvChannel.tvChannelId, listener = object: OnTvChannelClickListener {
                    override fun onItemClick(tvChannel: TvChannels.TvChannel) {
                        viewTvChannelViewModel.apply {
                            this@ViewTvChannelActivity.tvChannel = tvChannel
                            requestTvChannel(tvChannel.tvChannelId)

                            when (selectedGenre) {
                                0 -> requestTvChannels()
                                else -> requestTvChannelsByGenre(selectedGenre)
                            }
                        }
                    }
                })
                layout.recyclerRelatedTv.apply {
                    layoutManager = LinearLayoutManager(this@ViewTvChannelActivity)
                    adapter = relatedTvChannelsAdapter
                }
                relatedTvChannelsAdapter.differ.submitList(state.response.tvChannels)

                if (state.response.tvChannels.isEmpty()) {
                    layout.noItems.visibility = View.VISIBLE
                } else {
                    layout.noItems.visibility = View.GONE
                }
            }
            if (state.error != null) {
                layout.progressbar.visibility = View.GONE
            }
        }

        collectLatestOnLifecycleStarted(videoQualityChooserViewModel.selectedVideoQuality) { isSafe ->
            isSafe?.let { quality ->
                videoQualityChooserViewModel.setVideoQuality(null)
                exoPlayer?.apply {
                    releasePlayer()
                    when (quality) {
                        1 -> initializePlayer(url = tvChannel.sdUrl, position = currentPosition)
                        2 -> initializePlayer(url = tvChannel.hdUrl, position = currentPosition)
                        3 -> initializePlayer(url = tvChannel.fhdUrl, position = currentPosition)
                    }
                }
            }
        }
    }

    private fun initializePlayer(url: String, position: Long = 0) {
        val trackSelector = DefaultTrackSelector(this@ViewTvChannelActivity, AdaptiveTrackSelection.Factory() as ExoTrackSelection.Factory)
        val renderersFactory = DefaultRenderersFactory(this@ViewTvChannelActivity).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
        val dataSourceFactory: DataSource.Factory = DefaultDataSourceFactory(this@ViewTvChannelActivity, Util.getUserAgent(this@ViewTvChannelActivity, tvChannel.userAgent))
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(Uri.parse(url)))

        selectedUrl = url

        exoPlayer = SimpleExoPlayer.Builder(this@ViewTvChannelActivity, renderersFactory)
            .setTrackSelector(trackSelector)
            .build().apply {
                addListener(playerListener)
                setMediaSource(mediaSource)
                prepare()
            }

        layout.exoPlayer.apply {
            player = exoPlayer
            setControllerVisibilityListener { visibility ->
                layout.controllerContainer.visibility = when (visibility) {
                    View.VISIBLE -> View.VISIBLE
                    else -> View.GONE
                }
            }
        }

        exoPlayer?.apply {
            seekTo(position)
            play()
        }

        layout.play.setOnClickListener {
            exoPlayer?.apply {
                when (isPlaying) {
                    true -> pause()
                    else -> play()
                }
            }
        }

        layout.exoPlayer.findViewById<ImageView>(R.id.fullscreen_mode).setOnClickListener {
            Intent(this@ViewTvChannelActivity, TvChannelPlayerActivity::class.java).also { intent ->
                intent.putExtra("playerContent", TvChannelPlayerContent(
                    url = url,
                    userAgent = tvChannel.userAgent,
                    currentPosition = exoPlayer!!.currentPosition.toInt()))
                startActivityForResult.launch(intent)
            }
        }

        layout.playerOptions.setOnClickListener {
            val videoQualityChooserBottomSheetModal = VideoQualityChooserBottomSheetModal()
            videoQualityChooserBottomSheetModal.show(supportFragmentManager, "TAG")
        }
    }

    private var playerListener = object: Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            if (isPlaying) {
                exoPlayer?.apply { playWhenReady = true }
                layout.play.setImageResource(R.drawable.icon_pause_filled)
            } else {
                exoPlayer?.apply { playWhenReady = false }
                layout.play.setImageResource(R.drawable.icon_play_filled)
            }
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            if (playbackState == Player.STATE_BUFFERING) {
                layout.apply {
                    playerProgressbar.visibility = View.VISIBLE
                    play.visibility = View.GONE
                }
            } else {
                layout.apply {
                    playerProgressbar.visibility = View.GONE
                    play.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun createGenreChip(genre: Genres.Genre): Chip {
        val chip = this@ViewTvChannelActivity.layoutInflater.inflate(R.layout.item_genre_selectable, null, false) as Chip
        return chip.apply {
            id = ViewCompat.generateViewId()
            text = genre.title
            setOnCheckedChangeListener { _, _ ->
                selectedGenre = genre.genreId
                viewTvChannelViewModel.apply {
                    requestTvChannelsByGenre(selectedGenre)
                }
            }
        }
    }

    private fun releasePlayer() {
        exoPlayer?.apply {
            stop()
            clearMediaItems()
        }
    }

    private var startActivityForResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult? ->
        if (result != null && result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                exoPlayer?.apply {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    seekTo(data.getIntExtra("currentPosition", 0).toLong())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        exoPlayer?.apply {
            play()
        }
        Helper.restrictVpn(this@ViewTvChannelActivity)
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.apply { pause() }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.apply { pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.apply {
            removeListener(playerListener)
            releasePlayer()
        }
        window.apply {
            clearFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!Config.DEVELOPMENT_BUILD) {
                clearFlags(LayoutParams.FLAG_SECURE)
            }
        }
    }
}