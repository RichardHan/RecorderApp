package com.eva.drive.data.auth;

import android.content.Context;
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
public final class GoogleDriveAuthImpl_Factory implements Factory<GoogleDriveAuthImpl> {
  private final Provider<Context> contextProvider;

  private final Provider<DriveSettingsRepository> settingsRepositoryProvider;

  private GoogleDriveAuthImpl_Factory(Provider<Context> contextProvider,
      Provider<DriveSettingsRepository> settingsRepositoryProvider) {
    this.contextProvider = contextProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
  }

  @Override
  public GoogleDriveAuthImpl get() {
    return newInstance(contextProvider.get(), settingsRepositoryProvider.get());
  }

  public static GoogleDriveAuthImpl_Factory create(Provider<Context> contextProvider,
      Provider<DriveSettingsRepository> settingsRepositoryProvider) {
    return new GoogleDriveAuthImpl_Factory(contextProvider, settingsRepositoryProvider);
  }

  public static GoogleDriveAuthImpl newInstance(Context context,
      DriveSettingsRepository settingsRepository) {
    return new GoogleDriveAuthImpl(context, settingsRepository);
  }
}
