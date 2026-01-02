package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
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
import androidx.compose.ui.util.fastRoundToInt
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.utils.isRtl
import com.mocharealm.accompanist.lyrics.ui.utils.modifier.dynamicFadingEdge
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun KaraokeLyricsView(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
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
    breathingDotsDefaults: KaraokeBreathingDotsDefaults = KaraokeBreathingDotsDefaults(),
    blendMode: BlendMode = BlendMode.Plus,
    useBlurEffect: Boolean = true,
    showDebugRectangles: Boolean = false
) {
    // 1. 稳定 TextStyle 参数 (防止父组件重组导致此处无限刷新)
    val stableNormalTextStyle = remember(normalLineTextStyle) { normalLineTextStyle }
    val stableAccompanimentTextStyle =
        remember(accompanimentLineTextStyle) { accompanimentLineTextStyle }

    val currentTimeMs: () -> Int = currentPosition

    val timeProvider = remember { currentPosition }

    val rawFirstFocusedLineIndexState = remember(lyrics) {
        derivedStateOf { lyrics.getCurrentFirstHighlightLineIndexByTime(currentTimeMs()) }
    }

    val finalFirstFocusedLineIndex by remember(lyrics.lines) {
        derivedStateOf {
            val rawIndex = rawFirstFocusedLineIndexState.value
            val line = lyrics.lines.getOrNull(rawIndex) as? KaraokeLine
            if (line != null && line.isAccompaniment) {
                var newIndex = rawIndex
                for (i in rawIndex downTo 0) {
                    if (!(lyrics.lines[i] as KaraokeLine).isAccompaniment) {
                        newIndex = i
                        break
                    }
                }
                newIndex
            } else {
                rawIndex
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
    val showDotInPause by remember(lyrics.lines) {
        derivedStateOf {
            val currentTime = currentTimeMs()
            lyrics.lines.indices.any { index ->
                val line = lyrics.lines[index]
                val previousLine = lyrics.lines.getOrNull(index - 1)
                previousLine != null && (line.start - previousLine.end > 5000) && (currentTime in previousLine.end..line.start)
            }
        }
    }
    val firstLine = lyrics.lines.firstOrNull() ?: SyncedLine("", null, 0, 0)
    val showDotInIntro by remember(firstLine) {
        derivedStateOf {
            (firstLine.start > 5000) && (currentTimeMs() in 0 until firstLine.start)
        }
    }
    val allFocusedLineIndex by remember(lyrics) {
        derivedStateOf {
            lyrics.getCurrentAllHighlightLineIndicesByTime(currentTimeMs())
        }
    }
    val accompanimentVisibilityRanges = remember(lyrics.lines) {
        val map = mutableMapOf<Int, IntRange>()
        val mainLines = lyrics.lines.filter { it !is KaraokeLine || !it.isAccompaniment }
        if (mainLines.isNotEmpty()) {
            lyrics.lines.forEachIndexed { index, line ->
                if (line is KaraokeLine && line.isAccompaniment) {
                    val entryAnchor =
                        mainLines.findLast { it.start <= line.start } ?: mainLines.firstOrNull()
                    val exitAnchor =
                        mainLines.firstOrNull { it.end >= line.end } ?: mainLines.lastOrNull()
                    val visualStartTime = (entryAnchor?.start ?: line.start) - 600
                    val visualEndTime = (exitAnchor?.end ?: line.end) + 600
                    map[index] = visualStartTime..visualEndTime
                }
            }
        }
        map
    }
    LaunchedEffect(showDotInIntro) {
        if (showDotInIntro) {
            if (!listState.isScrollInProgress) {
                val layoutInfo = listState.layoutInfo
                val beforeContentPadding = layoutInfo.beforeContentPadding
                val targetVisualY = (-beforeContentPadding.toFloat()) + with(density) { 46.dp.toPx() }
                listState.animateScrollToItem(0,targetVisualY.fastRoundToInt())
            }
        }
    }

    LaunchedEffect(
        finalFirstFocusedLineIndex, showDotInPause
    ) {
        if (isUserInteracting) return@LaunchedEffect
        if (showDotInIntro) return@LaunchedEffect
        if (showDotInPause) return@LaunchedEffect

        val targetListIndex = finalFirstFocusedLineIndex + headerItemCount

        if (finalFirstFocusedLineIndex < 0 || finalFirstFocusedLineIndex >= lyrics.lines.size) {
            return@LaunchedEffect
        }

        isScrollProgrammatically = true
        try {
            val layoutInfo = listState.layoutInfo
            val beforeContentPadding = layoutInfo.beforeContentPadding
            val targetVisualY = (-beforeContentPadding.toFloat()) + with(density) { 46.dp.toPx() }

            val visibleItems = layoutInfo.visibleItemsInfo
            val targetItem = visibleItems.find { it.index == targetListIndex }

            if (targetItem != null) {
                val currentOffset = targetItem.offset

                val delta = currentOffset - targetVisualY

                if (abs(delta) > 5f) {
                    listState.animateScrollBy(
                        value = delta, animationSpec = tween(600, easing = EaseInOutCubic)
                    )
                }
            } else {

                val snapOffset = -10

                listState.scrollToItem(
                    index = targetListIndex, scrollOffset = snapOffset
                )
            }
        } catch (_: Exception) {
        } finally {
            isScrollProgrammatically = false
        }
    }

    Crossfade(lyrics) { lyrics ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 300.dp)
            ) {
                item(key = "intro-dots") {
                    AnimatedVisibility(
                        visible = showDotInIntro,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        KaraokeBreathingDots(
                            alignment = (firstLine as? KaraokeLine)?.alignment
                                ?: KaraokeAlignment.Start,
                            startTimeMs = 0,
                            endTimeMs = firstLine.start,
                            currentTimeProvider = timeProvider,
                            defaults = breathingDotsDefaults
                        )
                    }
                }

                itemsIndexed(
                    items = lyrics.lines,
                    key = { index, line -> "${line.start}-${line.end}-$index" }
                ) { index, line ->
                    val isCurrentFocusLine by rememberUpdatedState(index in allFocusedLineIndex)

//                val positionToFocusedLine = remember(index, allFocusedLineIndex) {
//                    val pos = if (isCurrentFocusLine) 0
//                    else if (allFocusedLineIndex.isNotEmpty() && index > allFocusedLineIndex.last()) index - allFocusedLineIndex.last()
//                    else if (allFocusedLineIndex.isNotEmpty() && index < allFocusedLineIndex.first()) allFocusedLineIndex.first() - index
//                    else 2
//                    pos.toFloat()
//                }

//                val blurRadiusState = if (useBlurEffect) {
//                    animateDpAsState(
//                        targetValue = if (isUserInteracting) 0.dp else positionToFocusedLine.coerceAtMost(
//                            10f
//                        ).dp, label = "blur"
//                    )
//                } else remember { mutableStateOf(0.dp) }

                    val listIndex = index + headerItemCount
                    Column(modifier = Modifier.fillMaxWidth().dynamicFadingEdge(listState, listIndex)) {
                        val animDuration = 600

                        val previousLine = lyrics.lines.getOrNull(index - 1)
                        val showDotInPause by remember(line, previousLine) {
                            derivedStateOf {
                                val currentTime = currentTimeMs()
                                previousLine != null && (line.start - previousLine.end > 5000) && (currentTime in previousLine.end..line.start)
                            }
                        }
                        AnimatedVisibility(showDotInPause) {
                            KaraokeBreathingDots(
                                alignment = (line as? KaraokeLine)?.alignment
                                    ?: KaraokeAlignment.Start,
                                startTimeMs = previousLine!!.end,
                                endTimeMs = line.start,
                                currentTimeProvider = timeProvider,
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
                                val layoutModifier = Modifier.fillMaxWidth()
                                    .wrapContentWidth(if (isVisualRightAligned) Alignment.End else Alignment.Start)
                                    .fillMaxWidth(if (isDuoView) 0.85f else 1f)
//                                .graphicsLayer {
//                                   renderEffect = createBlurEffect(
//                                            blurRadius.toPx(), blurRadius.toPx(), android.graphics.Shader.TileMode.DECAL
//                                        ).asComposeRenderEffect()
//                                }

                                if (!line.isAccompaniment) {
                                    KaraokeLineText(
                                        line = line,
                                        onLineClicked = onLineClicked,
                                        onLinePressed = onLinePressed,
                                        currentTimeProvider = timeProvider,
                                        isFocused = isCurrentFocusLine,
                                        modifier = layoutModifier,
                                        normalLineTextStyle = stableNormalTextStyle,
                                        accompanimentLineTextStyle = stableAccompanimentTextStyle,
                                        activeColor = textColor,
                                        blendMode = blendMode,
                                        showDebugRectangles = showDebugRectangles
                                    )
                                } else {
                                    val visibilityRange = accompanimentVisibilityRanges[index]
                                    val isAccompanimentVisible by remember(visibilityRange) {
                                        derivedStateOf {
                                            val currentTime = currentTimeMs()
                                            if (visibilityRange != null) {
                                                currentTime in visibilityRange
                                            } else {
                                                currentTime >= (line.start - animDuration) && currentTime <= (line.end + animDuration)
                                            }
                                        }
                                    }

                                    AnimatedVisibility(
                                        visible = isAccompanimentVisible,
                                        enter = scaleIn(
                                            tween(animDuration), transformOrigin = TransformOrigin(
                                                if (isVisualRightAligned) 1f else 0f, 0f
                                            )
                                        ) + fadeIn(tween(animDuration)) + slideInVertically(
                                            tween(
                                                animDuration
                                            )
                                        ) + expandVertically(tween(animDuration)),
                                        exit = scaleOut(
                                            tween(animDuration), transformOrigin = TransformOrigin(
                                                if (isVisualRightAligned) 1f else 0f, 0f
                                            )
                                        ) + fadeOut(tween(animDuration)) + slideOutVertically(
                                            tween(
                                                animDuration
                                            )
                                        ) + shrinkVertically(tween(animDuration)),
                                    ) {
                                        KaraokeLineText(
                                            line = line,
                                            onLineClicked = onLineClicked,
                                            onLinePressed = onLinePressed,
                                            currentTimeProvider = timeProvider,
                                            isFocused = isCurrentFocusLine,
                                            modifier = layoutModifier.alpha(0.8f),
                                            normalLineTextStyle = stableNormalTextStyle,
                                            accompanimentLineTextStyle = stableAccompanimentTextStyle,
                                            activeColor = textColor,
                                            blendMode = blendMode
                                        )
                                    }
                                }
                            }

                            is SyncedLine -> {
                                val scaleState = animateFloatAsState(
                                    targetValue = if (isCurrentFocusLine) 1f else 0.98f,
                                    animationSpec = if (isCurrentFocusLine) {
                                        tween(durationMillis = 600, easing = LinearOutSlowInEasing)
                                    } else {
                                        tween(durationMillis = 300, easing = EaseInOut)
                                    },
                                    label = "scale"
                                )
                                val alphaState = animateFloatAsState(
                                    targetValue = if (isCurrentFocusLine) 1f else 0.4f,
                                    label = "alpha"
                                )

                                Box(
                                    modifier = Modifier.fillMaxWidth().graphicsLayer {
//                                        val radius = blurRadiusState.value.toPx()
//                                        if (radius > 0f) {
//                                            createBlurEffect(
//                                                radius,
//                                                radius,
//                                                TileMode.Decal
//                                            )?.let { platformRenderEffect ->
//                                                convertToComposeRenderEffect(platformRenderEffect)?.let { renderEffect ->
//                                                    this.renderEffect = renderEffect
//                                                }
//                                            }
//                                        } else {
//                                            renderEffect = null
//                                        }

                                        scaleX = scaleState.value
                                        scaleY = scaleState.value
                                        alpha = alphaState.value
                                        transformOrigin = TransformOrigin(0f, 1f)
                                        this.blendMode = blendMode
                                        compositingStrategy = CompositingStrategy.Offscreen
                                    }.clip(ContinuousRoundedRectangle(8.dp)).combinedClickable(
                                        onClick = { onLineClicked(line) },
                                        onLongClick = { onLinePressed(line) })
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
                                                it, color = textColor.copy(0.6f)
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
                        modifier = Modifier.fillMaxWidth().height(2000.dp)
                    )
                }
            }
        }
    }
}