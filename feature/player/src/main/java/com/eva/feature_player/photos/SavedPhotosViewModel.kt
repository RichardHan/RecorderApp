package com.eva.feature_player.photos

import androidx.lifecycle.ViewModel
import com.eva.recordings.domain.provider.RecordingPhotos
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SavedPhotosViewModel @Inject constructor(val photos: RecordingPhotos) : ViewModel()
