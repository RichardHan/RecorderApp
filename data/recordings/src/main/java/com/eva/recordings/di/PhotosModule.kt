package com.eva.recordings.di

import com.eva.recordings.data.photos.RecordingPhotosImpl
import com.eva.recordings.domain.provider.RecordingPhotos
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PhotosModule {
	@Binds abstract fun photos(impl: RecordingPhotosImpl): RecordingPhotos
}
