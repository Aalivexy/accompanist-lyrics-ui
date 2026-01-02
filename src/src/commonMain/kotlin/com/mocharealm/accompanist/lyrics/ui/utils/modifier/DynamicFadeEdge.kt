package com.mocharealm.accompanist.lyrics.ui.utils.modifier

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.ui.utils.LayerPaint

fun Modifier.dynamicFadingEdge(
    listState: LazyListState,
    index: Int,
    fadeHeight: Dp = 100.dp
): Modifier = this.drawWithContent {
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val itemInfo = visibleItems.find { it.index == index }

    if (itemInfo == null) {
        drawContent()
        return@drawWithContent
    }

    val fadeHeightPx = fadeHeight.toPx()

    val containerHeight = layoutInfo.viewportSize.height.toFloat()

    val itemTop = (itemInfo.offset + layoutInfo.beforeContentPadding).toFloat()
    val itemBottom = itemTop + itemInfo.size.toFloat()

    val touchesTop = itemTop < fadeHeightPx

    val touchesBottom = itemBottom > (containerHeight - fadeHeightPx)

    if (!touchesTop && !touchesBottom) {
        drawContent()
        return@drawWithContent
    }

    drawContext.canvas.saveLayer(
        Rect(0f, 0f, size.width, size.height),
        LayerPaint
    )

    drawContent()

    if (touchesTop) {
        val gradientStart = -itemTop
        val gradientEnd = fadeHeightPx - itemTop

        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color.Black,
                startY = gradientStart,
                endY = gradientEnd
            ),
            blendMode = BlendMode.DstIn
        )
    }

    if (touchesBottom) {
        val gradientStart = (containerHeight - fadeHeightPx) - itemTop
        val gradientEnd = containerHeight - itemTop

        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black,
                1f to Color.Transparent,
                startY = gradientStart,
                endY = gradientEnd
            ),
            blendMode = BlendMode.DstIn
        )
    }

    drawContext.canvas.restore()
}