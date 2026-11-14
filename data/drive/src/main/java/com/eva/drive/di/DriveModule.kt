package com.eva.drive.di

import com.eva.drive.data.auth.GoogleDriveAuthImpl
import com.eva.drive.data.network.AndroidDriveNetworkChecker
import com.eva.drive.data.network.DriveUploaderImpl
import com.eva.drive.data.network.GoogleDriveClientFactory
import com.eva.drive.data.preferences.DriveSettingsRepositoryImpl
import com.eva.drive.data.repository.RecordingUploadTaskImpl
import com.eva.drive.domain.auth.GoogleDriveAuth
import com.eva.drive.domain.provider.DriveClientFactory
import com.eva.drive.domain.provider.DriveNetworkChecker
import com.eva.drive.domain.provider.DriveUploader
import com.eva.drive.domain.provider.RecordingUploadTask
import com.eva.drive.domain.repository.DriveSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DriveModule {
	@Binds
	@Singleton
	abstract fun bindAuth(impl: GoogleDriveAuthImpl): GoogleDriveAuth

	@Binds
	@Singleton
	abstract fun bindSettings(impl: DriveSettingsRepositoryImpl): DriveSettingsRepository

	@Binds
	@Singleton
	abstract fun bindClientFactory(impl: GoogleDriveClientFactory): DriveClientFactory

	@Binds
	@Singleton
	abstract fun bindUploader(impl: DriveUploaderImpl): DriveUploader

	@Binds
	@Singleton
	abstract fun bindTask(impl: RecordingUploadTaskImpl): RecordingUploadTask

	@Binds
	@Singleton
	abstract fun bindNetworkChecker(impl: AndroidDriveNetworkChecker): DriveNetworkChecker
}
