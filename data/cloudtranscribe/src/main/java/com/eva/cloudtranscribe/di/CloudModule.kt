package com.eva.cloudtranscribe.di

import android.content.Context
import com.eva.cloudtranscribe.store.TranscriptStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CloudModule {
	@Provides
	@Singleton
	fun store(@ApplicationContext context: Context): TranscriptStore = TranscriptStore(File(context.filesDir, "ai_transcripts"))
}
