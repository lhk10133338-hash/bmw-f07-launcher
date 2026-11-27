package com.hyteck.bmwf07launcher.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video surface for the start-up intro.
 *
 * v3.1: the clip is shown at its own resolution, centred, with no matrix at all. A TextureView
 * stretches whatever it draws over its whole view, so v3.0 laid a `setTransform` matrix over a
 * full-screen view to undo that stretch — and whenever that matrix disagreed with the real view
 * size the picture came out stretched sideways. Now the *view itself* is given the video's own
 * ratio: the smaller of min(parentW/videoW, parentH/videoH) fits the clip inside the intro layer,
 * the view is that many pixels wide and high and the parent centres it. Nothing is scaled beyond
 * its original pixels, nothing is cropped, and no matrix can drift.
 */
class CroppedVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr),
    TextureView.SurfaceTextureListener,
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnCompletionListener,
    MediaPlayer.OnErrorListener,
    MediaPlayer.OnVideoSizeChangedListener {

    var onFinished: (() -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var pendingUri: Uri? = null
    private var videoWidth = 0
    private var videoHeight = 0

    /** True once the clip really started; the watchdog in [play] gives up until then (v4.11). */
    private var prepared = false

    private val handler = Handler(Looper.getMainLooper())

    /**
     * v4.11: a clip that never prepares must not hold the boot screen forever.
     *
     * The intro layer covers both panes, so a `prepareAsync()` that never answers (or a
     * `SurfaceTexture` that is never handed out) leaves a black screen with nothing on it until
     * someone taps it. The caller's `onFailed` ends the boot screen the same way a broken file
     * does; a clip normally prepares in well under a second, so the timeout costs a working clip
     * nothing.
     */
    private val prepareWatchdog = Runnable {
        if (prepared) return@Runnable
        Log.w(TAG, "intro clip did not start within ${PREPARE_TIMEOUT_MS}ms; ending the boot screen")
        onFailed?.invoke()
    }

    /** Re-fits the clip when the intro layer is measured or the screen size changes. */
    private val parentLayoutListener = View.OnLayoutChangeListener {
            _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            fitToParent()
        }
    }

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    fun play(uri: Uri) {
        release()
        pendingUri = uri
        handler.postDelayed(prepareWatchdog, PREPARE_TIMEOUT_MS)
        if (isAvailable) open(uri)
    }

    fun stopPlayback() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) mp.pause()
        } catch (ignored: Exception) {
            // Not playing.
        }
    }

    fun release() {
        handler.removeCallbacks(prepareWatchdog)
        prepared = false
        player?.let { mp ->
            try {
                mp.setSurface(null)
            } catch (ignored: Exception) {
                // Surface already gone.
            }
            try {
                mp.release()
            } catch (ignored: Exception) {
                // Already released.
            }
        }
        player = null
        surface?.release()
        surface = null
        pendingUri = null
        videoWidth = 0
        videoHeight = 0
    }

    private fun open(uri: Uri) {
        val texture = surfaceTexture ?: return
        val videoSurface = Surface(texture)
        surface = videoSurface
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setOnPreparedListener(this)
            mp.setOnCompletionListener(this)
            mp.setOnErrorListener(this)
            mp.setOnVideoSizeChangedListener(this)
            mp.setSurface(videoSurface)
            mp.setDataSource(context, uri)
            mp.isLooping = false
            mp.prepareAsync()
        } catch (e: Exception) {
            release()
            onFailed?.invoke()
        }
    }

    override fun onPrepared(mp: MediaPlayer) {
        prepared = true
        handler.removeCallbacks(prepareWatchdog)
        takeVideoSize(mp)
        try {
            mp.setVolume(0f, 0f)
        } catch (ignored: Exception) {
            // Volume control is optional.
        }
        try {
            mp.start()
        } catch (ignored: Exception) {
            onFailed?.invoke()
        }
    }

    override fun onVideoSizeChanged(mp: MediaPlayer, width: Int, height: Int) {
        takeVideoSize(mp)
    }

    private fun takeVideoSize(mp: MediaPlayer) {
        val w = mp.videoWidth
        val h = mp.videoHeight
        if (w <= 0 || h <= 0) return
        videoWidth = w
        videoHeight = h
        fitToParent()
    }

    override fun onCompletion(mp: MediaPlayer) {
        onFinished?.invoke()
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        handler.removeCallbacks(prepareWatchdog)
        onFailed?.invoke()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.addOnLayoutChangeListener(parentLayoutListener)
        post { fitToParent() }
    }

    override fun onDetachedFromWindow() {
        (parent as? View)?.removeOnLayoutChangeListener(parentLayoutListener)
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (player == null) {
            pendingUri?.let { open(it) }
        } else {
            val videoSurface = Surface(st)
            surface?.release()
            surface = videoSurface
            try {
                player?.setSurface(videoSurface)
            } catch (ignored: Exception) {
                // Ignore; the player is being torn down.
            }
            fitToParent()
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        // The view is sized to the clip's own ratio, so a surface resize needs no matrix.
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        try {
            player?.setSurface(null)
        } catch (ignored: Exception) {
            // Ignore.
        }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        // No-op.
    }

    /**
     * Sizes the view to the clip's own pixels: keep the video's aspect ratio, fit it inside the
     * intro layer (the smaller of the two ratios wins, so nothing is cropped), never enlarge a
     * clip beyond its own resolution, and let the parent centre it on black.
     */
    private fun fitToParent() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val container = parent as? View ?: return
        val availableWidth = container.width
        val availableHeight = container.height
        if (availableWidth <= 0 || availableHeight <= 0) return

        val fit = min(
            availableWidth.toFloat() / videoWidth,
            availableHeight.toFloat() / videoHeight
        ).coerceAtMost(1f)
        val targetWidth = (videoWidth * fit).roundToInt().coerceAtLeast(1)
        val targetHeight = (videoHeight * fit).roundToInt().coerceAtLeast(1)

        val current = layoutParams
        if (current != null && current.width == targetWidth && current.height == targetHeight) {
            return
        }
        layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
        requestLayout()
    }

    companion object {
        private const val TAG = "MNLauncher"

        /** A local clip prepares in well under a second; this is only a guard against a hang. */
        private const val PREPARE_TIMEOUT_MS = 8_000L
    }
}
