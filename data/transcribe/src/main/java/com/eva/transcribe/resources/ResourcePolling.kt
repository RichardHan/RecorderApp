package com.eva.transcribe.resources

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Called only inside repeatOnLifecycle(STARTED). No detached scope: stopping cancels read/delay immediately. */
suspend fun pollResources(read: suspend () -> ResourceSnapshot, emit: (ResourceSnapshot) -> Unit) {
    while (currentCoroutineContext().isActive) {
        emit(read())
        delay(5_000)
    }
}
