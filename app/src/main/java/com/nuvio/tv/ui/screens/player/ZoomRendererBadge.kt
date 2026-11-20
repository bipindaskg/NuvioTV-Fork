package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.InternalPlayerEngine

/** True while Exo video is actually rendered through the GPU (TextureView) zoom renderer. */
internal fun PlayerUiState.showsZoomRendererBadge(): Boolean =
    useZoomRenderer && !tunnelingEnabled && internalPlayerEngine == InternalPlayerEngine.EXOPLAYER

/** Small "GPU ZOOM" pill shown next to the stream badges while the zoom renderer is on. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ZoomRendererBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xFF1E88E5).copy(alpha = 0.9f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ZoomIn,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "GPU ZOOM",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
