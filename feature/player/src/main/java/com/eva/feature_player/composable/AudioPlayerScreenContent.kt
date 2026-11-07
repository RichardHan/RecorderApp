package com.eva.feature_player.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eva.bookmarks.domain.AudioBookmarkModel
import com.eva.feature_player.bookmarks.state.BookMarkEvents
import com.eva.feature_player.bookmarks.state.CreateBookmarkState
import com.eva.feature_player.state.PlayerEvents
import com.eva.player.domain.model.PlayerMetaData
import com.eva.player.domain.model.PlayerTrackData
import com.eva.player_shared.composables.PlayerDurationText
import com.eva.player_shared.util.PlayerGraphData
import com.eva.ui.theme.DownloadableFonts
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun AudioPlayerScreenContent(
    waveforms: PlayerGraphData,
    bookMarkState: CreateBookmarkState,
    trackData: () -> PlayerTrackData,
    playerMetaData: PlayerMetaData,
    bookmarks: ImmutableList<AudioBookmarkModel>,
    onPlayerEvents: (PlayerEvents) -> Unit,
    modifier: Modifier = Modifier,
    onBookmarkEvent: (BookMarkEvents) -> Unit = {},
    isControllerReady: Boolean = false,
    isPlayerPlaying: Boolean = true,
    isBookmarksEnabled: Boolean = true,
    transcriptPanel: (@Composable (Modifier) -> Unit)? = null,
) {
    val bookMarkTimeStamps by remember(bookmarks) {
        derivedStateOf {
            bookmarks.map(AudioBookmarkModel::timeStamp)
                .toImmutableList()
        }
    }

    if (transcriptPanel != null) {
        // compact layout: small waveform, saved transcript gets the free space, controls stay at the bottom
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlayerDurationText(
                track = trackData,
                fontFamily = DownloadableFonts.SPLINE_SANS_MONO_FONT_FAMILY,
            )
            PlayerAmplitudeGraph2(
                trackData = trackData,
                bookMarksTimeStamps = bookMarkTimeStamps,
                graphData = waveforms,
                isSwipeToScrollEnabled = true,
                onSeek = { amount -> onPlayerEvents(PlayerEvents.OnSeekingPlayer(amount)) },
                onSeekEnd = { onPlayerEvents(PlayerEvents.OnSeekEndPlayer) },
                timelineFontFamily = DownloadableFonts.PLUS_CODE_LATIN_FONT_FAMILY,
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
            PlayerBookMarks(
                trackData = trackData,
                isBookmarkActionEnabled = isBookmarksEnabled,
                bookmarks = bookmarks,
                bookMarkState = bookMarkState,
                onBookmarkEvent = onBookmarkEvent,
                modifier = Modifier.fillMaxWidth(),
            )
            transcriptPanel(Modifier.fillMaxWidth().weight(1f))
            PlayerActionsAndSlider(
                metaData = playerMetaData,
                trackData = trackData,
                isPlayerPlaying = isPlayerPlaying,
                isControllerSet = isControllerReady,
                onPlayerAction = onPlayerEvents,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        PlayerDurationText(
            track = trackData,
            fontFamily = DownloadableFonts.SPLINE_SANS_MONO_FONT_FAMILY,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .offset(y = (-80).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlayerAmplitudeGraph2(
                trackData = trackData,
                bookMarksTimeStamps = bookMarkTimeStamps,
                graphData = waveforms,
                isSwipeToScrollEnabled = true,
                onSeek = { amount -> onPlayerEvents(PlayerEvents.OnSeekingPlayer(amount)) },
                onSeekEnd = { onPlayerEvents(PlayerEvents.OnSeekEndPlayer) },
                timelineFontFamily = DownloadableFonts.PLUS_CODE_LATIN_FONT_FAMILY,
                modifier = Modifier.fillMaxWidth(),
            )
            PlayerBookMarks(
                trackData = trackData,
                isBookmarkActionEnabled = isBookmarksEnabled,
                bookmarks = bookmarks,
                bookMarkState = bookMarkState,
                onBookmarkEvent = onBookmarkEvent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PlayerActionsAndSlider(
            metaData = playerMetaData,
            trackData = trackData,
            isPlayerPlaying = isPlayerPlaying,
            isControllerSet = isControllerReady,
            onPlayerAction = onPlayerEvents,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
        )
    }
}
