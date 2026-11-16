package com.eva.feature_recorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.eva.utils.IntentConstants
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.navDeepLink
import com.eva.feature_recorder.screen.RecorderViewModel
import com.eva.feature_recorder.screen.VoiceRecorderScreen
import com.eva.ui.navigation.NavRoutes
import com.eva.ui.navigation.animatedComposable
import com.eva.ui.utils.LocalSharedTransitionVisibilityScopeProvider
import com.eva.ui.utils.UiEventsHandler
import com.eva.utils.NavDeepLinks

fun NavGraphBuilder.recorderRoute(navController: NavHostController) =
	animatedComposable<NavRoutes.VoiceRecorder>(
		deepLinks = listOf(
			navDeepLink {
				uriPattern = NavDeepLinks.RECORDER_DESTINATION_PATTERN
				action = Intent.ACTION_VIEW
			},
		),
	) {

		val viewModel = hiltViewModel<RecorderViewModel>()

		// Quick Settings tile or 一鍵錄音 widget start: neither can start a microphone foreground
		// service itself, so they open this screen with a one-time request; start only once resumed and permitted.
		val tileStartRequestId = remember(it) { it.tileStartRequestId() }
		val context = LocalContext.current
		val lifecycleOwner = LocalLifecycleOwner.current
		LaunchedEffect(tileStartRequestId) {
			if (tileStartRequestId == null) return@LaunchedEffect
			lifecycleOwner.lifecycle.withResumed { }
			val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
					PackageManager.PERMISSION_GRANTED
			if (granted) viewModel.onTileStartRequest(tileStartRequestId)
			// no permission: the screen already shows its permission prompt; the user starts manually
			else viewModel.discardTileStartRequest(tileStartRequestId)
		}

		val isRecorderReady by viewModel.isServiceReady.collectAsStateWithLifecycle()
		val recorderState by viewModel.recorderState.collectAsStateWithLifecycle()
		val recorderTimer by viewModel.recorderTime.collectAsStateWithLifecycle()
		val recordingPoints by viewModel.recordingPoints.collectAsStateWithLifecycle()
		val transcriptionsResult by viewModel.transcriptionsResult.collectAsStateWithLifecycle()
		val transcriptionSettings by viewModel.transcribeSettings.collectAsStateWithLifecycle()
		val liveCloud by viewModel.liveCloud.collectAsStateWithLifecycle()
		val slackStatus by viewModel.slackStatus.collectAsStateWithLifecycle()
		val liveToggleUi by viewModel.liveToggleUi.collectAsStateWithLifecycle()

		UiEventsHandler(eventsFlow = viewModel::uiEvent)

		CompositionLocalProvider(
			LocalSharedTransitionVisibilityScopeProvider provides this
		) {
			VoiceRecorderScreen(
				isRecorderReady = isRecorderReady,
				recorderState = recorderState,
				transcriptions = transcriptionsResult,
				liveCloud = liveCloud,
				slackStatus = slackStatus,
				onToggleSlackSync = viewModel::setSlackSyncForThisRecording,
				transcriptionSettings = transcriptionSettings,
				liveToggles = liveToggleUi,
				onToggleLocalLive = viewModel::setLocalLive,
				onToggleCloudLive = viewModel::setCloudLive,
				onAcceptCloudConsent = viewModel::acceptCloudConsent,
				recorderTimer = { recorderTimer },
				deferredRecordingPoints = { recordingPoints },
				onRecorderAction = viewModel::onAction,
				onScreenEvent = viewModel::onEvent,
				onToggleLiveTranscription = viewModel::setLocalLive,
				showNotes = true,
				onShowRecordings = dropUnlessResumed { navController.navigate(NavRoutes.VoiceRecordings) },
				onNavigateToBin = dropUnlessResumed { navController.navigate(NavRoutes.TrashRecordings) },
				onNavigateToSettings = dropUnlessResumed { navController.navigate(NavRoutes.AudioSettings) },
			)
		}
	}

private fun NavBackStackEntry.tileStartRequestId(): Long? {
	val args = arguments ?: return null
	val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
		args.getParcelable(NavController.KEY_DEEP_LINK_INTENT, Intent::class.java)
	else @Suppress("DEPRECATION") args.getParcelable(NavController.KEY_DEEP_LINK_INTENT)
	if (intent == null) return null
	val existing = intent.getLongExtra(IntentConstants.EXTRAS_TILE_START_REQUEST_ID, -1L).takeIf { it > 0L }
	if (existing != null) return existing
	// Widget start: assign a unique id once onto the same intent so rotation does not start twice.
	if (!intent.getBooleanExtra(IntentConstants.EXTRAS_WIDGET_TRY_START, false)) return null
	val id = System.currentTimeMillis()
	intent.putExtra(IntentConstants.EXTRAS_TILE_START_REQUEST_ID, id)
	return id
}
