package com.eva.player.data.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.eva.player.domain.model.PlayerState
import java.lang.reflect.Proxy
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioFilePlayerImplTest {

	@Before
	fun setup() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
	}

	@After
	fun teardown() {
		Dispatchers.resetMain()
	}

	private class FakePlayerState(
		var playbackState: Int = Player.STATE_IDLE,
		var playWhenReady: Boolean = false,
		var durationMs: Long = 12_000L,
		var currentPositionMs: Long = 0L,
		var mediaId: String? = "42",
	) {
		val listeners = mutableListOf<Player.Listener>()
		val soughtPositions = mutableListOf<Long>()
		var pauseCalls = 0
	}

	private fun fakePlayer(state: FakePlayerState): Player {
		val item = state.mediaId?.let { MediaItem.Builder().setMediaId(it).build() }
		return Proxy.newProxyInstance(
			Player::class.java.classLoader,
			arrayOf(Player::class.java),
		) { _, method, args ->
			when (method.name) {
				"getPlaybackState" -> state.playbackState
				"getPlayWhenReady" -> state.playWhenReady
				"getDuration" -> state.durationMs
				"getCurrentPosition" -> state.currentPositionMs
				"getCurrentMediaItem" -> state.mediaId?.let { id ->
					if (item?.mediaId == id) item else MediaItem.Builder().setMediaId(id).build()
				}
				"isCommandAvailable" -> true
				"getRepeatMode" -> Player.REPEAT_MODE_OFF
				"getVolume" -> 1f
				"addListener" -> {
					state.listeners += args!![0] as Player.Listener
					Unit
				}
				"removeListener" -> {
					state.listeners -= args!![0] as Player.Listener
					Unit
				}
				"pause" -> {
					state.pauseCalls++
					state.playWhenReady = false
					Unit
				}
				"seekTo" -> {
					val pos = (args!![0] as Long)
					state.soughtPositions += pos
					state.currentPositionMs = pos
					if (state.playbackState == Player.STATE_ENDED && pos < state.durationMs) {
						state.playbackState = Player.STATE_READY
						state.listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
					}
					Unit
				}
				"equals" -> false
				"hashCode" -> 0
				"toString" -> "FakePlayer"
				else -> when (method.returnType) {
					java.lang.Boolean.TYPE -> false
					java.lang.Integer.TYPE -> 0
					java.lang.Long.TYPE -> 0L
					java.lang.Float.TYPE -> 0f
					java.lang.Double.TYPE -> 0.0
					Void.TYPE -> Unit
					else -> null
				}
			}
		} as Player
	}

	@Test
	fun seekToRecording_accepts_STATE_ENDED_pauses_and_seeks_without_hanging() = runTest {
		val state = FakePlayerState(
			playbackState = Player.STATE_ENDED,
			playWhenReady = true,
			durationMs = 12_000L,
			currentPositionMs = 12_000L,
			mediaId = "42",
		)
		val player = fakePlayer(state)
		val wrapper = AudioFilePlayerImpl(player)

		wrapper.seekToRecording(42L, 5.seconds)

		assertEquals(listOf(5_000L), state.soughtPositions)
		assertEquals(5_000L, state.currentPositionMs)
		assertEquals(1, state.pauseCalls)
		assertFalse(state.playWhenReady)
		assertTrue(state.listeners.isEmpty())
	}

	@Test
	fun listener_transitions_playerState_from_COMPLETED_back_to_PLAYER_READY_on_seek() = runTest {
		val state = FakePlayerState(
			playbackState = Player.STATE_ENDED,
			playWhenReady = true,
			durationMs = 12_000L,
			currentPositionMs = 12_000L,
			mediaId = "42",
		)
		val player = fakePlayer(state)
		val listener = AudioFilePlayerListener(player)
		player.addListener(listener)
		listener.onPlaybackStateChanged(Player.STATE_ENDED)
		assertEquals(PlayerState.COMPLETED, listener.playerMetaDataFlow.first().playerState)

		val wrapper = AudioFilePlayerImpl(player)
		wrapper.seekToRecording(42L, 5.seconds)

		assertEquals(PlayerState.PLAYER_READY, listener.playerMetaDataFlow.first().playerState)
	}

	@Test
	fun seekToRecording_waits_for_STATE_READY_when_player_is_idle() = runTest {
		val state = FakePlayerState(
			playbackState = Player.STATE_IDLE,
			durationMs = 0L,
			mediaId = null,
		)
		val player = fakePlayer(state)
		val wrapper = AudioFilePlayerImpl(player)

		val job = launch { wrapper.seekToRecording(42L, 5.seconds) }
		assertTrue(state.soughtPositions.isEmpty())
		assertFalse(job.isCompleted)

		state.mediaId = "42"
		state.durationMs = 12_000L
		state.playbackState = Player.STATE_READY
		state.listeners.toList().forEach {
			it.onEvents(player, Player.Events(androidx.media3.common.FlagSet.Builder().build()))
		}
		job.join()

		assertEquals(listOf(5_000L), state.soughtPositions)
		assertEquals(5_000L, state.currentPositionMs)
	}
}
