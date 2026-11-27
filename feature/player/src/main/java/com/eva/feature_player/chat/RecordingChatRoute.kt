package com.eva.feature_player.chat

import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.eva.ui.navigation.NavRoutes
import com.eva.ui.navigation.PlayerSubGraph
import com.eva.ui.navigation.animatedComposable

fun NavGraphBuilder.recordingChatRoute(controller: NavHostController) =
	animatedComposable<PlayerSubGraph.RecordingChatRoute> { backStackEntry ->
		val route = backStackEntry.toRoute<PlayerSubGraph.RecordingChatRoute>()
		val viewModel = hiltViewModel<RecordingChatViewModel>()

		LaunchedEffect(route.audioId) {
			viewModel.bind(route.audioId)
		}

		RecordingChatScreen(
			recordingTitle = route.title,
			loading = viewModel.loading,
			messages = viewModel.messages,
			sending = viewModel.sending,
			pendingQuestion = viewModel.pendingQuestion,
			errorMessage = viewModel.errorMessage,
			transcriptBlockedReason = viewModel.transcriptBlockedReason,
			configBlockedReason = viewModel.configBlockedReason,
			contextInfo = viewModel.contextInfo,
			estimate = viewModel.estimate,
			recordedUsage = viewModel.recordedUsage,
			activeModel = viewModel.activeModel,
			confirmedCost = viewModel.confirmedCost,
			onBack = dropUnlessResumed(block = controller::popBackStack),
			onNavigateToSettings = dropUnlessResumed {
				controller.navigate(NavRoutes.AudioSettings(openCloudAi = true))
			},
			onSeekTo = { ms ->
				controller.previousBackStackEntry?.savedStateHandle?.set(CHAT_SEEK_MS_KEY, ms)
				controller.popBackStack()
			},
			onSend = viewModel::send,
			onConfirmCostAndSend = viewModel::confirmCostAndSend,
			onRefreshEstimate = viewModel::refreshEstimate,
			onCancelSend = if (viewModel.canCancelSend) viewModel::cancel else null,
			onClearHistory = viewModel::clearHistory,
			onDismissError = viewModel::dismissError,
		)
	}

internal const val CHAT_SEEK_MS_KEY = "recording_chat_seek_ms"
