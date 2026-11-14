package com.eva.drive.data.repository;

import android.content.Context;
import com.eva.database.dao.DriveUploadDao;
import com.eva.drive.domain.provider.DriveNetworkChecker;
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

  private final Provider<DriveNetworkChecker> networkCheckerProvider;

  private RecordingUploadTaskImpl_Factory(Provider<Context> contextProvider,
      Provider<DriveSettingsRepository> settingsRepositoryProvider,
      Provider<DriveRecordingsProvider> recordingsProvider,
      Provider<DriveUploadDao> uploadDaoProvider, Provider<DriveUploader> uploaderProvider,
      Provider<DriveNetworkChecker> networkCheckerProvider) {
    this.contextProvider = contextProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
    this.recordingsProvider = recordingsProvider;
    this.uploadDaoProvider = uploadDaoProvider;
    this.uploaderProvider = uploaderProvider;
    this.networkCheckerProvider = networkCheckerProvider;
  }

  @Override
  public RecordingUploadTaskImpl get() {
    return newInstance(contextProvider.get(), settingsRepositoryProvider.get(), recordingsProvider.get(), uploadDaoProvider.get(), uploaderProvider.get(), networkCheckerProvider.get());
  }

  public static RecordingUploadTaskImpl_Factory create(Provider<Context> contextProvider,
      Provider<DriveSettingsRepository> settingsRepositoryProvider,
      Provider<DriveRecordingsProvider> recordingsProvider,
      Provider<DriveUploadDao> uploadDaoProvider, Provider<DriveUploader> uploaderProvider,
      Provider<DriveNetworkChecker> networkCheckerProvider) {
    return new RecordingUploadTaskImpl_Factory(contextProvider, settingsRepositoryProvider, recordingsProvider, uploadDaoProvider, uploaderProvider, networkCheckerProvider);
  }

  public static RecordingUploadTaskImpl newInstance(Context context,
      DriveSettingsRepository settingsRepository, DriveRecordingsProvider recordingsProvider,
      DriveUploadDao uploadDao, DriveUploader uploader, DriveNetworkChecker networkChecker) {
    return new RecordingUploadTaskImpl(context, settingsRepository, recordingsProvider, uploadDao, uploader, networkChecker);
  }
}
