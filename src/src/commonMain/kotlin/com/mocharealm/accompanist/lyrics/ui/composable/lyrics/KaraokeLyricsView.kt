package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.utils.isRtl
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun KaraokeLyricsView(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: Long,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
    normalLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    textColor: Color = Color.White,
    verticalFadeMask: Modifier = Modifier.drawWithCache {
        onDrawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(0f),
                    0.1f to Color.White,
                    0.5f to Color.White,
                    1f to Color.White.copy(0f)
                ),
                blendMode = BlendMode.DstIn
            )
        }
    },
    breathingDotsDefaults: KaraokeBreathingDotsDefaults = KaraokeBreathingDotsDefaults(),
    blendMode: BlendMode = BlendMode.Plus,
    useBlurEffect: Boolean = true,
    showDebugRectangles: Boolean = false
) {
    val currentTimeMs by rememberUpdatedState(currentPosition.toInt())

    val rawFirstFocusedLineIndex = lyrics.getCurrentFirstHighlightLineIndexByTime(currentTimeMs)

    val finalFirstFocusedLineIndex by remember(currentTimeMs, lyrics.lines) {
        derivedStateOf {
            val line = lyrics.lines.getOrNull(rawFirstFocusedLineIndex) as? KaraokeLine
            if (line != null && line.isAccompaniment) {
                var newIndex = rawFirstFocusedLineIndex
                for (i in rawFirstFocusedLineIndex downTo 0) {
                    if (!(lyrics.lines[i] as KaraokeLine).isAccompaniment) {
                        newIndex = i
                        break
                    }
                }
                newIndex
            } else {
                rawFirstFocusedLineIndex
            }
        }
    }

    val isDuoView by remember {
        derivedStateOf {
            var hasStart = false
            var hasEnd = false
            if (lyrics.lines.isEmpty()) return@derivedStateOf false
            for (line in lyrics.lines) {
                if (line is KaraokeLine) {
                    when (line.alignment) {
                        KaraokeAlignment.Start -> hasStart = true
                        KaraokeAlignment.End -> hasEnd = true
                        else -> {}
                    }
                }
                if (hasStart && hasEnd) break
            }
            hasStart && hasEnd
        }
    }

    var isScrollProgrammatically by remember { mutableStateOf(false) }

    val isUserInteracting by remember {
        derivedStateOf { listState.isScrollInProgress && !isScrollProgrammatically }
    }

    val headerItemCount = 1
    val density = LocalDensity.current
    val showDotInPause by remember(lyrics.lines, currentTimeMs) {
        derivedStateOf {
            lyrics.lines.indices.any { index ->
                val line = lyrics.lines[index]
                val previousLine = lyrics.lines.getOrNull(index - 1)
                previousLine != null &&
                        (line.start - previousLine.end > 5000) &&
                        (currentTimeMs in previousLine.end..line.start)
            }
        }
    }
    val firstLine = lyrics.lines.firstOrNull() ?: SyncedLine("", null, 0, 0)
    val showDotInIntro = remember(firstLine, currentTimeMs) {
        (firstLine.start > 5000) && (currentTimeMs in 0 until firstLine.start)
    }
    val allFocusedLineIndex by rememberUpdatedState(
        lyrics.getCurrentAllHighlightLineIndicesByTime(
            currentTimeMs
        )
    )
    val accompanimentVisibilityRanges = remember(lyrics.lines) {
        val map = mutableMapOf<Int, LongRange>()
        val mainLines = lyrics.lines.filter { it !is KaraokeLine || !it.isAccompaniment }
        if (mainLines.isNotEmpty()) {
            lyrics.lines.forEachIndexed { index, line ->
                if (line is KaraokeLine && line.isAccompaniment) {
                    val entryAnchor =
                        mainLines.findLast { it.start <= line.start } ?: mainLines.firstOrNull()
                    val exitAnchor =
                        mainLines.firstOrNull { it.end >= line.end } ?: mainLines.lastOrNull()
                    val visualStartTime = (entryAnchor?.start ?: line.start) - 600L
                    val visualEndTime = (exitAnchor?.end ?: line.end) + 600L
                    map[index] = visualStartTime..visualEndTime
                }
            }
        }
        map
    }
    val visibleAccompanimentIndices by remember(lyrics.lines, currentTimeMs) {
        derivedStateOf {
            accompanimentVisibilityRanges.filter { (_, range) ->
                currentTimeMs in range
            }.keys
        }
    }
    LaunchedEffect(finalFirstFocusedLineIndex, showDotInIntro, showDotInPause, visibleAccompanimentIndices) {
        if (isUserInteracting) {
            return@LaunchedEffect
        }

        if (showDotInIntro) {
            isScrollProgrammatically = true
            try {
                listState.animateScrollToItem(index = 0, scrollOffset = 0)
            } catch (_: Exception) {
            } finally {
                isScrollProgrammatically = false
            }
            return@LaunchedEffect
        }
        
        // 如果显示行间圆点，不进行滚动
        if (showDotInPause) {
            return@LaunchedEffect
        }
        
        // 目标索引 = 歌词索引 + Header数量
        val targetListIndex = finalFirstFocusedLineIndex + headerItemCount

        if (finalFirstFocusedLineIndex < 0 ||
            finalFirstFocusedLineIndex >= lyrics.lines.size
        ) {
            return@LaunchedEffect
        }

        isScrollProgrammatically = true
        try {
            val layoutInfo = listState.layoutInfo
            val beforeContentPadding = layoutInfo.beforeContentPadding
            val targetVisualY =
                (-beforeContentPadding.toFloat()) + with(density) { 46.dp.toPx() }

            val visibleItems = layoutInfo.visibleItemsInfo
            val targetItem = visibleItems.find { it.index == targetListIndex }

            if (targetItem != null) {
                val currentOffset = targetItem.offset

                val delta = currentOffset - targetVisualY

                if (abs(delta) > 5f) {
                    listState.animateScrollBy(
                        value = delta,
                        animationSpec = tween(600, easing = EaseInOutCubic)
                    )
                }
            } else {

                val snapOffset = -10

                listState.scrollToItem(
                    index = targetListIndex,
                    scrollOffset = snapOffset
                )
            }
        } catch (_: Exception) {
        } finally {
            isScrollProgrammatically = false
        }
    }

    Crossfade(lyrics) { lyrics ->
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize()
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .then(verticalFadeMask),
            contentPadding = PaddingValues(vertical = 300.dp)
        ) {
            // Header Item (Index 0)
            item(key = "intro-dots") {
                if (showDotInIntro) {
                    KaraokeBreathingDots(
                        alignment = (firstLine as? KaraokeLine)?.alignment
                            ?: KaraokeAlignment.Start,
                        startTimeMs = 0,
                        endTimeMs = firstLine.start,
                        currentTimeMs = currentTimeMs,
                        defaults = breathingDotsDefaults
                    )
                }
            }

            itemsIndexed(
                items = lyrics.lines,
                key = { index, line -> "${line.start}-${line.end}-$index" }
            ) { index, line ->
                val isCurrentFocusLine by rememberUpdatedState(index in allFocusedLineIndex)

                val positionToFocusedLine = remember(index, allFocusedLineIndex) {
                    val pos = if (isCurrentFocusLine) 0
                    else if (allFocusedLineIndex.isNotEmpty() && index > allFocusedLineIndex.last()) index - allFocusedLineIndex.last()
                    else if (allFocusedLineIndex.isNotEmpty() && index < allFocusedLineIndex.first()) allFocusedLineIndex.first() - index
                    else 2
                    pos.toFloat()
                }

                val blurRadius by if (useBlurEffect) {
                    animateDpAsState(
                        targetValue = if (isUserInteracting) 0.dp else positionToFocusedLine.coerceAtMost(
                            10f
                        ).dp,
                        label = "blur"
                    )
                } else derivedStateOf { 0.dp }

                Column(modifier = Modifier.fillMaxWidth()) {
                    val animDuration = 600

                    val previousLine = lyrics.lines.getOrNull(index - 1)
                    val showDotInPause = remember(line, previousLine, currentTimeMs) {
                        previousLine != null && (line.start - previousLine.end > 5000) && (currentTimeMs in previousLine.end..line.start)
                    }
                    AnimatedVisibility(showDotInPause) {
                        KaraokeBreathingDots(
                            alignment = (line as? KaraokeLine)?.alignment
                                ?: KaraokeAlignment.Start,
                            startTimeMs = previousLine!!.end,
                            endTimeMs = line.start,
                            currentTimeMs = currentTimeMs,
                            defaults = breathingDotsDefaults,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    when (line) {
                        is KaraokeLine -> {
                            val isLineRtl =
                                remember(line.syllables) { line.syllables.any { it.content.isRtl() } }
                            val isVisualRightAligned = remember(line.alignment, isLineRtl) {
                                when (line.alignment) {
                                    KaraokeAlignment.Start, KaraokeAlignment.Unspecified -> isLineRtl
                                    KaraokeAlignment.End -> !isLineRtl
                                }
                            }
                            val layoutModifier = Modifier
                                .fillMaxWidth()
                                .wrapContentWidth(if (isVisualRightAligned) Alignment.End else Alignment.Start)
                                .fillMaxWidth(if (isDuoView) 0.85f else 1f)
                                .blur(blurRadius, BlurredEdgeTreatment.Unbounded)

                            if (!line.isAccompaniment) {
                                KaraokeLineText(
                                    line = line,
                                    onLineClicked = onLineClicked,
                                    onLinePressed = onLinePressed,
                                    currentTimeMs = currentTimeMs,
                                    modifier = layoutModifier,
                                    normalLineTextStyle = normalLineTextStyle,
                                    accompanimentLineTextStyle = accompanimentLineTextStyle,
                                    activeColor = textColor,
                                    blendMode = blendMode,
                                    showDebugRectangles = showDebugRectangles
                                )
                            } else {
                                val visibilityRange = accompanimentVisibilityRanges[index]
                                val isAccompanimentVisible = if (visibilityRange != null) {
                                    currentTimeMs in visibilityRange
                                } else {
                                    currentTimeMs >= (line.start - animDuration) && currentTimeMs <= (line.end + animDuration)
                                }

                                AnimatedVisibility(
                                    visible = isAccompanimentVisible,
                                    enter = scaleIn(
                                        tween(animDuration),
                                        transformOrigin = TransformOrigin(
                                            if (isVisualRightAligned) 1f else 0f,
                                            0f
                                        )
                                    ) +
                                            fadeIn(tween(animDuration)) +
                                            slideInVertically(tween(animDuration)) +
                                            expandVertically(tween(animDuration)),
                                    exit = scaleOut(
                                        tween(animDuration),
                                        transformOrigin = TransformOrigin(
                                            if (isVisualRightAligned) 1f else 0f,
                                            0f
                                        )
                                    ) +
                                            fadeOut(tween(animDuration)) +
                                            slideOutVertically(tween(animDuration)) +
                                            shrinkVertically(tween(animDuration)),
                                ) {
                                    KaraokeLineText(
                                        line = line,
                                        onLineClicked = onLineClicked,
                                        onLinePressed = onLinePressed,
                                        currentTimeMs = currentTimeMs,
                                        modifier = layoutModifier.alpha(0.8f),
                                        normalLineTextStyle = normalLineTextStyle,
                                        accompanimentLineTextStyle = accompanimentLineTextStyle,
                                        activeColor = textColor,
                                        blendMode = blendMode
                                    )
                                }
                            }
                        }

                        is SyncedLine -> {
                            val scale by animateFloatAsState(
                                targetValue = if (isCurrentFocusLine) 1f else 0.98f, animationSpec = if (isCurrentFocusLine) {
                                    tween(durationMillis = 600, easing = LinearOutSlowInEasing)
                                } else {
                                    tween(durationMillis = 300, easing = EaseInOut)
                                }, label = "scale"
                            )
                            val alpha by animateFloatAsState(
                                targetValue = if (isCurrentFocusLine) 1f else 0.4f ,
                                label = "alpha"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .blur(blurRadius, BlurredEdgeTreatment.Unbounded)
                                    .clip(ContinuousRoundedRectangle(8.dp))
                                    .combinedClickable(
                                        onClick = { onLineClicked(line) },
                                        onLongClick = { onLinePressed(line) })
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        this.alpha = alpha
                                        transformOrigin = TransformOrigin(0f, 1f)
                                        this.blendMode = blendMode
                                        compositingStrategy = CompositingStrategy.Offscreen
                                    }
                            ) {
                                Column(
                                    Modifier.align(Alignment.TopStart)
                                        .padding(vertical = 8.dp, horizontal = 16.dp)
                                ) {
                                    Text(
                                        line.content,
                                        style = normalLineTextStyle.copy(lineHeight = 1.2f.em),
                                        color = textColor
                                    )
                                    line.translation?.let {
                                        Text(
                                            it,
                                            color = textColor.copy(0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item("BottomSpacing") {
                Spacer(
                    modifier = Modifier.fillMaxWidth()
                        .height(2000.dp)
                )
            }
        }
    }
}