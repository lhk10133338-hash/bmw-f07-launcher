package com.hyteck.bmwf07launcher.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import kotlin.math.min

/**
 * Video surface for the start-up intro.
 *
 * The clip keeps its original aspect ratio and is fitted to the screen height, centred: a 16:9
 * clip on the 2560×720 head unit is scaled by min(viewW/videoW, viewH/videoH), so nothing is cut
 * off and the leftover side area stays pure black. (It used to be centre-cropped, which the user
 * reported as "너무 배율이 들어갔어" — the picture was zoomed up and cut top and bottom.)
 */
class CroppedVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr),
    TextureView.SurfaceTextureListener,
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnCompletionListener,
    MediaPlayer.OnErrorListener {

    var onFinished: (() -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var pendingUri: Uri? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    fun play(uri: Uri) {
        release()
        pendingUri = uri
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
        videoWidth = mp.videoWidth
        videoHeight = mp.videoHeight
        updateMatrix()
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

    override fun onCompletion(mp: MediaPlayer) {
        onFinished?.invoke()
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        onFailed?.invoke()
        return true
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
            updateMatrix()
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        updateMatrix()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    /**
     * Fit-centre: keep the clip's own aspect ratio and fit it inside the view, centred. The
     * smaller of the two ratios wins, so a clip that is wider than the screen is scaled down to
     * the width instead of being cropped.
     */
    private fun updateMatrix() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val scale = min(viewWidth / videoWidth, viewHeight / videoHeight)
        val matrix = Matrix()
        matrix.setScale(scale, scale, viewWidth / 2f, viewHeight / 2f)
        setTransform(matrix)
    }
}
