package com.eva.drive.data.network;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class GoogleDriveClientFactory_Factory implements Factory<GoogleDriveClientFactory> {
  @Override
  public GoogleDriveClientFactory get() {
    return newInstance();
  }

  public static GoogleDriveClientFactory_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static GoogleDriveClientFactory newInstance() {
    return new GoogleDriveClientFactory();
  }

  private static final class InstanceHolder {
    static final GoogleDriveClientFactory_Factory INSTANCE = new GoogleDriveClientFactory_Factory();
  }
}
