package com.eva.drive.data.preferences;

import com.eva.drive.domain.provider.RecordingUploadTask;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata
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
public final class RecordingAutoUploader_Factory implements Factory<RecordingAutoUploader> {
  private final Provider<RecordingUploadTask> uploadTaskProvider;

  private RecordingAutoUploader_Factory(Provider<RecordingUploadTask> uploadTaskProvider) {
    this.uploadTaskProvider = uploadTaskProvider;
  }

  @Override
  public RecordingAutoUploader get() {
    return newInstance(uploadTaskProvider.get());
  }

  public static RecordingAutoUploader_Factory create(
      Provider<RecordingUploadTask> uploadTaskProvider) {
    return new RecordingAutoUploader_Factory(uploadTaskProvider);
  }

  public static RecordingAutoUploader newInstance(RecordingUploadTask uploadTask) {
    return new RecordingAutoUploader(uploadTask);
  }
}
