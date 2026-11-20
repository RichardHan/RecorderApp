package com.eva.feature_player.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.eva.bookmarks.domain.AudioBookmarkModel
import com.eva.feature_player.bookmarks.state.BookMarkEvents
import com.eva.feature_player.bookmarks.state.CreateBookmarkState
import com.eva.feature_player.state.PlayerEvents
import com.eva.player.domain.model.PlayerMetaData
import com.eva.player.domain.model.PlayerTrackData
import com.eva.player_shared.util.PlayerGraphData
import com.eva.utils.toDuration
import kotlinx.collections.immutable.ImmutableList

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
    showBookmarkList: Boolean = false,
    onShowBookmarkList: (Boolean) -> Unit = {},
    transcriptPanel: (@Composable (Modifier) -> Unit)? = null,
) {
    // waveforms are no longer drawn on this screen (the bottom seek bar is the only scrubber).
    // The parameter is kept so the visualizer pipeline and call sites stay unchanged.
    @Suppress("UNUSED_VARIABLE")
    val unusedWaveforms = waveforms
    @Suppress("UNUSED_PARAMETER")
    val unusedEnabled = isBookmarksEnabled

    val bookmarkTimesMs by remember(bookmarks) {
        derivedStateOf {
            bookmarks.map { it.timeStamp.toDuration().inWholeMilliseconds }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (transcriptPanel != null) {
            transcriptPanel(Modifier.fillMaxWidth().weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        PlayerActionsAndSlider(
            metaData = playerMetaData,
            trackData = trackData,
            isPlayerPlaying = isPlayerPlaying,
            isControllerSet = isControllerReady,
            onPlayerAction = onPlayerEvents,
            bookmarkTimesMs = bookmarkTimesMs,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    PlayerBookmarkDialogs(
        trackData = trackData,
        bookmarks = bookmarks,
        bookMarkState = bookMarkState,
        onBookmarkEvent = onBookmarkEvent,
        showList = showBookmarkList,
        onListDismiss = { onShowBookmarkList(false) },
    )
}

internal fun fmtSec(s: Long): String = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
