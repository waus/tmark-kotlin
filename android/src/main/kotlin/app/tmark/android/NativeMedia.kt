package app.tmark.android

import android.annotation.SuppressLint
import android.content.Context
import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.text.style.ReplacementSpan
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.appcompat.content.res.AppCompatResources
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.R as MaterialR
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Bounded downloads and sampled bitmaps; detached views never retain a running UI callback. */
@SuppressLint("ViewConstructor") // Internal, programmatically constructed widgets.
internal class NativeImageView(
    context: Context,
    private val source: String,
    private val loader: MediaContentLoader,
    private val onError: ((Throwable) -> Unit)?,
    var isSpoilerHidden: Boolean = false,
    private val fillBounds: Boolean = false,
    private val onImageSize: ((Int, Int) -> Unit)? = null,
    cropToBounds: Boolean = fillBounds,
) : LinearLayout(context) {
    private val image = ImageView(context).apply {
        adjustViewBounds = !fillBounds
        scaleType = if (cropToBounds) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
    }
    private val status = MaterialTextView(context).apply { setText(R.string.tmark_loading) }
    private var work: Future<*>? = null
    private var generation = 0
    private var loaded = false
    private var original: Bitmap? = null
    private var animation: LottieAnimationView? = null
    init {
        orientation = VERTICAL
        addView(image, if (fillBounds) LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f) else LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(status)
    }
    fun revealSpoiler() {
        isSpoilerHidden = false
        original?.let(image::setImageBitmap)
        animation?.let { image.visibility = GONE; it.visibility = VISIBLE; if (isAttachedToWindow) it.playAnimation() }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (loaded) { if (!isSpoilerHidden) animation?.playAnimation(); return }
        val token = ++generation
        val needsPreview = isSpoilerHidden
        work = imageExecutor.submit {
            val result = runCatching {
                val bytes = loader.getContent(source)
                val format = imageFormat(bytes)
                if (format == ImageFormat.LOTTIE) {
                    val parsed = LottieCompositionFactory.fromJsonInputStreamSync(ByteArrayInputStream(bytes), null)
                    val composition = parsed.value ?: throw (parsed.exception ?: IllegalArgumentException("Invalid Lottie image"))
                    LoadedImage.Animation(composition)
                } else {
                    require(format != null) { "Unsupported image format" }
                    val bitmap = decodeBitmap(bytes, 2048)
                    LoadedImage.Static(bitmap, if (needsPreview) blurSpoiler(bitmap) else null)
                }
            }
            mainHandler.post {
                if (token != generation || !isAttachedToWindow) return@post
                result.fold({ loadedImage ->
                    when (loadedImage) {
                        is LoadedImage.Static -> {
                            original = loadedImage.bitmap
                            onImageSize?.invoke(loadedImage.bitmap.width, loadedImage.bitmap.height)
                            if (isSpoilerHidden && loadedImage.preview != null)
                                image.setImageDrawable(SpoilerPreviewDrawable(resources, loadedImage.preview, loadedImage.bitmap.width, loadedImage.bitmap.height))
                            else image.setImageBitmap(loadedImage.bitmap)
                        }
                        is LoadedImage.Animation -> {
                            val composition = loadedImage.composition
                            onImageSize?.invoke(composition.bounds.width(), composition.bounds.height())
                            val view = LottieAnimationView(context).apply {
                                setComposition(composition)
                                repeatCount = LottieDrawable.INFINITE
                                scaleType = image.scaleType
                                adjustViewBounds = image.adjustViewBounds
                            }
                            animation = view
                            addView(view, 0, if (fillBounds) LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
                                else LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                            if (isSpoilerHidden) {
                                val preview = lottiePreview(composition)
                                image.setImageDrawable(SpoilerPreviewDrawable(resources, blurSpoiler(preview), composition.bounds.width(), composition.bounds.height()))
                                view.visibility = GONE
                            } else {
                                image.visibility = GONE
                                view.playAnimation()
                            }
                        }
                    }
                    loaded = true
                    status.visibility = GONE
                }, {
                    status.setText(R.string.tmark_media_unavailable); onError?.invoke(it)
                })
            }
        }
    }
    override fun onDetachedFromWindow() { generation++; work?.cancel(true); work = null; animation?.pauseAnimation(); super.onDetachedFromWindow() }
}

private sealed interface LoadedImage {
    data class Static(val bitmap: Bitmap, val preview: Bitmap?) : LoadedImage
    data class Animation(val composition: LottieComposition) : LoadedImage
}

private fun lottiePreview(composition: LottieComposition): Bitmap {
    val bounds = composition.bounds
    val scale = minOf(1f, 256f / maxOf(1, bounds.width(), bounds.height()))
    val width = maxOf(1, (bounds.width() * scale).toInt())
    val height = maxOf(1, (bounds.height() * scale).toInt())
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val drawable = LottieDrawable().apply { setComposition(composition); setBounds(0, 0, width, height) }
        drawable.draw(Canvas(this))
    }
}

private class SpoilerPreviewDrawable(resources: Resources, preview: Bitmap, private val originalWidth: Int, private val originalHeight: Int) :
    BitmapDrawable(resources, preview) {
    override fun getIntrinsicWidth() = originalWidth
    override fun getIntrinsicHeight() = originalHeight
}

/** Small, heavily blurred preview avoids a second download when the spoiler is revealed. */
internal fun blurSpoiler(source: Bitmap): Bitmap {
    val scale = 128f / maxOf(source.width, source.height)
    val width = maxOf(1, (source.width * minOf(1f, scale)).toInt())
    val height = maxOf(1, (source.height * minOf(1f, scale)).toInt())
    val small = Bitmap.createScaledBitmap(source, width, height, true)
    var pixels = IntArray(width * height).also { small.getPixels(it, 0, width, 0, 0, width, height) }
    val radius = 12
    repeat(2) {
        val horizontal = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (dx in -radius..radius) {
                val pixel = pixels[y * width + (x + dx).coerceIn(0, width - 1)]
                a += pixel ushr 24; r += pixel shr 16 and 255
                g += pixel shr 8 and 255; b += pixel and 255
            }
            val count = radius * 2 + 1
            horizontal[y * width + x] = (a / count shl 24) or (r / count shl 16) or (g / count shl 8) or (b / count)
        }
        val vertical = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (dy in -radius..radius) {
                val pixel = horizontal[(y + dy).coerceIn(0, height - 1) * width + x]
                a += pixel ushr 24; r += pixel shr 16 and 255
                g += pixel shr 8 and 255; b += pixel and 255
            }
            val count = radius * 2 + 1
            vertical[y * width + x] = (a / count shl 24) or (r / count shl 16) or (g / count shl 8) or (b / count)
        }
        pixels = vertical
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

/** Shows the bracketed alt text until the inline image has loaded. */
internal class NativeIconSpan(
    private val owner: TextView,
    private val source: String,
    private val loader: MediaContentLoader,
    private val size: Int,
    private val onError: ((Throwable) -> Unit)?,
) : ReplacementSpan(), View.OnAttachStateChangeListener {
    private var image: BitmapDrawable? = null
    private var work: Future<*>? = null
    private var generation = 0
    private var loaded = false
    init { owner.addOnAttachStateChangeListener(this); if (owner.isAttachedToWindow) onViewAttachedToWindow(owner) }
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (image == null) return kotlin.math.ceil(paint.measureText(text, start, end).toDouble()).toInt()
        fm?.let {
            it.ascent = minOf(it.ascent, -size)
            it.top = minOf(it.top, -size)
        }
        return size
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val drawable = image
        if (drawable == null) canvas.drawText(text, start, end, x, y.toFloat(), paint)
        else {
            canvas.save()
            canvas.translate(x, (y - size).toFloat())
            drawable.draw(canvas)
            canvas.restore()
        }
    }
    override fun onViewAttachedToWindow(view: View) {
        if (loaded) return
        val token = ++generation
        work = imageExecutor.submit {
            val result = runCatching {
                val bytes = loader.getContent(source)
                when (imageFormat(bytes)) {
                    ImageFormat.LOTTIE -> {
                        val parsed = LottieCompositionFactory.fromJsonInputStreamSync(ByteArrayInputStream(bytes), null)
                        lottiePreview(parsed.value ?: throw (parsed.exception ?: IllegalArgumentException("Invalid Lottie icon")))
                    }
                    ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.AVIF -> decodeBitmap(bytes, 128)
                    null -> throw IllegalArgumentException("Unsupported icon format")
                }
            }
            mainHandler.post {
                if (token != generation || !owner.isAttachedToWindow) return@post
                result.fold({ bitmap ->
                    image = BitmapDrawable(owner.resources, bitmap).apply { setBounds(0, 0, size, size) }
                    loaded = true
                    owner.text = owner.text
                    owner.requestLayout()
                    owner.invalidate()
                }, { onError?.invoke(it) })
            }
        }
    }
    override fun onViewDetachedFromWindow(view: View) { generation++; work?.cancel(true); work = null }
}

internal fun decodeBitmap(bytes: ByteArray, maxDimension: Int): Bitmap {
    require(maxDimension > 0)
    require(imageFormat(bytes) in setOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.AVIF)) { "Unsupported image format" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (bounds.outWidth / options.inSampleSize > maxDimension || bounds.outHeight / options.inSampleSize > maxDimension) options.inSampleSize *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Unable to decode image")
}

private val imageExecutor = Executors.newFixedThreadPool(3)
private val mainHandler = Handler(Looper.getMainLooper())

private fun mediaIconButton(context: Context, icon: Int, label: Int) = ImageButton(context).apply {
    setImageDrawable(AppCompatResources.getDrawable(context, icon))
    imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this, MaterialR.attr.colorOnSurface))
    contentDescription = context.getString(label)
    scaleType = ImageView.ScaleType.CENTER
    val ripple = TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
    setBackgroundResource(ripple.resourceId)
}

private fun circularPlayButton(context: Context) = mediaIconButton(context, R.drawable.tmark_audio_play, R.string.tmark_play).apply {
    val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    val onPrimary = MaterialColors.getColor(this, MaterialR.attr.colorOnPrimary)
    imageTintList = ColorStateList.valueOf(onPrimary)
    background = RippleDrawable(
        ColorStateList.valueOf((onPrimary and 0x00ffffff) or 0x33000000),
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primary) },
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) },
    )
}

@SuppressLint("ViewConstructor") // Internal, programmatically constructed widgets.
internal class NativeVideoView(context: Context, private val source: String, private val loader: MediaContentLoader,
                               private val preview: String, private val loop: Boolean,
                               private val onError: ((Throwable) -> Unit)?, fillBounds: Boolean = false,
                               onImageSize: ((Int, Int) -> Unit)? = null,
                               cropToBounds: Boolean = fillBounds) : FrameLayout(context) {
    private var previewLoaded = false
    private val image = NativeImageView(context, preview, loader, onError, fillBounds = fillBounds,
        onImageSize = { width, height ->
            previewLoaded = true
            onImageSize?.invoke(width, height)
            if (loop && isAttachedToWindow) startLoop()
        }, cropToBounds = cropToBounds)
    private val video = if (loop) VideoView(context).apply { visibility = INVISIBLE } else null
    private var work: Future<*>? = null
    private var file: java.io.File? = null
    private var generation = 0
    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, if (fillBounds) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        video?.let { playerView ->
            addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            playerView.setOnPreparedListener { player ->
                player.isLooping = true
                player.setVolume(0f, 0f)
                playerView.visibility = VISIBLE
                image.visibility = INVISIBLE
                syncPlayback()
            }
            playerView.setOnErrorListener { _, what, extra ->
                playerView.visibility = INVISIBLE
                image.visibility = VISIBLE
                file?.delete(); file = null
                onError?.invoke(IllegalStateException("Video error $what/$extra"))
                true
            }
        }
        if (!loop) circularPlayButton(context).let { button ->
            val size = (48 * resources.displayMetrics.density + 0.5f).toInt()
            addView(button, LayoutParams(size, size, android.view.Gravity.CENTER))
            button.setOnClickListener { showVideoDialog(context, source, loader, onError) }
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (loop && previewLoaded && file == null) startLoop()
        syncPlayback()
    }
    private fun startLoop() {
        val playerView = video ?: return
        if (work != null || file != null) return
        val token = ++generation
        work = imageExecutor.submit {
            val result = runCatching { writeMediaFile(context, loader.getContent(source)) }
            mainHandler.post {
                if (token != generation || !isAttachedToWindow) { result.getOrNull()?.delete(); return@post }
                work = null
                result.fold({ loaded -> file = loaded; playerView.setVideoURI(Uri.fromFile(loaded)) }, onError ?: {})
            }
        }
    }
    override fun onDetachedFromWindow() {
        generation++; work?.cancel(true); work = null
        video?.stopPlayback(); video?.visibility = INVISIBLE; image.visibility = VISIBLE
        file?.delete(); file = null
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncPlayback()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncPlayback()
    }
    private fun syncPlayback() {
        val playerView = video ?: return
        if (playerView.visibility != VISIBLE) return
        if (isShown && windowVisibility == View.VISIBLE) playerView.start() else playerView.pause()
    }
}

private fun showVideoDialog(context: Context, source: String, loader: MediaContentLoader, onError: ((Throwable) -> Unit)?) {
    val dialog = Dialog(context)
    val content = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
    val video = VideoView(context)
    val loading = ProgressBar(context)
    val error = MaterialTextView(context).apply {
        setText(R.string.tmark_media_unavailable)
        setTextColor(Color.WHITE)
        visibility = View.GONE
    }
    val close = MaterialButton(context).apply {
        setText(R.string.tmark_close)
        setOnClickListener { dialog.dismiss() }
    }
    content.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
    content.addView(loading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
    content.addView(error, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
    content.addView(close, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END))
    var work: Future<*>? = null
    var file: java.io.File? = null
    var dismissed = false
    dialog.setOnDismissListener {
        dismissed = true
        work?.cancel(true)
        video.stopPlayback()
        file?.delete()
    }
    fun showError(cause: Throwable) {
        loading.visibility = View.GONE
        error.visibility = View.VISIBLE
        onError?.invoke(cause)
    }
    video.setMediaController(MediaController(context).apply { setAnchorView(video) })
    var videoWidth = 0
    var videoHeight = 0
    fun centerVideo() {
        if (videoWidth <= 0 || videoHeight <= 0 || content.width <= 0 || content.height <= 0) return
        val scale = minOf(content.width.toFloat() / videoWidth, content.height.toFloat() / videoHeight)
        video.layoutParams = FrameLayout.LayoutParams(
            maxOf(1, (videoWidth * scale).toInt()),
            maxOf(1, (videoHeight * scale).toInt()),
            android.view.Gravity.CENTER,
        )
    }
    content.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) centerVideo()
    }
    video.setOnPreparedListener { player ->
        videoWidth = player.videoWidth
        videoHeight = player.videoHeight
        loading.visibility = View.GONE
        centerVideo()
        video.start()
    }
    video.setOnErrorListener { _, what, extra ->
        showError(IllegalStateException("Video error $what/$extra"))
        true
    }
    dialog.setContentView(content)
    dialog.show()
    dialog.window?.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
    work = imageExecutor.submit {
        val result = runCatching { writeMediaFile(context, loader.getContent(source)) }
        mainHandler.post {
            if (dismissed) { result.getOrNull()?.delete(); return@post }
            result.fold({ loaded -> file = loaded; video.setVideoURI(Uri.fromFile(loaded)) }, ::showError)
        }
    }
}

@SuppressLint("ViewConstructor") // Internal, programmatically constructed widgets.
internal class NativeAudioView(context: Context, private val source: String, private val loader: MediaContentLoader,
                               private val onError: ((Throwable) -> Unit)?) : LinearLayout(context) {
    private val play = circularPlayButton(context)
    private val elapsed = MaterialTextView(context).apply { text = "0:00"; setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyLarge) }
    private val progress = LinearProgressIndicator(context).apply {
        max = 1000
        trackThickness = dp(4)
        trackStopIndicatorSize = 0
        setWavelengthDeterminate(dp(24))
        waveSpeed = dp(24)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val progressTouch = FrameLayout(context).apply {
        addView(progress, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        contentDescription = context.getString(R.string.tmark_audio_seek)
        isFocusable = true
    }
    private val duration = MaterialTextView(context).apply { text = "0:00"; setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyLarge) }
    private val more = mediaIconButton(context, R.drawable.tmark_audio_more, R.string.tmark_audio_more)
    private var player: MediaPlayer? = null
    private var ready = false
    private var pendingPlay = false
    private var scrubbing = false
    private var position = 0
    private var speed = 1f
    private var work: Future<*>? = null
    private var file: java.io.File? = null
    private var generation = 0
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (ready && !scrubbing) {
                updatePosition(p.currentPosition)
            }
            postDelayed(this, 500)
        }
    }
    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(play, LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        addView(elapsed, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        addView(progressTouch, LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        addView(duration, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
        addView(more, LayoutParams(dp(48), dp(48)))
        play.setOnClickListener {
            val p = player
            if (p != null && ready) {
                if (p.isPlaying) { p.pause(); updatePlayIcon(false) }
                else startPlayback(p)
            } else {
                pendingPlay = !pendingPlay
                if (work == null && p == null) prepare()
            }
        }
        progressTouch.setOnTouchListener { view, event ->
            if (!ready) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scrubbing = true
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    seekToFraction(event.x / view.width.coerceAtLeast(1))
                    true
                }
                MotionEvent.ACTION_MOVE -> { seekToFraction(event.x / view.width.coerceAtLeast(1)); true }
                MotionEvent.ACTION_UP -> {
                    seekToFraction(event.x / view.width.coerceAtLeast(1))
                    scrubbing = false
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    scrubbing = false
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
        progressTouch.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (ready) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                }
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val delta = when (action) {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -5000
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 5000
                    else -> return super.performAccessibilityAction(host, action, args)
                }
                if (!ready) return false
                seekToPosition(position + delta)
                return true
            }
        }
        more.setOnClickListener { anchor ->
            PopupMenu(context, anchor).apply {
                val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                speeds.forEachIndexed { index, value ->
                    menu.add(0, index, index, "${if (value == 1f || value == 2f) value.toInt() else value}×").isCheckable = true
                    menu.findItem(index).isChecked = speed == value
                }
                setOnMenuItemClickListener { item ->
                    speed = speeds[item.itemId]
                    player?.takeIf { ready && it.isPlaying }?.let { it.playbackParams = it.playbackParams.setSpeed(speed) }
                    true
                }
                show()
            }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun updatePlayIcon(playing: Boolean) {
        play.setImageResource(if (playing) R.drawable.tmark_audio_pause else R.drawable.tmark_audio_play)
        play.contentDescription = context.getString(if (playing) R.string.tmark_pause else R.string.tmark_play)
        progress.waveAmplitude = if (playing) dp(3) else 0
    }
    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
    private fun updatePosition(milliseconds: Int) {
        val end = player?.duration?.coerceAtLeast(0) ?: 0
        position = milliseconds.coerceIn(0, end)
        progress.setProgressCompat(if (end > 0) (position.toLong() * progress.max / end).toInt() else 0, false)
        elapsed.text = formatTime(milliseconds)
        duration.text = formatTime(end)
        progressTouch.contentDescription = context.getString(R.string.tmark_audio_seek_position, formatTime(position), formatTime(end))
    }
    private fun seekToFraction(fraction: Float) {
        val end = player?.duration ?: return
        seekToPosition((end * fraction.coerceIn(0f, 1f)).toInt())
    }
    private fun seekToPosition(milliseconds: Int) {
        val end = player?.duration ?: return
        position = milliseconds.coerceIn(0, end)
        player?.seekTo(position)
        updatePosition(position)
    }
    private fun startPlayback(p: MediaPlayer) {
        if (!isShown || windowVisibility != View.VISIBLE) return
        p.start()
        if (speed != 1f) p.playbackParams = p.playbackParams.setSpeed(speed)
        updatePlayIcon(true)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (player == null && work == null) prepare()
    }
    private fun prepare() {
        if (work != null) return
        play.isEnabled = true
        updatePlayIcon(false)
        val token = ++generation
        work = imageExecutor.submit {
            val result = runCatching {
                val bytes = loader.getContent(source)
                require(audioFormat(bytes) != null) { "Unsupported audio format" }
                writeMediaFile(context.applicationContext, bytes)
            }
            mainHandler.post {
                if (token != generation || !isAttachedToWindow) { result.getOrNull()?.delete(); return@post }
                work = null
                result.fold({ loaded -> file = loaded; startPlayer(loaded) }, ::failure)
            }
        }
    }
    private fun startPlayer(file: java.io.File) {
        try {
            val p = MediaPlayer()
            player = p
            p.apply {
                p.setDataSource(file.absolutePath)
                p.setOnPreparedListener {
                    ready = true; it.seekTo(position)
                    updatePosition(position)
                    if (pendingPlay) startPlayback(it)
                    pendingPlay = false
                    post(tick)
                }
                p.setOnCompletionListener { updatePlayIcon(false); updatePosition(it.duration) }
                p.setOnErrorListener { _, what, extra -> failure(IllegalStateException("Audio error $what/$extra")); true }
                p.prepareAsync()
            }
        } catch (e: Exception) { failure(e) }
    }
    private fun failure(error: Throwable) {
        release()
        pendingPlay = false
        play.isEnabled = false
        play.contentDescription = context.getString(R.string.tmark_media_unavailable)
        onError?.invoke(error)
    }
    private fun release() { removeCallbacks(tick); player?.release(); player = null; ready = false; scrubbing = false; file?.delete(); file = null }
    override fun onDetachedFromWindow() {
        generation++; work?.cancel(true); work = null
        if (ready) position = player?.currentPosition ?: 0
        pendingPlay = false
        release(); updatePlayIcon(false); super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE && ready) { player?.pause(); updatePlayIcon(false) }
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE && ready) { player?.pause(); updatePlayIcon(false) }
    }
}
