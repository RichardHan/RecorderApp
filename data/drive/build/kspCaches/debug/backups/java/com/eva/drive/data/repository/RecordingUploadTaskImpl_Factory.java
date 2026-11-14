package com.eva.drive.data.repository;

import android.content.Context;
import com.eva.database.dao.DriveUploadDao;
import com.eva.drive.domain.provider.DriveUploader;
import com.eva.drive.domain.repository.DriveSettingsRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class RecordingUploadTaskImpl_Factory implements Factory<RecordingUploadTaskImpl> {
  private final Provider<Context> contextProvider;

  private final Provider<DriveSettingsRepository> settingsRepositoryProvider;

  private final Provider<DriveRecordingsProvider> recordingsProvider;

  private final Provider<DriveUploadDao> uploadDaoProvider;

  private final Provider<DriveUploader> uploaderProvider;

  private RecordingUploadTaskImpl_Factory(Provider<Context> contextProvider,
      Provider<DriveSettingsRepository> settingsRepositoryProvider,
      Provider<DriveRecordingsProvider> recordingsProvider,
      Provider<DriveUploadDao> uploadDaoProvider, Provider<DriveUploader> uploaderProvider) {
    this.contextProvider = contextProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.recordingsProvider = recordingsProvider;
    this.uploadDaoProvider = uploadDaoProvider;
    this.uploaderProvider = uploaderProvider;
  }

  @Override
  public RecordingUploadTaskImpl get() {
    return newInstance(contextProvider.get(), settingsRepositoryProvider.get(), recordingsProvider.get(), uploadDaoProvider.get(), uploaderProvider.get());
  }

  public static RecordingUploadTaskImpl_Factory create(Provider<Context> contextProvider,
      Provider<DriveSettingsRepository> settingsRepositoryProvider,
      Provider<DriveRecordingsProvider> recordingsProvider,
      Provider<DriveUploadDao> uploadDaoProvider, Provider<DriveUploader> uploaderProvider) {
    return new RecordingUploadTaskImpl_Factory(contextProvider, settingsRepositoryProvider, recordingsProvider, uploadDaoProvider, uploaderProvider);
  }

  public static RecordingUploadTaskImpl newInstance(Context context,
      DriveSettingsRepository settingsRepository, DriveRecordingsProvider recordingsProvider,
      DriveUploadDao uploadDao, DriveUploader uploader) {
    return new RecordingUploadTaskImpl(context, settingsRepository, recordingsProvider, uploadDao, uploader);
  }
}
