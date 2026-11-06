package com.eva.drive.data.network;

import com.eva.drive.domain.provider.DriveClientFactory;
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
public final class DriveUploaderImpl_Factory implements Factory<DriveUploaderImpl> {
  private final Provider<DriveClientFactory> clientFactoryProvider;

  private DriveUploaderImpl_Factory(Provider<DriveClientFactory> clientFactoryProvider) {
    this.clientFactoryProvider = clientFactoryProvider;
  }

  @Override
  public DriveUploaderImpl get() {
    return newInstance(clientFactoryProvider.get());
  }

  public static DriveUploaderImpl_Factory create(
      Provider<DriveClientFactory> clientFactoryProvider) {
    return new DriveUploaderImpl_Factory(clientFactoryProvider);
  }

  public static DriveUploaderImpl newInstance(DriveClientFactory clientFactory) {
    return new DriveUploaderImpl(clientFactory);
  }
}
