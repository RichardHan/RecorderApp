package com.eva.recorder.di

import android.content.Context
import com.eva.cloudtranscribe.device.DeviceContextSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.location.domain.repository.LocationAddressProvider
import com.eva.location.domain.repository.LocationProvider
import com.eva.recorder.data.RecorderWidgetInteracterImpl
import com.eva.recorder.data.device.AndroidDeviceContextCollector
import com.eva.recorder.data.device.DeviceContextCollector
import com.eva.recorder.data.device.DeviceContextSession
import com.eva.recorder.data.recorder.AudioRecordAmplitudeReader
import com.eva.recorder.domain.RecorderWidgetInteractor
import com.eva.recorder.domain.recorder.AudioDataReader
import com.eva.recorder.domain.stopwatch.RecorderStopWatch
import com.eva.recordings.domain.provider.StorageInfoProvider
import com.eva.utils.RecorderConstants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object RecorderSingletonModule {

	@Provides
	@Singleton
	fun providesAudioBytesReader(@ApplicationContext context: Context): AudioDataReader =
		AudioRecordAmplitudeReader(context)

	@Provides
	@Singleton
	fun providesStopWatch(): RecorderStopWatch =
		RecorderStopWatch(delayTime = RecorderConstants.AMPS_READ_DELAY_RATE)

	@Provides
	@Singleton
	fun providesWidgetInteracter(@ApplicationContext context: Context): RecorderWidgetInteractor =
		RecorderWidgetInteracterImpl(context)

	@Provides
	@Singleton
	fun providesDeviceContextCollector(
		@ApplicationContext context: Context,
		locationProvider: LocationProvider,
		addressProvider: LocationAddressProvider,
		storageInfoProvider: StorageInfoProvider,
	): DeviceContextCollector = AndroidDeviceContextCollector(
		context = context,
		locationProvider = locationProvider,
		addressProvider = addressProvider,
		storageInfoProvider = storageInfoProvider,
	)

	@Provides
	@Singleton
	fun providesDeviceContextSession(
		collector: DeviceContextCollector,
		settingsRepo: DeviceContextSettingsRepository,
		transcriptStore: TranscriptStore,
	): DeviceContextSession = DeviceContextSession(
		collector = collector,
		settingsRepo = settingsRepo,
		transcriptStore = transcriptStore,
	)
}
