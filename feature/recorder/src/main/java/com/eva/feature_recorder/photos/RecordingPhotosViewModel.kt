package com.eva.feature_recorder.photos

import androidx.lifecycle.ViewModel
import com.eva.recordings.domain.provider.RecordingPhotos
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RecordingPhotosViewModel @Inject constructor(val photos: RecordingPhotos) : ViewModel()
