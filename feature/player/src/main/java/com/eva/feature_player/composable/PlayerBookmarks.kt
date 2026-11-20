package com.eva.feature_player.composable

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.DialogProperties
import com.eva.bookmarks.domain.AudioBookmarkModel
import com.eva.feature_player.bookmarks.composable.AddBookmarkDialogContent
import com.eva.feature_player.bookmarks.composable.AudioBookmarksList
import com.eva.feature_player.bookmarks.state.BookMarkEvents
import com.eva.feature_player.bookmarks.state.CreateBookmarkState
import com.eva.player.domain.model.PlayerTrackData
import com.eva.ui.R
import kotlin.time.Duration
import kotlinx.collections.immutable.ImmutableList

/** Dialogs and the bookmark list sheet. Triggers live in the top app bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerBookmarkDialogs(
    trackCurrentTime: () -> Duration,
    bookmarks: ImmutableList<AudioBookmarkModel>,
    onBookmarkEvent: (BookMarkEvents) -> Unit,
    showList: Boolean,
    onListDismiss: () -> Unit,
    showCreateDialog: Boolean = false,
    textFieldState: TextFieldValue = TextFieldValue(),
    isBookmarkUpdate: Boolean = false,
) {
    val bookmarkSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(showList) {
        if (showList) bookmarkSheet.show()
    }

    if (showList) {
        ModalBottomSheet(
            sheetState = bookmarkSheet,
            onDismissRequest = onListDismiss,
        ) {
            AudioBookmarksList(
                bookmarks = bookmarks,
                onExportBookmarks = { onBookmarkEvent(BookMarkEvents.OnExportBookMarkPoints) },
                onEditBookMark = { onBookmarkEvent(BookMarkEvents.OpenDialogToEdit(it)) },
                onDeleteBookMark = { onBookmarkEvent(BookMarkEvents.OnDeleteBookmark(it)) },
                contentPadding = PaddingValues(all = dimensionResource(id = R.dimen.bottom_sheet_padding_lg)),
            )
        }
    }

    if (showCreateDialog) {
        BasicAlertDialog(
            onDismissRequest = { onBookmarkEvent(BookMarkEvents.OnCloseDialog) },
            properties = DialogProperties(dismissOnClickOutside = false),
        ) {
            AddBookmarkDialogContent(
                isUpdate = isBookmarkUpdate,
                textFieldValue = textFieldState,
                onValueChange = { onBookmarkEvent(BookMarkEvents.OnUpdateTextField(it)) },
                onDismiss = { onBookmarkEvent(BookMarkEvents.OnCloseDialog) },
                onConfirm = {
                    val currentTime = trackCurrentTime()
                    onBookmarkEvent(BookMarkEvents.OnAddOrUpdateBookMark(currentTime))
                },
            )
        }
    }
}

@Composable
internal fun PlayerBookmarkDialogs(
    trackData: () -> PlayerTrackData,
    bookmarks: ImmutableList<AudioBookmarkModel>,
    bookMarkState: CreateBookmarkState,
    onBookmarkEvent: (BookMarkEvents) -> Unit,
    showList: Boolean,
    onListDismiss: () -> Unit,
) {
    PlayerBookmarkDialogs(
        bookmarks = bookmarks,
        trackCurrentTime = { trackData().current },
        showCreateDialog = bookMarkState.showDialog,
        textFieldState = bookMarkState.textValue,
        isBookmarkUpdate = bookMarkState.isUpdate,
        onBookmarkEvent = onBookmarkEvent,
        showList = showList,
        onListDismiss = onListDismiss,
    )
}
