package com.riyadm.unsendvault.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.riyadm.unsendvault.R

object HomeTestTags {
    const val STATUS_CARD = "home_status_card"
    const val GRANT = "home_grant"
    const val RESUME = "home_resume"
    const val RECONNECT = "home_reconnect"
    const val LIST = "home_list"
    const val EMPTY = "home_empty"
    fun appChip(pkg: String) = "home_chip_$pkg"
}

private data class StatusVisual(val icon: ImageVector, val title: Int, val body: Int, val container: Color, val content: Color)

/**
 * Capture health card. The status is spelled out in text (and exposed as a state description), never color-only, and
 * announced politely when it changes.
 */
@Composable
fun CaptureStatusCard(
    status: CaptureStatus,
    onGrantAccess: () -> Unit,
    onResume: () -> Unit,
    onReconnect: () -> Unit,
    onReliabilityHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val visual = when (status) {
        CaptureStatus.NO_ACCESS -> StatusVisual(Icons.Outlined.NotificationsOff, R.string.status_no_access_title, R.string.status_no_access_body, colors.errorContainer, colors.onErrorContainer)
        CaptureStatus.PAUSED -> StatusVisual(Icons.Outlined.PauseCircle, R.string.status_paused_title, R.string.status_paused_body, colors.tertiaryContainer, colors.onTertiaryContainer)
        CaptureStatus.DISCONNECTED -> StatusVisual(Icons.Outlined.Sync, R.string.status_disconnected_title, R.string.status_disconnected_body, colors.tertiaryContainer, colors.onTertiaryContainer)
        CaptureStatus.ACTIVE -> StatusVisual(Icons.Filled.CheckCircle, R.string.status_active_title, R.string.status_active_body, colors.secondaryContainer, colors.onSecondaryContainer)
    }
    val stateText = stringResource(visual.title)
    Card(
        modifier = modifier.fillMaxWidth().testTag(HomeTestTags.STATUS_CARD),
        colors = CardDefaults.cardColors(containerColor = visual.container, contentColor = visual.content),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = stateText
                },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(visual.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stateText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                }
                Text(stringResource(visual.body), style = MaterialTheme.typography.bodyMedium)
            }
            when (status) {
                CaptureStatus.NO_ACCESS -> Button(onClick = onGrantAccess, modifier = Modifier.testTag(HomeTestTags.GRANT)) {
                    Text(stringResource(R.string.action_grant_notification_access))
                }
                CaptureStatus.PAUSED -> Button(onClick = onResume, modifier = Modifier.testTag(HomeTestTags.RESUME)) {
                    Text(stringResource(R.string.action_resume_capture))
                }
                CaptureStatus.DISCONNECTED -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onReconnect, modifier = Modifier.testTag(HomeTestTags.RECONNECT)) {
                        Text(stringResource(R.string.action_reconnect))
                    }
                    OutlinedButton(onClick = onReliabilityHelp) {
                        Icon(Icons.Outlined.BatteryAlert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_reliability_help))
                    }
                }
                CaptureStatus.ACTIVE -> Unit
            }
        }
    }
}
