package com.eva.drive.worker;

import android.content.Context;
import androidx.work.WorkerParameters;
import com.eva.drive.data.preferences.RecordingAutoUploader;
import com.eva.drive.domain.auth.GoogleDriveAuth;
import dagger.internal.DaggerGenerated;
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
public final class DriveUploadWorker_Factory {
  private final Provider<GoogleDriveAuth> authProvider;

  private final Provider<RecordingAutoUploader> uploadTaskProvider;

  private DriveUploadWorker_Factory(Provider<GoogleDriveAuth> authProvider,
      Provider<RecordingAutoUploader> uploadTaskProvider) {
    this.authProvider = authProvider;
    this.uploadTaskProvider = uploadTaskProvider;
  }

  public DriveUploadWorker get(Context context, WorkerParameters params) {
    return newInstance(context, params, authProvider.get(), uploadTaskProvider.get());
  }

  public static DriveUploadWorker_Factory create(Provider<GoogleDriveAuth> authProvider,
      Provider<RecordingAutoUploader> uploadTaskProvider) {
    return new DriveUploadWorker_Factory(authProvider, uploadTaskProvider);
  }

  public static DriveUploadWorker newInstance(Context context, WorkerParameters params,
      GoogleDriveAuth auth, RecordingAutoUploader uploadTask) {
    return new DriveUploadWorker(context, params, auth, uploadTask);
  }
}
