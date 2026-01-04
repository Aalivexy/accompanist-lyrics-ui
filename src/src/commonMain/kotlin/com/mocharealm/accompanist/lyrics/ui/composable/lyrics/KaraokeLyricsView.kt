package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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
//    TODO: pass it to dynamicFadeEdge
//    verticalFadeBrush: Brush = Brush.verticalGradient(
//        0f to Color.White.copy(0f),
//        0.05f to Color.White,
//        0.6f to Color.White,
//        1f to Color.White.copy(0f)
//    ),
    blendMode: BlendMode = BlendMode.Plus,
    useBlurEffect: Boolean = true,
    offset: Dp = 32.dp,
    showDebugRectangles: Boolean = false
) {
    val density = LocalDensity.current
    val stableNormalTextStyle = remember(normalLineTextStyle) { normalLineTextStyle }
    val stableAccompanimentTextStyle =
        remember(accompanimentLineTextStyle) { accompanimentLineTextStyle }
    val stableOffset = remember(offset) { offset }
    val stableOffsetPx =
        remember(stableOffset) { with(density) { stableOffset.toPx().fastRoundToInt() } }
    val stableBlendMode = remember(blendMode) { blendMode }

    val textMeasurer = rememberTextMeasurer()
    val layoutCache = remember { mutableStateMapOf<Int, List<SyllableLayout>>() }

    LaunchedEffect(lyrics, stableNormalTextStyle, stableAccompanimentTextStyle) {
        layoutCache.clear()
        withContext(Dispatchers.Default) {
            val normalStyle = stableNormalTextStyle.copy(textDirection = TextDirection.Content)
            val accompanimentStyle =
                stableAccompanimentTextStyle.copy(textDirection = TextDirection.Content)

            val normalSpaceWidth = textMeasurer.measure(" ", normalStyle).size.width.toFloat()
            val accompanimentSpaceWidth =
                textMeasurer.measure(" ", accompanimentStyle).size.width.toFloat()

            lyrics.lines.forEachIndexed { index, line ->
                if (!isActive) return@forEachIndexed
                if (line is KaraokeLine) {
                    val style = if (line.isAccompaniment) accompanimentStyle else normalStyle
                    val spaceWidth =
                        if (line.isAccompaniment) accompanimentSpaceWidth else normalSpaceWidth

                    val processedSyllables = if (line.alignment == KaraokeAlignment.End) {
                        line.syllables.dropLastWhile { it.content.isBlank() }
                    } else {
                        line.syllables
                    }

                    val layout = measureSyllablesAndDetermineAnimation(
                        syllables = processedSyllables,
                        textMeasurer = textMeasurer,
                        style = style,
                        isAccompanimentLine = line.isAccompaniment,
                        spaceWidth = spaceWidth
                    )

                    withContext(Dispatchers.Main) {
                        layoutCache[index] = layout
                    }
                }
            }
        }
    }

    val currentTimeMs: () -> Int = currentPosition

    val timeProvider = remember { currentPosition }

    val firstFocusedLineIndex by remember(lyrics.lines) {
        derivedStateOf {
            val rawIndex = lyrics.getCurrentFirstHighlightLineIndexByTime(currentTimeMs())
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

    val firstLine = lyrics.lines.firstOrNull()

    val haveDotsIntro by remember(firstLine) {
        derivedStateOf {
            if (firstLine == null) false
            else (firstLine.start > 5000)
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

    LaunchedEffect(
        firstFocusedLineIndex
    ) {
        if (!listState.isScrollInProgress) {
            val items = listState.layoutInfo.visibleItemsInfo
            val targetItem = items.firstOrNull { it.index == firstFocusedLineIndex }
            val scrollOffset =
                (targetItem?.offset?.minus(listState.layoutInfo.viewportStartOffset + stableOffsetPx))?.toFloat()
            try {
                if (scrollOffset != null) {
                    listState.animateScrollBy(scrollOffset, tween(600, 0, EaseIn))
                } else {
                    listState.animateScrollToItem(firstFocusedLineIndex, stableOffsetPx)
                }
            } catch (_: Exception) {
            }
        }
    }

    Crossfade(lyrics) { lyrics ->
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = stableOffset)
        ) {
            itemsIndexed(
                items = lyrics.lines,
                key = { index, line -> "${line.start}-${line.end}-$index" }
            ) { index, line ->
                val isCurrentFocusLine by rememberUpdatedState(index in allFocusedLineIndex)

                Column(modifier = Modifier.fillMaxWidth().dynamicFadingEdge(listState, index)) {
                    val animDuration = 600

                    val previousLine = lyrics.lines.getOrNull(index - 1)

                    val showDotsInterlude by remember(line, previousLine) {
                        derivedStateOf {
                            val currentTime = currentPosition()
                            (previousLine != null && (line.start - previousLine.end > 5000) && (currentTime in previousLine.end..line.start))
                        }
                    }
                    val showDotsIntro by remember(firstLine) {
                        derivedStateOf {
                            haveDotsIntro && (currentTimeMs() in 0 until firstLine!!.start) && index == 0
                        }
                    }

                    AnimatedVisibility(showDotsInterlude || showDotsIntro) {
                        KaraokeBreathingDots(
                            alignment = when (val line = previousLine ?: firstLine) {
                                is KaraokeLine -> line.alignment
                                is SyncedLine -> if (line.content.isRtl()) KaraokeAlignment.End else KaraokeAlignment.Start
                                else -> KaraokeAlignment.Start
                            },
                            startTimeMs = previousLine?.end ?: 0,
                            endTimeMs = if (showDotsIntro) firstLine!!.start else line.start,
                            currentTimeProvider = timeProvider,
                            defaults = breathingDotsDefaults,
                            modifier = Modifier.padding(vertical = 12.dp)
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
                                    blendMode = stableBlendMode,
                                    showDebugRectangles = showDebugRectangles,
                                    precalculatedLayouts = layoutCache[index]
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
                                        blendMode = stableBlendMode,
                                        precalculatedLayouts = layoutCache[index]
                                    )
                                }
                            }
                        }

                        is SyncedLine -> {
                            SyncedLineItem(
                                line = line,
                                isFocused = isCurrentFocusLine,
                                onLineClicked = onLineClicked,
                                onLinePressed = onLinePressed,
                                textStyle = stableNormalTextStyle.copy(lineHeight = 1.2.em),
                                textColor = textColor,
                                blendMode = stableBlendMode
                            )
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