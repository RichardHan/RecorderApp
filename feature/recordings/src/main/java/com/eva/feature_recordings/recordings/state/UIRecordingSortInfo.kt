package com.eva.feature_recordings.recordings.state

import com.eva.datastore.domain.enums.RecordingSortOrder

internal data class UIRecordingSortInfo(
	val identifier: UISortIdentifier = UISortIdentifier.DATE_CREATED,
	val sortDirection: UISortOrder = UISortOrder.DESC
)

internal fun RecordingSortOrder.toUIOrder() = when (this) {
	RecordingSortOrder.DATE_CREATED -> UISortIdentifier.DATE_CREATED
	RecordingSortOrder.DURATION -> UISortIdentifier.DURATION
	RecordingSortOrder.NAME -> UISortIdentifier.NAME
	RecordingSortOrder.SIZE -> UISortIdentifier.SIZE
}