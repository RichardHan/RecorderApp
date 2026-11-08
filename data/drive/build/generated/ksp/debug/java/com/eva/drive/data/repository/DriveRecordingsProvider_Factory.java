package com.eva.drive.data.repository;

import android.content.Context;
import com.eva.recordings.domain.provider.VoiceRecordingsProvider;
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
public final class DriveRecordingsProvider_Factory implements Factory<DriveRecordingsProvider> {
  private final Provider<Context> contextProvider;

  private final Provider<VoiceRecordingsProvider> recordingsProvider;

  private DriveRecordingsProvider_Factory(Provider<Context> contextProvider,
      Provider<VoiceRecordingsProvider> recordingsProvider) {
    this.contextProvider = contextProvider;
    this.recordingsProvider = recordingsProvider;
  }

  @Override
  public DriveRecordingsProvider get() {
    return newInstance(contextProvider.get(), recordingsProvider.get());
  }

  public static DriveRecordingsProvider_Factory create(Provider<Context> contextProvider,
      Provider<VoiceRecordingsProvider> recordingsProvider) {
    return new DriveRecordingsProvider_Factory(contextProvider, recordingsProvider);
  }

  public static DriveRecordingsProvider newInstance(Context context,
      VoiceRecordingsProvider recordingsProvider) {
    return new DriveRecordingsProvider(context, recordingsProvider);
  }
}
