package com.maxmfonseca.pontinho.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar

@Composable
fun AboutScreen(versionName: String, onBack: () -> Unit, onLearn: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).testTag("about_root"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PontinhoTopBar(R.string.route_about, onBack = onBack)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(64.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.about_version, versionName), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val purpose = stringResource(R.string.about_purpose)
                    val learnPhrase = stringResource(R.string.route_learn)
                    Text(buildAnnotatedString {
                        append(purpose)
                        val linkStart = purpose.lastIndexOf(learnPhrase)
                        if (linkStart >= 0) {
                            addLink(LinkAnnotation.Clickable(
                                tag = "learn",
                                styles = TextLinkStyles(SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                )),
                                linkInteractionListener = { onLearn() },
                            ), linkStart, linkStart + learnPhrase.length)
                        }
                    }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(stringResource(R.string.about_support), style = MaterialTheme.typography.titleMedium)
            Text(
                text = "maxmfonseca12@gmail.com",
                modifier = Modifier.fillMaxWidth().testTag("about_support_email"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
