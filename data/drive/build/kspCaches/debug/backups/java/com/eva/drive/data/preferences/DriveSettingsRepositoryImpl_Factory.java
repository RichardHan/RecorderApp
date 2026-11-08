package com.eva.drive.data.preferences;

import android.content.Context;
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
public final class DriveSettingsRepositoryImpl_Factory implements Factory<DriveSettingsRepositoryImpl> {
  private final Provider<Context> contextProvider;

  private DriveSettingsRepositoryImpl_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public DriveSettingsRepositoryImpl get() {
    return newInstance(contextProvider.get());
  }

  public static DriveSettingsRepositoryImpl_Factory create(Provider<Context> contextProvider) {
    return new DriveSettingsRepositoryImpl_Factory(contextProvider);
  }

  public static DriveSettingsRepositoryImpl newInstance(Context context) {
    return new DriveSettingsRepositoryImpl(context);
  }
}
