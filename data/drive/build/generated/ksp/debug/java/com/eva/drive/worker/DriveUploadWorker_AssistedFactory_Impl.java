package com.eva.drive.worker;

import android.content.Context;
import androidx.work.WorkerParameters;
import dagger.internal.DaggerGenerated;
import dagger.internal.InstanceFactory;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class DriveUploadWorker_AssistedFactory_Impl implements DriveUploadWorker_AssistedFactory {
  private final DriveUploadWorker_Factory delegateFactory;

  DriveUploadWorker_AssistedFactory_Impl(DriveUploadWorker_Factory delegateFactory) {
    this.delegateFactory = delegateFactory;
  }

  @Override
  public DriveUploadWorker create(Context p0, WorkerParameters p1) {
    return delegateFactory.get(p0, p1);
  }

  public static Provider<DriveUploadWorker_AssistedFactory> create(
      DriveUploadWorker_Factory delegateFactory) {
    return InstanceFactory.create(new DriveUploadWorker_AssistedFactory_Impl(delegateFactory));
  }

  public static dagger.internal.Provider<DriveUploadWorker_AssistedFactory> createFactoryProvider(
      DriveUploadWorker_Factory delegateFactory) {
    return InstanceFactory.create(new DriveUploadWorker_AssistedFactory_Impl(delegateFactory));
  }
}
