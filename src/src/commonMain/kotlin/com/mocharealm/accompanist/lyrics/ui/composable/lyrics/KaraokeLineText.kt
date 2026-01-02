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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.ui.utils.LayerPaint
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

data class SyllableLayout(
    val syllable: KaraokeSyllable,
    val textLayoutResult: TextLayoutResult,
    val wordId: Int,
    val useAwesomeAnimation: Boolean,
    val width: Float = textLayoutResult.size.width.toFloat(), // 允许覆盖宽度
    val position: Offset = Offset.Zero,
    val wordPivot: Offset = Offset.Zero,
    val wordAnimInfo: WordAnimationInfo? = null,
    val charOffsetInWord: Int = 0,
    val charLayouts: List<TextLayoutResult>? = null, // 新增：缓存每个字符的测量结果
    val charOriginalBounds: List<Rect>? = null       // 新增：缓存每个字符的原始位置
)

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

private fun measureSyllablesAndDetermineAnimation(
    syllables: List<KaraokeSyllable>,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    isAccompanimentLine: Boolean,
    spaceWidth: Float
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
            val layoutResult = textMeasurer.measure(syllable.content, style)

            // --- Fix: 修正尾部空格宽度丢失 ---
            var layoutWidth = layoutResult.size.width.toFloat()
            if (syllable.content.endsWith(" ")) {
                val trimmedWidth = textMeasurer.measure(syllable.content.trimEnd(), style).size.width.toFloat()
                if (layoutWidth <= trimmedWidth) {
                    val spaceCount = syllable.content.length - syllable.content.trimEnd().length
                    layoutWidth = trimmedWidth + (spaceWidth * spaceCount)
                }
            }
            // -----------------------------

            // 新增：如果需要高级动画，预先测量每个字符
            val (charLayouts, charBounds) = if (useAwesomeAnimation) {
                val layouts = syllable.content.map { char ->
                    textMeasurer.measure(char.toString(), style)
                }
                val bounds = syllable.content.indices.map { index ->
                    layoutResult.getBoundingBox(index)
                }
                layouts to bounds
            } else {
                null to null
            }

            SyllableLayout(
                syllable = syllable,
                textLayoutResult = layoutResult,
                wordId = wordIndex,
                useAwesomeAnimation = useAwesomeAnimation,
                width = layoutWidth, // 使用修正后的宽度
                charLayouts = charLayouts,      // 存入缓存
                charOriginalBounds = charBounds // 存入缓存
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

    // 1. 先按 wordId 将音节分组
    val wordGroups = mutableListOf<List<SyllableLayout>>()
    if (syllableLayouts.isNotEmpty()) {
        var currentWordGroup = mutableListOf<SyllableLayout>()
        var currentWordId = syllableLayouts.first().wordId

        syllableLayouts.forEach { layout ->
            if (layout.wordId != currentWordId) {
                wordGroups.add(currentWordGroup)
                currentWordGroup = mutableListOf()
                currentWordId = layout.wordId
            }
            currentWordGroup.add(layout)
        }
        wordGroups.add(currentWordGroup)
    }

    // 2. 按单词进行排版
    wordGroups.forEach { wordSyllables ->
        val wordWidth = wordSyllables.sumOf { it.width.toDouble() }.toFloat()

        // 如果当前行能放下整个单词
        if (currentLineWidth + wordWidth <= availableWidthPx) {
            currentLine.addAll(wordSyllables)
            currentLineWidth += wordWidth
        } else {
            // 放不下，先换行（如果当前行不是空的）
            if (currentLine.isNotEmpty()) {
                val trimmedDisplayLine = trimDisplayLineTrailingSpaces(currentLine, textMeasurer, style)
                if (trimmedDisplayLine.syllables.isNotEmpty()) {
                    lines.add(trimmedDisplayLine)
                }
                currentLine.clear()
                currentLineWidth = 0f
            }

            // 换行后，检查新行能不能放下这个单词
            if (wordWidth <= availableWidthPx) {
                // 能放下，直接加入
                currentLine.addAll(wordSyllables)
                currentLineWidth += wordWidth
            } else {
                // 【特殊情况】单词超级长（比如德语符合词），比一整行还宽
                // 此时必须破坏单词完整性，退化回按音节换行
                wordSyllables.forEach { syllable ->
                    if (currentLineWidth + syllable.width > availableWidthPx && currentLine.isNotEmpty()) {
                        val trimmedLine = trimDisplayLineTrailingSpaces(currentLine, textMeasurer, style)
                        if (trimmedLine.syllables.isNotEmpty()) lines.add(trimmedLine)
                        currentLine.clear()
                        currentLineWidth = 0f
                    }
                    currentLine.add(syllable)
                    currentLineWidth += syllable.width
                }
            }
        }
    }

    // 处理最后一行
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
            if (j > 1 && syllableLayouts[j - 2].wordId == syllableLayouts[j - 1].wordId) {
                currentLineWidth += syllableLayouts[j - 1].width
                if (currentLineWidth > availableWidthPx) break
                continue
            }

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
 * 修复版：移除 Alignment 依赖，使用布尔值控制布局起始点，解决 RTL 居中问题
 */
private fun calculateStaticLineLayout(
    wrappedLines: List<WrappedLine>,
    isLineRightAligned: Boolean,
    canvasWidth: Float,
    lineHeight: Float,
    isRtl: Boolean
): List<List<SyllableLayout>> {
    val layoutsByWord = mutableMapOf<Int, MutableList<SyllableLayout>>()

    val positionedLines = wrappedLines.mapIndexed { lineIndex, wrappedLine ->
        val lineY = lineIndex * lineHeight

        // 核心逻辑：如果是右对齐，起始点 = 画布宽 - 行宽。否则为 0。
        val startX = if (isLineRightAligned) {
            canvasWidth - wrappedLine.totalWidth
        } else {
            0f
        }

        // 下面的逻辑处理 RTL 单词内的排列，保持不变
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

    // --- 以下保持原样 ---
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
                    activeSyllableLayout.position.x + activeSyllableLayout.width * (1f - syllableProgress)
                } else {
                    activeSyllableLayout.position.x + activeSyllableLayout.width * syllableProgress
                }
            }
            else -> {
                val lastFinished = lineLayout.lastOrNull { currentTimeMs >= it.syllable.end }
                if (isRtl) {
                    lastFinished?.position?.x ?: totalMaxX
                } else {
                    lastFinished?.let { it.position.x + it.width } ?: totalMinX
                }
            }
        }
        ((currentPixelPosition - totalMinX) / totalWidth).coerceIn(0f, 1f)
    }

    val fadeRange = (minFadeWidth / totalWidth).coerceAtMost(1f)
    val fadeCenterStart = -fadeRange / 2f
    val fadeCenterEnd = 1f + fadeRange / 2f
    val fadeCenter = fadeCenterStart + (fadeCenterEnd - fadeCenterStart) * lineProgress
    val fadeStart = fadeCenter - fadeRange / 2f
    val fadeEnd = fadeCenter + fadeRange / 2f

    val colorStops = if (isRtl) {
        arrayOf(
            0.0f to inactiveColor,
            fadeStart.coerceIn(0f, 1f) to inactiveColor,
            fadeEnd.coerceIn(0f, 1f) to activeColor,
            1.0f to activeColor
        )
    } else {
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

/**
 * 修复版：使用 getBoundingBox 解决 RTL 字符位置错乱问题
 */
fun DrawScope.drawLine(
    lineLayouts: List<List<SyllableLayout>>,
    currentTimeMs: Int,
    color: Color,
    blendMode: BlendMode,
    isRtl: Boolean,
    showDebugRectangles: Boolean = false
) {
    lineLayouts.forEach { rowLayouts ->
        if (rowLayouts.isEmpty()) return@forEach

        val firstSyllableStart = rowLayouts.first().syllable.start
        val lastSyllableEnd = rowLayouts.last().syllable.end

        // 优化 A: 还没开始唱的行 -> 直接画暗色，不使用 saveLayer
        if (currentTimeMs < firstSyllableStart) {
            drawRowText(rowLayouts, color.copy(alpha = 0.2f), blendMode, showDebugRectangles, currentTimeMs)
            return@forEach
        }

        // 优化 B: 已经唱完的行 -> 直接画亮色，不使用 saveLayer
        if (currentTimeMs >= lastSyllableEnd) {
            drawRowText(rowLayouts, color, blendMode, showDebugRectangles, currentTimeMs)
            return@forEach
        }

        // 优化 C: 只有正在唱的行 -> 才开销 saveLayer 做渐变遮罩
        val minX = rowLayouts.minOf { it.position.x }
        val maxX = rowLayouts.maxOf { it.position.x + it.width }
        val minY = rowLayouts.minOf { it.position.y }
        val totalHeight = rowLayouts.maxOf { it.textLayoutResult.size.height }.toFloat()

        val verticalPadding = (totalHeight * 0.1).dp.toPx()
        val horizontalPadding = ((maxX - minX) * 0.2).dp.toPx()

        drawIntoCanvas { canvas ->
            val layerBounds = Rect(
                left = minX - horizontalPadding,
                top = minY - verticalPadding,
                right = maxX + horizontalPadding,
                bottom = minY + totalHeight + verticalPadding
            )
            // 传入 null paint 节省对象分配
            canvas.saveLayer(layerBounds, LayerPaint)

            // 绘制白色底文字
            drawRowText(rowLayouts, color, blendMode, showDebugRectangles, currentTimeMs)

            // 绘制进度遮罩 (DstIn)
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

// 提取出的纯文本绘制逻辑 (去除 Canvas 操作)
private fun DrawScope.drawRowText(
    rowLayouts: List<SyllableLayout>,
    drawColor: Color,
    blendMode: BlendMode,
    showDebugRectangles: Boolean,
    currentTimeMs: Int
) {
    rowLayouts.forEachIndexed { index, syllableLayout ->
        val wordAnimInfo = syllableLayout.wordAnimInfo

        if (wordAnimInfo != null) {
            val fastCharAnimationThresholdMs = 200f
            val awesomeDuration = wordAnimInfo.wordDuration * 0.8f

            // 使用缓存的 charLayouts 和 charOriginalBounds
            val charLayouts = syllableLayout.charLayouts ?: emptyList()
            val charBounds = syllableLayout.charOriginalBounds ?: emptyList()

            syllableLayout.syllable.content.forEachIndexed { charIndex, _ ->
                val singleCharLayoutResult = charLayouts.getOrNull(charIndex) ?: return@forEachIndexed
                val charBox = charBounds.getOrNull(charIndex) ?: return@forEachIndexed

                val absoluteCharIndex = syllableLayout.charOffsetInWord + charIndex
                val numCharsInWord = wordAnimInfo.wordContent.length
                val earliestStartTime = wordAnimInfo.wordStartTime
                val latestStartTime = wordAnimInfo.wordEndTime - awesomeDuration

                val charRatio = if (numCharsInWord > 1) absoluteCharIndex.toFloat() / (numCharsInWord - 1) else 0.5f
                val awesomeStartTime = (earliestStartTime + (latestStartTime - earliestStartTime) * charRatio).toLong()
                val awesomeProgress = ((currentTimeMs - awesomeStartTime).toFloat() / awesomeDuration).coerceIn(0f, 1f)

                val floatOffset = 4f * DipAndRise(
                    dip = ((0.5 * (wordAnimInfo.wordDuration - fastCharAnimationThresholdMs * numCharsInWord) / 1000)).coerceIn(0.0, 0.5)
                ).transform(1.0f - awesomeProgress)
                val scale = 1f + Swell(
                    (0.1 * (wordAnimInfo.wordDuration - fastCharAnimationThresholdMs * numCharsInWord) / 1000).coerceIn(0.0, 0.1)
                ).transform(awesomeProgress)

                val centeredOffsetX = (charBox.width - singleCharLayoutResult.size.width) / 2f
                val xPos = syllableLayout.position.x + charBox.left + centeredOffsetX
                val yPos = syllableLayout.position.y + charBox.top + floatOffset

                val blurRadius = 10f * Bounce.transform(awesomeProgress)
                val shadow = Shadow(
                    color = drawColor.copy(0.4f),
                    offset = Offset(0f, 0f),
                    blurRadius = blurRadius
                )

                withTransform({ scale(scale = scale, pivot = syllableLayout.wordPivot) }) {
                    drawText(
                        textLayoutResult = singleCharLayoutResult,
                        color = drawColor,
                        topLeft = Offset(xPos, yPos),
                        shadow = shadow,
                        blendMode = blendMode
                    )
                    if (showDebugRectangles) {
                        drawRect(
                            color = Color.Red, topLeft = Offset(xPos, yPos), size = Size(
                                singleCharLayoutResult.size.width.toFloat(),
                                singleCharLayoutResult.size.height.toFloat()
                            ), style = Stroke(1f)
                        )
                    }
                }
            }
        } else {
            val driverLayout = if (syllableLayout.syllable.content.trim().isPunctuation()) {
                var searchIndex = index - 1
                while (searchIndex >= 0) {
                    val candidate = rowLayouts[searchIndex]
                    if (!candidate.syllable.content.trim().isPunctuation()) {
                        break
                    }
                    searchIndex--
                }
                if (searchIndex < 0) syllableLayout else rowLayouts[searchIndex]
            } else {
                syllableLayout
            }

            val animationFixedDuration = 700f
            val timeSinceStart = currentTimeMs - driverLayout.syllable.start
            val animationProgress = (timeSinceStart / animationFixedDuration).coerceIn(0f, 1f)

            val maxOffsetY = 4f
            val floatCurveValue = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f).transform(1f - animationProgress)
            val floatOffset = maxOffsetY * floatCurveValue

            val finalPosition = syllableLayout.position.copy(
                y = syllableLayout.position.y + floatOffset
            )

            drawText(
                textLayoutResult = syllableLayout.textLayoutResult,
                color = drawColor,
                topLeft = finalPosition,
                blendMode = blendMode
            )
            if (showDebugRectangles) {
                drawRect(
                    color = Color.Green, topLeft = finalPosition, size = Size(
                        syllableLayout.textLayoutResult.size.width.toFloat(),
                        syllableLayout.textLayoutResult.size.height.toFloat()
                    ), style = Stroke(2f)
                )
            }
        }
    }
}

@Stable
@Composable
fun KaraokeLineText(
    line: KaraokeLine,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    currentTimeProvider: () -> Int,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    normalLineTextStyle: TextStyle,
    accompanimentLineTextStyle: TextStyle,
    blendMode: BlendMode = BlendMode.Plus,
    showDebugRectangles: Boolean = false
) {
    // 1. RTL 检测
    val isRtl = remember(line.syllables) {
        line.syllables.any { it.content.isRtl() }
    }

    val textMeasurer = rememberTextMeasurer()

    // 2. 核心对齐逻辑计算
    // Unspecified 默认视为 Start (跟随语言自然方向)
    val isRightAligned = remember(line.alignment, isRtl) {
        when (line.alignment) {
            // Start 或 Unspecified:
            // 如果是 RTL，Start 意味着在右边 -> True
            // 如果是 LTR，Start 意味着在左边 -> False
            KaraokeAlignment.Start, KaraokeAlignment.Unspecified -> isRtl

            // End:
            // 如果是 RTL，End 意味着在左边 -> False
            // 如果是 LTR，End 意味着在右边 -> True
            KaraokeAlignment.End -> !isRtl
        }
    }

    // 3. 动画锚点：如果是右对齐，缩放中心点在右侧 (1f)，否则在左侧 (0f)
    val scaleTransformOrigin = remember(isRightAligned) {
        TransformOrigin(
            pivotFractionX = if (isRightAligned) 1f else 0f,
            pivotFractionY = 1f
        )
    }

    // 4. 翻译文本对齐
    val translationTextAlign = remember(isRightAligned) {
        if (isRightAligned) TextAlign.End else TextAlign.Start
    }

    // Column 内部对齐 (用于非 Canvas 元素，如翻译文本)
    val columnHorizontalAlignment = remember(isRightAligned) {
        if (isRightAligned) Alignment.End else Alignment.Start
    }

    val animatedScaleState = animateFloatAsState(
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
    val animatedAlphaState = animateFloatAsState(
        targetValue = if (!line.isAccompaniment) if (isFocused) 1f else 0.4f else if (isFocused) 0.6f else 0.2f,
        label = "alpha"
    )

    Box(
        modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(8.dp))
            .combinedClickable(
                onClick = { onLineClicked(line) },
                onLongClick = { onLinePressed(line) }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth() // 关键：始终占满宽度，不使用 align 移动 Column
                .padding(vertical = 8.dp, horizontal = 16.dp)
                .graphicsLayer {
                    scaleX = animatedScaleState.value
                    scaleY = animatedScaleState.value
                    transformOrigin = scaleTransformOrigin
                },
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = columnHorizontalAlignment // 控制翻译文本的位置
        ) {
            BoxWithConstraints(Modifier.graphicsLayer {
                alpha = animatedAlphaState.value
            }) {
                val density = LocalDensity.current
                val availableWidthPx = with(density) { maxWidth.toPx() }

                val textStyle = remember(line.isAccompaniment) {
                    val baseStyle = if (line.isAccompaniment) accompanimentLineTextStyle else normalLineTextStyle
                    baseStyle.copy(textDirection = TextDirection.Content)
                }

                val spaceWidth = remember(textMeasurer, textStyle) {
                    textMeasurer.measure(" ", textStyle).size.width.toFloat()
                }

                val processedSyllables = remember(line.syllables, line.alignment) {
                    // 仅在 End 且非 RTL 时移除尾部空格，或者根据需要调整逻辑
                    // 简单起见，这里可以保持原逻辑，或者去掉 trim 逻辑以保证数据一致性
                    if (line.alignment == KaraokeAlignment.End) {
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
                            isAccompanimentLine = line.isAccompaniment,
                            spaceWidth = spaceWidth
                        )
                    }
                }

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

                // 传入 isRightAligned，完全由 calculateStaticLineLayout 计算 X 坐标
                val finalLineLayouts = remember(wrappedLines, availableWidthPx, lineHeight, isRtl, isRightAligned) {
                    calculateStaticLineLayout(
                        wrappedLines = wrappedLines,
                        isLineRightAligned = isRightAligned,
                        canvasWidth = availableWidthPx,
                        lineHeight = lineHeight,
                        isRtl = isRtl
                    )
                }

                val totalHeight = remember(wrappedLines, lineHeight) {
                    lineHeight * wrappedLines.size
                }

                // Canvas 始终填满 BoxWithConstraints 给的宽度 (maxWidth)
                Canvas(modifier = Modifier.size(maxWidth, (totalHeight.roundToInt() + 8).toDp())) {
                    val time = currentTimeProvider()
                    drawLine(
                        lineLayouts = finalLineLayouts,
                        currentTimeMs = time,
                        color = activeColor,
                        blendMode = blendMode,
                        isRtl = isRtl,
                        showDebugRectangles = showDebugRectangles
                    )
                }
            }

            line.translation?.let { translation ->
                Text(
                    text = translation,
                    color = activeColor.copy(0.4f),
                    modifier = Modifier.graphicsLayer {
                        this.blendMode = blendMode
                    },
                    textAlign = translationTextAlign // 翻译文本使用 Text 的对齐属性
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