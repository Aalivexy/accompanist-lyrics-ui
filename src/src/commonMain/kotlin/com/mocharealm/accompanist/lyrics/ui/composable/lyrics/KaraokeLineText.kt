package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.ui.utils.easing.Bounce
import com.mocharealm.accompanist.lyrics.ui.utils.easing.DipAndRise
import com.mocharealm.accompanist.lyrics.ui.utils.easing.Swell
import com.mocharealm.accompanist.lyrics.ui.utils.isArabic
import com.mocharealm.accompanist.lyrics.ui.utils.isDevanagari
import com.mocharealm.accompanist.lyrics.ui.utils.isPunctuation
import com.mocharealm.accompanist.lyrics.ui.utils.isPureCjk
import com.mocharealm.accompanist.lyrics.ui.utils.isRtl
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * All pre-calculated parameters required for word-level "awesome" animation.
 */
data class WordAnimationInfo(
    val wordStartTime: Long,
    val wordEndTime: Long,
    val wordContent: String,
    val wordDuration: Long = wordEndTime - wordStartTime
)


data class WrappedLine(
    val syllables: List<SyllableLayout>, val totalWidth: Float
)

private fun String.shouldUseSimpleAnimation(): Boolean {
    val cleanedStr = this.filter { !it.isWhitespace() && !it.toString().isPunctuation() }
    if (cleanedStr.isEmpty()) return false

    // If the string is purely CJK, or contains any Arabic or Devanagari characters
    return cleanedStr.isPureCjk() || cleanedStr.any { it.isArabic() || it.isDevanagari() }
}

private fun groupIntoWords(syllables: List<KaraokeSyllable>): List<List<KaraokeSyllable>> {
    if (syllables.isEmpty()) return emptyList()
    val words = mutableListOf<List<KaraokeSyllable>>()
    var currentWord = mutableListOf<KaraokeSyllable>()
    syllables.forEach { syllable ->
        currentWord.add(syllable)
        if (syllable.content.trimEnd().length < syllable.content.length) {
            words.add(currentWord.toList())
            currentWord = mutableListOf()
        }
    }
    if (currentWord.isNotEmpty()) {
        words.add(currentWord.toList())
    }
    return words
}

/**
 * Measure syllables and determine animation type, directly producing "semi-finished" SyllableLayout list with measurement information.
 */
private fun measureSyllablesAndDetermineAnimation(
    syllables: List<KaraokeSyllable>,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    isAccompanimentLine: Boolean
): List<SyllableLayout> {
    val words = groupIntoWords(syllables)
    val fastCharAnimationThresholdMs = 200f

    return words.flatMapIndexed { wordIndex, word ->
        val wordContent = word.joinToString("") { it.content }
        val wordDuration = if (word.isNotEmpty()) word.last().end - word.first().start else 0
        val perCharDuration = if (wordContent.isNotEmpty() && wordDuration > 0) {
            wordDuration.toFloat() / wordContent.length
        } else {
            0f
        }

        val useAwesomeAnimation =
            perCharDuration > fastCharAnimationThresholdMs && wordDuration >= 1000
                    && !wordContent.shouldUseSimpleAnimation()
                    && !isAccompanimentLine

        word.map { syllable ->
            SyllableLayout(
                syllable = syllable,
                textLayoutResult = textMeasurer.measure(syllable.content, style),
                wordId = wordIndex,
                useAwesomeAnimation = useAwesomeAnimation
            )
        }
    }
}


private fun calculateGreedyWrappedLines(
    syllableLayouts: List<SyllableLayout>,
    availableWidthPx: Float,
    textMeasurer: TextMeasurer,
    style: TextStyle
): List<WrappedLine> {

    val lines = mutableListOf<WrappedLine>()
    val currentLine = mutableListOf<SyllableLayout>()
    var currentLineWidth = 0f

    syllableLayouts.forEach { syllableLayout ->
        if (currentLineWidth + syllableLayout.width > availableWidthPx && currentLine.isNotEmpty()) {
            val trimmedDisplayLine = trimDisplayLineTrailingSpaces(currentLine, textMeasurer, style)
            if (trimmedDisplayLine.syllables.isNotEmpty()) {
                lines.add(trimmedDisplayLine)
            }
            currentLine.clear()
            currentLineWidth = 0f
        }
        currentLine.add(syllableLayout)
        currentLineWidth += syllableLayout.width
    }

    if (currentLine.isNotEmpty()) {
        val trimmedDisplayLine = trimDisplayLineTrailingSpaces(currentLine, textMeasurer, style)
        if (trimmedDisplayLine.syllables.isNotEmpty()) {
            lines.add(trimmedDisplayLine)
        }
    }
    return lines
}

private fun calculateBalancedLines(
    syllableLayouts: List<SyllableLayout>,
    availableWidthPx: Float,
    textMeasurer: TextMeasurer,
    style: TextStyle
): List<WrappedLine> {
    if (syllableLayouts.isEmpty()) return emptyList()

    val n = syllableLayouts.size
    val costs = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
    val breaks = IntArray(n + 1)
    costs[0] = 0.0

    for (i in 1..n) {
        var currentLineWidth = 0f
        for (j in i downTo 1) {
            currentLineWidth += syllableLayouts[j - 1].width

            if (currentLineWidth > availableWidthPx) break

            val badness = (availableWidthPx - currentLineWidth).pow(2).toDouble()

            if (costs[j - 1] != Double.POSITIVE_INFINITY && costs[j - 1] + badness < costs[i]) {
                costs[i] = costs[j - 1] + badness
                breaks[i] = j - 1
            }
        }
    }

    if (costs[n] == Double.POSITIVE_INFINITY) {
        return calculateGreedyWrappedLines(syllableLayouts, availableWidthPx, textMeasurer, style)
    }

    val lines = mutableListOf<WrappedLine>()
    var currentIndex = n
    while (currentIndex > 0) {
        val startIndex = breaks[currentIndex]
        val lineSyllables = syllableLayouts.subList(startIndex, currentIndex)
        val trimmedLine = trimDisplayLineTrailingSpaces(lineSyllables, textMeasurer, style)
        lines.add(0, trimmedLine)
        currentIndex = startIndex
    }

    return lines
}

/**
 * Receive "semi-finished" SyllableLayout with measurement information, calculate final position and animation parameters,
 * output "complete" SyllableLayout list.
 */
private fun calculateStaticLineLayout(
    wrappedLines: List<WrappedLine>,
    lineAlignment: Alignment,
    canvasWidth: Float,
    lineHeight: Float,
    isRtl: Boolean
): List<List<SyllableLayout>> {
    val layoutsByWord = mutableMapOf<Int, MutableList<SyllableLayout>>()

    // Pass 1: Calculate initial position, update SyllableLayout objects with .copy(). Also group by wordId.
    val positionedLines = wrappedLines.mapIndexed { lineIndex, wrappedLine ->
        val lineY = lineIndex * lineHeight

        // Horizontal positioning logic
        val startX = when (lineAlignment) {
            Alignment.TopStart -> 0f
            Alignment.TopEnd -> canvasWidth - wrappedLine.totalWidth
            else -> (canvasWidth - wrappedLine.totalWidth) / 2f
        }

        var currentX = if (isRtl) startX + wrappedLine.totalWidth else startX

        wrappedLine.syllables.map { initialLayout ->
            val positionX = if (isRtl) {
                currentX - initialLayout.width
            } else {
                currentX
            }

            val positionedLayout = initialLayout.copy(position = Offset(positionX, lineY))
            layoutsByWord.getOrPut(positionedLayout.wordId) { mutableListOf() }
                .add(positionedLayout)

            if (isRtl) {
                currentX -= positionedLayout.width
            } else {
                currentX += positionedLayout.width
            }

            positionedLayout
        }
    }

    // Pass 2: Pre-calculate all word-level animation information.
    val animInfoByWord = mutableMapOf<Int, WordAnimationInfo>()
    val charOffsetsBySyllable = mutableMapOf<SyllableLayout, Int>()

    layoutsByWord.forEach { (wordId, layouts) ->
        if (layouts.first().useAwesomeAnimation) {
            animInfoByWord[wordId] = WordAnimationInfo(
                wordStartTime = layouts.minOf { it.syllable.start }.toLong(),
                wordEndTime = layouts.maxOf { it.syllable.end }.toLong(),
                wordContent = layouts.joinToString("") { it.syllable.content })
            var runningCharOffset = 0
            layouts.forEach { layout ->
                charOffsetsBySyllable[layout] = runningCharOffset
                runningCharOffset += layout.syllable.content.length
            }
        }
    }

    // Pass 3: Final .copy(), inject word-level animation information and focus into each SyllableLayout.
    return positionedLines.map { line ->
        line.map { positionedLayout ->
            val wordLayouts = layoutsByWord.getValue(positionedLayout.wordId)
            val minX = wordLayouts.minOf { it.position.x }
            val maxX = wordLayouts.maxOf { it.position.x + it.width }
            val bottomY = wordLayouts.maxOf { it.position.y + it.textLayoutResult.size.height }

            positionedLayout.copy(
                wordPivot = Offset(x = (minX + maxX) / 2f, y = bottomY),
                wordAnimInfo = animInfoByWord[positionedLayout.wordId],
                charOffsetInWord = charOffsetsBySyllable[positionedLayout] ?: 0
            )
        }
    }
}

private fun createLineGradientBrush(
    lineLayout: List<SyllableLayout>,
    currentTimeMs: Int,
    isRtl: Boolean
): Brush {
    val activeColor = Color.White
    val inactiveColor = Color.White.copy(alpha = 0.2f)
    val minFadeWidth = 100f

    if (lineLayout.isEmpty()) {
        return Brush.horizontalGradient(colors = listOf(inactiveColor, inactiveColor))
    }

    val totalMinX = lineLayout.minOf { it.position.x }
    val totalMaxX = lineLayout.maxOf { it.position.x + it.width }
    val totalWidth = totalMaxX - totalMinX

    if (totalWidth <= 0f) {
        val isFinished = currentTimeMs >= lineLayout.last().syllable.end
        val color = if (isFinished) activeColor else inactiveColor
        return Brush.horizontalGradient(listOf(color, color))
    }

    val firstSyllableStart = lineLayout.first().syllable.start
    val lastSyllableEnd = lineLayout.last().syllable.end

    val lineProgress = run {
        if (currentTimeMs <= firstSyllableStart) return Brush.horizontalGradient(
            listOf(inactiveColor, inactiveColor)
        )
        if (currentTimeMs >= lastSyllableEnd) return Brush.horizontalGradient(
            listOf(activeColor, activeColor)
        )

        val activeSyllableLayout = lineLayout.find {
            currentTimeMs in it.syllable.start until it.syllable.end
        }

        val currentPixelPosition = when {
            activeSyllableLayout != null -> {
                val syllableProgress = activeSyllableLayout.syllable.progress(currentTimeMs)
                if (isRtl) {
                    // RTL: Progress moves from Right (width) to Left (0) within the syllable
                    activeSyllableLayout.position.x + activeSyllableLayout.width * (1f - syllableProgress)
                } else {
                    // LTR: Progress moves from Left (0) to Right (width) within the syllable
                    activeSyllableLayout.position.x + activeSyllableLayout.width * syllableProgress
                }
            }
            else -> {
                // Determine if we are between syllables
                val lastFinished = lineLayout.lastOrNull { currentTimeMs >= it.syllable.end }
                if (isRtl) {
                    // RTL: Last finished means we passed it going left. Use its Left edge.
                    lastFinished?.position?.x ?: totalMaxX
                } else {
                    // LTR: Last finished means we passed it going right. Use its Right edge.
                    lastFinished?.let { it.position.x + it.width } ?: totalMinX
                }
            }
        }
        // Normalize position relative to the line width (0..1)
        ((currentPixelPosition - totalMinX) / totalWidth).coerceIn(0f, 1f)
    }

    val fadeRange = (minFadeWidth / totalWidth).coerceAtMost(1f)
    val fadeCenterStart = -fadeRange / 2f
    val fadeCenterEnd = 1f + fadeRange / 2f
    val fadeCenter = fadeCenterStart + (fadeCenterEnd - fadeCenterStart) * lineProgress
    val fadeStart = fadeCenter - fadeRange / 2f
    val fadeEnd = fadeCenter + fadeRange / 2f

    val colorStops = if (isRtl) {
        // RTL Gradient: Left side is Inactive (future), Right side is Active (past)
        // Order of stops is 0.0 (Left) -> 1.0 (Right)
        arrayOf(
            0.0f to inactiveColor,
            fadeStart.coerceIn(0f, 1f) to inactiveColor,
            fadeEnd.coerceIn(0f, 1f) to activeColor,
            1.0f to activeColor
        )
    } else {
        // LTR Gradient: Left side is Active (past), Right side is Inactive (future)
        arrayOf(
            0.0f to activeColor,
            fadeStart.coerceIn(0f, 1f) to activeColor,
            fadeEnd.coerceIn(0f, 1f) to inactiveColor,
            1.0f to inactiveColor
        )
    }

    return Brush.horizontalGradient(
        colorStops = colorStops,
        startX = totalMinX,
        endX = totalMaxX
    )
}


fun DrawScope.drawLine(
    lineLayouts: List<List<SyllableLayout>>,
    currentTimeMs: Int,
    color: Color,
    textMeasurer: TextMeasurer,
    blendMode: BlendMode,
    isRtl: Boolean,
    showDebugRectangles: Boolean = false
) {
    lineLayouts.forEach { rowLayouts ->
        if (rowLayouts.isEmpty()) return@forEach

        val minX = rowLayouts.minOf { it.position.x }
        val maxX = rowLayouts.maxOf { it.position.x + it.width }
        val minY = rowLayouts.minOf { it.position.y }
        val totalHeight = rowLayouts.maxOf { it.textLayoutResult.size.height }.toFloat()

        // Use calculated min/max bounds instead of first/last layout which might differ in RTL
        val verticalPadding = (totalHeight * 0.1).dp.toPx()
        val horizontalPadding = ((maxX - minX) * 0.2).dp.toPx()

        drawIntoCanvas { canvas ->
            val layerBounds = Rect(
                left = minX - horizontalPadding,
                top = minY - verticalPadding,
                right = maxX + horizontalPadding,
                bottom = minY + totalHeight + verticalPadding
            )
            canvas.saveLayer(layerBounds, Paint())

            rowLayouts.forEachIndexed { index, syllableLayout ->
                val wordAnimInfo = syllableLayout.wordAnimInfo

                if (wordAnimInfo != null) {
                    val textStyle = syllableLayout.textLayoutResult.layoutInput.style
                    val fastCharAnimationThresholdMs = 200f
                    val awesomeDuration = wordAnimInfo.wordDuration * 0.8f

                    syllableLayout.syllable.content.forEachIndexed { charIndex, char ->
                        val absoluteCharIndex = syllableLayout.charOffsetInWord + charIndex
                        val numCharsInWord = wordAnimInfo.wordContent.length
                        val earliestStartTime = wordAnimInfo.wordStartTime
                        val latestStartTime = wordAnimInfo.wordEndTime - awesomeDuration

                        val charRatio =
                            if (numCharsInWord > 1) absoluteCharIndex.toFloat() / (numCharsInWord - 1) else 0.5f
                        val awesomeStartTime =
                            (earliestStartTime + (latestStartTime - earliestStartTime) * charRatio).toLong()
                        val awesomeProgress =
                            ((currentTimeMs - awesomeStartTime).toFloat() / awesomeDuration).coerceIn(
                                0f, 1f
                            )

                        val floatOffset = 4f * DipAndRise(
                            dip = ((0.5 * (wordAnimInfo.wordDuration - fastCharAnimationThresholdMs * numCharsInWord) / 1000)).coerceIn(
                                0.0, 0.5
                            )
                        ).transform(1.0f - awesomeProgress)
                        val scale = 1f + Swell(
                            (0.1 * (wordAnimInfo.wordDuration - fastCharAnimationThresholdMs * numCharsInWord) / 1000).coerceIn(
                                0.0, 0.1
                            )
                        ).transform(awesomeProgress)
                        val yPos = syllableLayout.position.y + floatOffset
                        val xPos =
                            syllableLayout.position.x + syllableLayout.textLayoutResult.getHorizontalPosition(
                                offset = charIndex, usePrimaryDirection = true
                            )
                        val blurRadius = 10f * Bounce.transform(awesomeProgress)
                        val shadow = Shadow(
                            color = color.copy(0.4f),
                            offset = Offset(0f, 0f),
                            blurRadius = blurRadius
                        )
                        val charLayoutResult =
                            textMeasurer.measure(char.toString(), style = textStyle)

                        withTransform({ scale(scale = scale, pivot = syllableLayout.wordPivot) }) {
                            drawText(
                                textLayoutResult = charLayoutResult,
                                brush = Brush.horizontalGradient(0f to color, 1f to color),
                                topLeft = Offset(xPos, yPos),
                                shadow = shadow,
                                blendMode = blendMode
                            )
                            if (showDebugRectangles) {
                                drawRect(
                                    color = Color.Red, topLeft = Offset(xPos, yPos), size = Size(
                                        charLayoutResult.size.width.toFloat(),
                                        charLayoutResult.size.height.toFloat()
                                    ), style = Stroke(2f)
                                )
                            }
                        }
                    }
                } else {
                    // For punctuation, find the previous non-punctuation syllable to calculate rise animation
                    val progressSyllable =
                        if (syllableLayout.syllable.content.trim().isPunctuation()) {
                            // Search forward (or backward depending on visual order?)
                            // Logic here uses list index, which is layout order.
                            // In RTL, rowLayouts is ordered Right-to-Left visually?
                            // No, rowLayouts comes from calculateStaticLineLayout which iterates syllables in Logical Time Order.
                            // So 'index' is consistent with Time.
                            var searchIndex = index - 1
                            while (searchIndex >= 0) {
                                val candidateSyllable = rowLayouts[searchIndex]
                                if (!candidateSyllable.syllable.content.trim().isPunctuation()) {
                                    candidateSyllable
                                    break
                                }
                                searchIndex--
                            }
                            // If no non-punctuation syllable is found, use current syllable
                            if (searchIndex < 0) syllableLayout else rowLayouts[searchIndex]
                        } else {
                            syllableLayout
                        }
                    val timeSinceStart = currentTimeMs - progressSyllable.syllable.start
                    val animationProgress =
                        (timeSinceStart / (700f * progressSyllable.syllable.duration / 500f))
                            .coerceIn(0f, 1f)
                    val floatOffset = 4f * CubicBezierEasing(
                        0.6f, 0f, 0.2f, 1f
                    ).transform(1f - animationProgress)
                    val finalPosition =
                        syllableLayout.position.copy(y = syllableLayout.position.y + floatOffset)
                    drawText(
                        textLayoutResult = syllableLayout.textLayoutResult,
                        brush = Brush.horizontalGradient(0f to color, 1f to color),
                        topLeft = finalPosition,
                        blendMode = blendMode
                    )
                    if (showDebugRectangles) {
                        drawRect(
                            color = Color.Red, topLeft = finalPosition, size = Size(
                                syllableLayout.textLayoutResult.size.width.toFloat(),
                                syllableLayout.textLayoutResult.size.height.toFloat()
                            ), style = Stroke(2f)
                        )
                    }
                }
            }

            val progressBrush = createLineGradientBrush(rowLayouts, currentTimeMs, isRtl)
            drawRect(
                brush = progressBrush,
                topLeft = layerBounds.topLeft,
                size = layerBounds.size,
                blendMode = BlendMode.DstIn
            )
            canvas.restore()
        }
    }
}

@Stable
@Composable
fun KaraokeLineText(
    line: KaraokeLine,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    currentTimeMs: Int,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    normalLineTextStyle: TextStyle,
    accompanimentLineTextStyle: TextStyle,
    blendMode: BlendMode = BlendMode.Plus
) {
    val isFocused = line.isFocused(currentTimeMs)
    val textMeasurer = rememberTextMeasurer()

    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.98f, animationSpec = if (isFocused) {
            androidx.compose.animation.core.tween(
                durationMillis = 600, easing = LinearOutSlowInEasing
            )
        } else {
            androidx.compose.animation.core.tween(
                durationMillis = 300, easing = EaseInOut
            )
        }, label = "scale"
    )
    val animatedAlpha by animateFloatAsState(
        targetValue = if (!line.isAccompaniment) if (isFocused) 1f else 0.4f else if (isFocused) 0.6f else 0.2f,
        label = "alpha"
    )

    Box(
        Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(8.dp))
            .combinedClickable(
                onClick = { onLineClicked(line) },
                onLongClick = { onLinePressed(line) })
    ) {
        Column(
            modifier.align(if (line.alignment == KaraokeAlignment.End) Alignment.TopEnd else Alignment.TopStart)
                .padding(vertical = 8.dp, horizontal = 16.dp).graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    transformOrigin = TransformOrigin(
                        if (line.alignment == KaraokeAlignment.Start) 0f else 1f, 1f
                    )
                },
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = if (line.alignment == KaraokeAlignment.Start) Alignment.Start else Alignment.End
        ) {
            BoxWithConstraints(Modifier.graphicsLayer {
                alpha = animatedAlpha
            }) {
                val density = LocalDensity.current
                val availableWidthPx = with(density) { maxWidth.toPx() }

                val textStyle = remember(line.isAccompaniment) {
                    if (line.isAccompaniment) accompanimentLineTextStyle else normalLineTextStyle
                }

                // Detect RTL
                val isRtl = remember(line.syllables) {
                    line.syllables.any { it.content.isRtl() }
                }

                // 1. Measure and produce "semi-finished" Layout objects
                val processedSyllables = remember(line.syllables, line.alignment) {
                    if (line.alignment == KaraokeAlignment.End) {
                        // Remove trailing blank syllables
                        line.syllables.dropLastWhile { it.content.isBlank() }
                    } else {
                        line.syllables
                    }
                }

                val initialLayouts by remember {
                    derivedStateOf {
                        measureSyllablesAndDetermineAnimation(
                            syllables = processedSyllables,
                            textMeasurer = textMeasurer,
                            style = textStyle,
                            isAccompanimentLine = line.isAccompaniment
                        )
                    }
                }

                // 2. Wrap Layout objects into lines
                val wrappedLines by remember {
                    derivedStateOf {
                        calculateBalancedLines(
                            syllableLayouts = initialLayouts,
                            availableWidthPx = availableWidthPx,
                            textMeasurer = textMeasurer,
                            style = textStyle
                        )
                    }
                }

                val lineHeight = remember(textStyle) {
                    textMeasurer.measure("M", textStyle).size.height.toFloat()
                }

                // 3. Calculate final position and animation parameters, get "complete" Layout objects
                val finalLineLayouts = remember(wrappedLines, availableWidthPx, lineHeight, isRtl) {
                    calculateStaticLineLayout(
                        wrappedLines = wrappedLines,
                        lineAlignment = if (line.alignment == KaraokeAlignment.End) Alignment.TopEnd else Alignment.TopStart,
                        canvasWidth = availableWidthPx,
                        lineHeight = lineHeight,
                        isRtl = isRtl
                    )
                }

                val totalHeight = remember(wrappedLines, lineHeight) {
                    lineHeight * wrappedLines.size
                }

                Canvas(modifier = Modifier.size(maxWidth, (totalHeight.roundToInt() + 8).toDp())) {
                    drawLine(
                        lineLayouts = finalLineLayouts,
                        currentTimeMs = currentTimeMs,
                        color = activeColor,
                        textMeasurer = textMeasurer,
                        blendMode = blendMode,
                        isRtl = isRtl
                    )
                }
            }

            line.translation?.let { translation ->
                Text(
                    translation, color = activeColor.copy(0.4f),
                    modifier = Modifier
                        .graphicsLayer {
                            this.blendMode = blendMode
                        },
                    textAlign = if (line.alignment == KaraokeAlignment.End) TextAlign.End else TextAlign.Start
                )
            }
        }
    }
}

private fun trimDisplayLineTrailingSpaces(
    displayLineSyllables: List<SyllableLayout>, textMeasurer: TextMeasurer, style: TextStyle
): WrappedLine {
    if (displayLineSyllables.isEmpty()) {
        return WrappedLine(emptyList(), 0f)
    }

    val processedSyllables = displayLineSyllables.toMutableList()
    var lastIndex = processedSyllables.lastIndex

    while (lastIndex >= 0 && processedSyllables[lastIndex].syllable.content.isBlank()) {
        processedSyllables.removeAt(lastIndex)
        lastIndex--
    }

    if (processedSyllables.isEmpty()) {
        return WrappedLine(emptyList(), 0f)
    }

    val lastLayout = processedSyllables.last()
    val originalContent = lastLayout.syllable.content
    val trimmedContent = originalContent.trimEnd()

    if (trimmedContent.length < originalContent.length) {
        if (trimmedContent.isNotEmpty()) {
            val trimmedLayoutResult = textMeasurer.measure(trimmedContent, style)
            val trimmedLayout = lastLayout.copy(
                syllable = lastLayout.syllable.copy(content = trimmedContent),
                textLayoutResult = trimmedLayoutResult,
                width = trimmedLayoutResult.size.width.toFloat()
            )
            processedSyllables[processedSyllables.lastIndex] = trimmedLayout
        } else {
            processedSyllables.removeAt(processedSyllables.lastIndex)
        }
    }

    val totalWidth = processedSyllables.sumOf { it.width.toDouble() }.toFloat()
    return WrappedLine(processedSyllables, totalWidth)
}

@Composable
private fun Int.toDp(): Dp = with(LocalDensity.current) { this@toDp.toDp() }

@Composable
private fun IntSize.toDpSize(): DpSize =
    with(LocalDensity.current) { DpSize(width.toDp(), height.toDp()) }