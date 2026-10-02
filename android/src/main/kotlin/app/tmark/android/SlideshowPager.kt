package app.tmark.android

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

@Composable
internal fun SlideshowPager(
    count: Int,
    initialItem: Int,
    onItemChange: (Int) -> Unit,
    tile: (Int) -> View,
) {
    val cycles = if (count > 1) (Int.MAX_VALUE / count).coerceAtMost(1001) else 1
    val virtualCount = count * cycles
    val middle = (cycles / 2) * count
    val pagerState = rememberPagerState(initialPage = middle + initialItem) { virtualCount }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { page ->
            val index = page % count
            onItemChange(index)
            if (count > 1 && (page < count * 2 || page >= virtualCount - count * 2))
                pagerState.scrollToPage(middle + index)
        }
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = count > 1,
        ) { page ->
            AndroidView(
                factory = { tile(page % count) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (count > 10) {
                BasicText("${pagerState.settledPage % count + 1}/$count", style = TextStyle(color = Color.White, fontSize = 12.sp))
            } else {
                repeat(count) { index ->
                    Box(Modifier.size(6.dp).background(
                        if (index == pagerState.settledPage % count) Color.White else Color.White.copy(alpha = 0.55f),
                        CircleShape,
                    ))
                }
            }
        }
    }
}

/** Sizes the pager from the tallest image at the available width, capped at 420 dp. */
internal class SlideshowSizeFrame(
    context: android.content.Context,
    private val imageSources: List<String?>,
    private val loader: MediaContentLoader,
) : FrameLayout(context) {
    private val ratios = DoubleArray(imageSources.size) { if (imageSources[it] == null) 16.0 / 9 else 0.0 }
    private var work: Future<*>? = null
    private val handler = Handler(Looper.getMainLooper())

    fun setImageSize(index: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || index !in ratios.indices) return
        val ratio = width.toDouble() / height
        if (ratios[index] != ratio) {
            ratios[index] = ratio
            requestLayout()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (work != null || imageSources.none { it != null }) return
        work = slideshowSizeExecutor.submit {
            imageSources.forEachIndexed { index, source ->
                if (Thread.currentThread().isInterrupted) return@submit
                if (source == null || ratios[index] > 0.0) return@forEachIndexed
                val dimensions = runCatching { imageDimensions(loader.getContent(source)) }.getOrNull()
                if (dimensions != null) handler.post {
                    if (isAttachedToWindow) setImageSize(index, dimensions.first, dimensions.second)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        work?.cancel(true)
        work = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val fallback = 16.0 / 9
        val height = ratios.maxOfOrNull { ratio -> (width / (if (ratio > 0.0) ratio else fallback)).roundToInt() }
            ?: (width / fallback).roundToInt()
        val limit = (420 * resources.displayMetrics.density).roundToInt()
        val measuredHeight = height.coerceIn(1, limit)
        if (isAttachedToWindow) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(measuredHeight, View.MeasureSpec.EXACTLY))
        } else {
            setMeasuredDimension(View.resolveSize(width, widthMeasureSpec), measuredHeight)
        }
    }
}

private val slideshowSizeExecutor = Executors.newFixedThreadPool(2)

private fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (imageFormat(bytes) == ImageFormat.LOTTIE) {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        return json.optInt("w") to json.optInt("h")
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
}

/** Supplies the owners Compose needs when TmarkView is hosted by a plain Activity. */
internal class CarouselViewTreeOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
