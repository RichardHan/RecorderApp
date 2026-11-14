package com.eva.feature_player

import android.content.Intent
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.eva.feature_player.bookmarks.BookMarksViewModel
import com.eva.feature_player.bookmarks.BookmarksViewmodelFactory
import com.eva.feature_player.composable.ControllerLifeCycleObserver
import com.eva.feature_player.state.PlayerEvents
import com.eva.feature_player.viewmodel.AudioPlayerViewModel
import com.eva.feature_player.viewmodel.PlayerViewmodelFactory
import com.eva.player_shared.PlayerMetadataViewmodel
import com.eva.player_shared.PlayerVisualizerViewmodel
import com.eva.player_shared.factory.PlayerMetadataViewmodelFactory
import com.eva.player_shared.factory.PlayerVisualizerViewmodelFactory
import com.eva.ui.R
import com.eva.ui.animation.SharedElementTransitionKeys
import com.eva.ui.animation.sharedBoundsWrapper
import com.eva.ui.navigation.NavDialogs
import com.eva.ui.navigation.NavRoutes
import com.eva.ui.navigation.PlayerSubGraph
import com.eva.ui.navigation.animatedComposable
import com.eva.ui.utils.LocalSharedTransitionVisibilityScopeProvider
import com.eva.ui.utils.UiEventsHandler
import com.eva.ui.utils.sharedViewmodel
import com.eva.utils.NavDeepLinks
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.merge

fun NavGraphBuilder.audioPlayerRoute(controller: NavHostController) =
    animatedComposable<PlayerSubGraph.AudioPlayerRoute>(
        deepLinks = listOf(
            navDeepLink {
                uriPattern = NavDeepLinks.PLAYER_DESTINATION_PATTERN
                action = Intent.ACTION_VIEW
            },
            navDeepLink {
                uriPattern = "content://{authority}/.*/{audioId}"
                action = Intent.ACTION_VIEW
                mimeType = "audio/*"
            },
        ),
    ) { backStackEntry ->

        val route = backStackEntry.toRoute<PlayerSubGraph.AudioPlayerRoute>()

        // shared view models
        val visualsViewModel =
            backStackEntry.sharedViewmodel<PlayerVisualizerViewmodel, PlayerVisualizerViewmodelFactory>(
                controller = controller,
                factory = { factory -> factory.create(route.audioId) },
            )

        val metaDataViewmodel = backStackEntry.sharedViewmodel<PlayerMetadataViewmodel, PlayerMetadataViewmodelFactory>(
            controller = controller,
            factory = { factory -> factory.create(route.audioId) },
        )

        // route based view models
        val bookmarkViewmodel = hiltViewModel<BookMarksViewModel, BookmarksViewmodelFactory>(
            creationCallback = { factory -> factory.create(route.audioId) },
        )
        val playerViewModel = hiltViewModel<AudioPlayerViewModel, PlayerViewmodelFactory>(
            creationCallback = { factory -> factory.create(route.audioId) },
        )

        val visuals by visualsViewModel.fullVisualization.collectAsStateWithLifecycle()
        val contentState by metaDataViewmodel.loadState.collectAsStateWithLifecycle()

        // player states
        val trackData by playerViewModel.trackData.collectAsStateWithLifecycle()
        val isPlaying by playerViewModel.isPlayerPlaying.collectAsStateWithLifecycle()
        val playerMetadata by playerViewModel.playerMetaData.collectAsStateWithLifecycle()
        val isControllerReady by playerViewModel.isControllerReady.collectAsStateWithLifecycle()

        // bookmarks state
        val bookMarkState by bookmarkViewmodel.bookmarkState.collectAsStateWithLifecycle()
        val bookMarks by bookmarkViewmodel.bookMarksFlow.collectAsStateWithLifecycle()

        ControllerLifeCycleObserver(
            audioId = route.audioId,
            onEvent = playerViewModel::onControllerEvents,
        )

        // Handle UI Events
        UiEventsHandler(
            eventsFlow = {
                merge(
                    metaDataViewmodel.uiEvent,
                    visualsViewModel.uiEvent,
                    playerViewModel.uiEvent,
                    bookmarkViewmodel.uiEvent,
                )
            },
            onNavigateBack = controller::popBackStack,
        )

        // A transcript-search hit asks for one seek to its real timestamp; the loaded media decides when that is safe.
        var startSeekDone by androidx.compose.runtime.saveable.rememberSaveable(route.audioId) {
            androidx.compose.runtime.mutableStateOf(route.startPositionMs <= 0L)
        }
        LaunchedEffect(isControllerReady, trackData.total, route.audioId) {
            if (startSeekDone || !isControllerReady || trackData.total <= Duration.ZERO) return@LaunchedEffect
            startSeekDone = true
            val position = route.startPositionMs.coerceAtMost(trackData.total.inWholeMilliseconds)
            playerViewModel.onPlayerEvents(PlayerEvents.OnSeekingPlayer(position.milliseconds))
            playerViewModel.onPlayerEvents(PlayerEvents.OnSeekEndPlayer)
        }

        var showCloud by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        var transcriptRefresh by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
        if (showCloud) com.eva.feature_player.cloud.CloudTranscriptsDialog(recordingId = route.audioId, onDismiss = { showCloud = false; transcriptRefresh++ })

        val lifeCycleState by backStackEntry.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

        CompositionLocalProvider(LocalSharedTransitionVisibilityScopeProvider provides this) {
            AudioPlayerScreen(
                loadState = contentState,
                bookmarks = bookMarks,
                waveforms = { visuals },
                trackData = { trackData },
                playerMetaData = playerMetadata,
                isControllerReady = isControllerReady,
                isPlayerPlaying = isPlaying,
                bookMarkState = bookMarkState,
                isAppActionsEnabled = route.appActionsEnabled,
                onFileEvent = metaDataViewmodel::onFileEvent,
                onPlayerEvents = playerViewModel::onPlayerEvents,
                onBookmarkEvent = bookmarkViewmodel::onBookMarkEvent,
                onNavigateToEdit = dropUnlessResumed {
                    controller.navigate(PlayerSubGraph.AudioEditorRoute(route.audioId))
                },
                onRenameItem = { audioId ->
                    if (lifeCycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                        val dialog = NavDialogs.RenameRecordingDialog(audioId)
                        controller.navigate(dialog)
                    }
                },
                onCloudTranscripts = { showCloud = true },
                transcriptPanel = { panelModifier ->
                    com.eva.feature_player.cloud.PlayerSidePanel(
                        recordingId = route.audioId,
                        transcriptPanel = { modifier ->
                            com.eva.feature_player.cloud.SavedTranscriptPanel(
                                recordingId = route.audioId,
                                trackData = { trackData },
                                refreshKey = transcriptRefresh,
                                onSeekTo = { ms ->
                                    playerViewModel.onPlayerEvents(com.eva.feature_player.state.PlayerEvents.OnSeekingPlayer(with(kotlin.time.Duration) { ms.milliseconds }))
                                    playerViewModel.onPlayerEvents(com.eva.feature_player.state.PlayerEvents.OnSeekEndPlayer)
                                },
                                modifier = modifier,
                            )
                        },
                        onSeekTo = { ms ->
                            playerViewModel.onPlayerEvents(com.eva.feature_player.state.PlayerEvents.OnSeekingPlayer(with(kotlin.time.Duration) { ms.milliseconds }))
                            playerViewModel.onPlayerEvents(com.eva.feature_player.state.PlayerEvents.OnSeekEndPlayer)
                        },
                        modifier = panelModifier,
                    )
                },
                onNavigateToRecordings = {
                    controller.navigate(NavRoutes.VoiceRecordings) {
                        popUpTo<PlayerSubGraph.AudioPlayerRoute>()
                    }
                },
                navigation = {
                    if (controller.previousBackStackEntry?.destination?.route != null) {
                        IconButton(
                            onClick = dropUnlessResumed(block = controller::popBackStack),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Default.ArrowBack,
                                contentDescription = stringResource(R.string.back_arrow),
                            )
                        }
                    }
                },
                modifier = Modifier.sharedBoundsWrapper(
                    key = SharedElementTransitionKeys.recordSharedEntryContainer(route.audioId),
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                ),
            )
        }
    }

