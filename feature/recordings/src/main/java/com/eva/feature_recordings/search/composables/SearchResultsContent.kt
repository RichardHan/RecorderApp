package com.eva.feature_recordings.search.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.datasource.CollectionPreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.eva.feature_recordings.recordings.composables.RecordingCard
import com.eva.feature_recordings.search.transcript.TranscriptHit
import com.eva.feature_recordings.util.RecordingsPreviewFakes
import com.eva.recordings.domain.models.RecordedVoiceModel
import com.eva.ui.R
import com.eva.ui.theme.RecorderAppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

internal typealias RecordedVoiceModelsList = ImmutableList<RecordedVoiceModel>

/**
 * Search results: transcript lines that matched first (they answer "which meeting said it"), then the recordings whose
 * title matched. The empty state only appears when neither section has anything.
 */
@Composable
internal fun SearchResultsContent(
	searchResults: RecordedVoiceModelsList,
	transcriptHits: ImmutableList<TranscriptHit>,
	hitsTruncated: Boolean,
	onItemSelect: (RecordedVoiceModel) -> Unit,
	onHitSelect: (TranscriptHit) -> Unit,
	modifier: Modifier = Modifier,
	contentPadding: PaddingValues = PaddingValues(),
) {

	if (searchResults.isEmpty() && transcriptHits.isEmpty()) {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center
		) {
			Text(
				text = stringResource(R.string.no_search_results),
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.secondary
			)
		}
		return
	}

	val isLocalInspectionMode = LocalInspectionMode.current

	val recordingKeys: ((Int, RecordedVoiceModel) -> Any)? = remember(isLocalInspectionMode) {
		if (isLocalInspectionMode) null
		else { _, recording -> recording.id }
	}

	val hitKeys: ((Int, TranscriptHit) -> Any)? = remember(isLocalInspectionMode) {
		if (isLocalInspectionMode) null
		else { index, hit -> "${hit.recordingId}-${hit.startMs}-$index" }
	}

	LazyColumn(
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = modifier
	) {
		if (transcriptHits.isNotEmpty()) {
			item(key = "transcript-heading") {
				SectionHeading(stringResource(R.string.search_transcript_section))
			}
			itemsIndexed(
				items = transcriptHits,
				key = hitKeys,
				contentType = { _, _ -> "transcript-hit" }
			) { _, hit ->
				TranscriptHitRow(hit = hit, onClick = { onHitSelect(hit) })
			}
			if (hitsTruncated) {
				item(key = "transcript-truncated") {
					Text(
						text = stringResource(R.string.search_transcript_too_many),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			if (searchResults.isNotEmpty()) {
				item(key = "recordings-heading") {
					SectionHeading(stringResource(R.string.search_recordings_section))
				}
			}
		}
		itemsIndexed(
			items = searchResults,
			key = recordingKeys,
			contentType = { _, _ -> RecordedVoiceModel::class.simpleName }
		) { _, record ->
			RecordingCard(
				music = record,
				isSelected = false,
				isSelectable = false,
				onItemClick = { onItemSelect(record) },
				onItemSelect = { },
				modifier = Modifier
					.fillMaxWidth()
					.animateItem(),
			)
		}
	}
}

@Composable
private fun SectionHeading(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.titleSmall,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(top = 4.dp),
	)
}

@Composable
private fun TranscriptHitRow(hit: TranscriptHit, onClick: () -> Unit) {
	Surface(
		shape = MaterialTheme.shapes.medium,
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick),
	) {
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				text = hit.recordingName,
				style = MaterialTheme.typography.titleSmall,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = hit.text,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			hit.startMs?.let { startMs ->
				Text(
					text = formatHitTime(startMs),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

/** Only called for hits that carry a real timestamp; never invented for transcripts without one. */
internal fun formatHitTime(startMs: Long): String {
	val seconds = startMs / 1000
	return "%d:%02d".format(seconds / 60, seconds % 60)
}

private class SearchResultsPreviewParams :
	CollectionPreviewParameterProvider<RecordedVoiceModelsList>(
		listOf(
			persistentListOf(),
			RecordingsPreviewFakes.FAKE_VOICE_RECORDINGS_SELECTED
				.map { it.recording }
				.toImmutableList()
		)
	)

@PreviewLightDark
@Composable
private fun SearchResultsContentPreview(
	@PreviewParameter(SearchResultsPreviewParams::class)
	searchResults: RecordedVoiceModelsList,
) = RecorderAppTheme {
	Surface {
		SearchResultsContent(
			searchResults = searchResults,
			transcriptHits = persistentListOf(
				TranscriptHit(1L, "Weekly sync", "下週三前完成報價單", 192_000L),
				TranscriptHit(2L, "Offline note", "沒有時間戳的逐字稿內容", null),
			),
			hitsTruncated = true,
			onItemSelect = {},
			onHitSelect = {},
			contentPadding = PaddingValues(
				horizontal = dimensionResource(R.dimen.sc_padding),
				vertical = dimensionResource(R.dimen.sc_padding_secondary)
			)
		)
	}
}
