package com.maxmfonseca.pontinho.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.theme.Coral
import com.maxmfonseca.pontinho.ui.theme.Emerald
import com.maxmfonseca.pontinho.ui.theme.OffWhite

val RoundStatusContainerColorKey = SemanticsPropertyKey<Int>("RoundStatusContainerColor")
var SemanticsPropertyReceiver.roundStatusContainerColor by RoundStatusContainerColorKey

enum class RoundStatusKind(
    val symbol: String,
    @StringRes val description: Int,
) {
    WINNER("✓", R.string.round_status_winner),
    LOSS("×", R.string.round_status_loss),
    PENDING("◷", R.string.round_status_pending),
    NOT_PARTICIPATING("−", R.string.round_status_not_participating),
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
        RoundStatusKind.PENDING,
        RoundStatusKind.NOT_PARTICIPATING,
        -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (status) {
        RoundStatusKind.PENDING,
        RoundStatusKind.NOT_PARTICIPATING,
        -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> OffWhite
    }

    Surface(
        modifier = modifier
            .size(28.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                roundStatusContainerColor = background.toArgb()
            },
        shape = CircleShape,
        color = background,
        contentColor = foreground,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(status.symbol, fontWeight = FontWeight.Bold)
        }
    }
}
