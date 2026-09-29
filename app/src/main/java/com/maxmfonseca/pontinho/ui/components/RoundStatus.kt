package com.maxmfonseca.pontinho.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.theme.Coral
import com.maxmfonseca.pontinho.ui.theme.Emerald
import com.maxmfonseca.pontinho.ui.theme.OffWhite

val RoundStatusContainerColorKey = SemanticsPropertyKey<Int>("RoundStatusContainerColor")
var SemanticsPropertyReceiver.roundStatusContainerColor by RoundStatusContainerColorKey

enum class RoundStatusKind(
    @StringRes val description: Int,
) {
    WINNER(R.string.round_status_winner),
    LOSS(R.string.round_status_loss),
    PENDING(R.string.round_status_pending),
    EMPTY(R.string.round_status_empty),
    NOT_PARTICIPATING(R.string.round_status_not_participating),
}

@Composable
fun RoundStatus(
    status: RoundStatusKind,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(status.description)
    val background = when (status) {
        RoundStatusKind.WINNER -> Emerald
        RoundStatusKind.LOSS -> Coral
        RoundStatusKind.EMPTY -> MaterialTheme.colorScheme.surface
        RoundStatusKind.PENDING,
        RoundStatusKind.NOT_PARTICIPATING,
        -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (status) {
        RoundStatusKind.PENDING,
        RoundStatusKind.EMPTY,
        RoundStatusKind.NOT_PARTICIPATING,
        -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> OffWhite
    }

    Surface(
        modifier = modifier
            .size(14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                roundStatusContainerColor = background.toArgb()
            },
        shape = CircleShape,
        border = if (status == RoundStatusKind.EMPTY) BorderStroke(.5.dp, MaterialTheme.colorScheme.onSurfaceVariant) else null,
        color = background,
        contentColor = foreground,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (status) {
                RoundStatusKind.WINNER -> Icon(Icons.Filled.Check, null, Modifier.size(9.dp))
                RoundStatusKind.LOSS -> Icon(Icons.Filled.Close, null, Modifier.size(9.dp))
                RoundStatusKind.PENDING -> Canvas(Modifier.size(8.dp)) {
                    drawCircle(foreground, style = Stroke(.75.dp.toPx()))
                    drawLine(foreground, center, Offset(center.x, size.height * .2f), .75.dp.toPx(), StrokeCap.Round)
                    drawLine(foreground, center, Offset(size.width * .7f, center.y), .75.dp.toPx(), StrokeCap.Round)
                }
                RoundStatusKind.NOT_PARTICIPATING -> Canvas(Modifier.size(7.dp)) {
                    drawLine(foreground, Offset(0f, center.y), Offset(size.width, center.y), 1.dp.toPx(), StrokeCap.Round)
                }
                RoundStatusKind.EMPTY -> Unit
            }
        }
    }
}
