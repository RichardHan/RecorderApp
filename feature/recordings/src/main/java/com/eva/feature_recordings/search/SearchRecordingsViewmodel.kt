package com.eva.feature_recordings.search

import androidx.lifecycle.viewModelScope
import com.eva.categories.domain.models.RecordingCategoryModel
import com.eva.categories.domain.provider.RecordingCategoryProvider
import com.eva.feature_recordings.search.state.SearchFilterTimeOption
import com.eva.feature_recordings.search.state.SearchRecordingScreenEvent
import com.eva.feature_recordings.search.state.SearchRecordingScreenState
import com.eva.feature_recordings.search.transcript.TranscriptSearchRecording
import com.eva.feature_recordings.search.transcript.TranscriptSearchRepository
import com.eva.feature_recordings.search.transcript.TranscriptSearchResult
import com.eva.feature_recordings.search.transcript.TranscriptSearcher
import com.eva.feature_recordings.search.transcript.transcriptSearch
import com.eva.recordings.domain.models.RecordedVoiceModel
import com.eva.ui.viewmodel.AppViewModel
import com.eva.ui.viewmodel.UIEvents
import com.eva.use_case.usecases.RecordingsFromCategoriesUseCase
import com.eva.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

@HiltViewModel
internal class SearchRecordingsViewmodel @Inject constructor(
	private val categoriesProvider: RecordingCategoryProvider,
	private val recordingsUseCase: RecordingsFromCategoriesUseCase,
	private val transcriptSearch: TranscriptSearchRepository,
) : AppViewModel() {

	private val _categories = MutableStateFlow(emptyList<RecordingCategoryModel>())
	private val _recordings = MutableStateFlow(emptyList<RecordedVoiceModel>())

	private val _categoryFilter = MutableStateFlow<RecordingCategoryModel?>(null)
	private val _timeFilter = MutableStateFlow<SearchFilterTimeOption?>(null)
	private val _searchQuery = MutableStateFlow("")

	private val searcher = TranscriptSearcher()

	/** Every recording (name for display, id for the store); filled once, unaffected by the category/time filters. */
	private val _transcriptSearchScope = MutableStateFlow(emptyList<TranscriptSearchRecording>())

	@OptIn(FlowPreview::class)
	private val query: Flow<String>
		get() = _searchQuery.debounce(200.milliseconds)

	val searchState = combine(
		_searchQuery, _categoryFilter, _timeFilter, transform = ::SearchRecordingScreenState
	).stateIn(
		scope = viewModelScope,
		started = SharingStarted.Eagerly,
		initialValue = SearchRecordingScreenState()
	)

	val recordings = combine(_recordings, query, _timeFilter, transform = ::filterSearch)
		.map { it.toImmutableList() }
		.onStart { populateRecordings() }
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = persistentListOf()
		)

	/**
	 * On-device search over each recording's default transcript. Debounced, latest-wins, and the store walk happens on
	 * [Dispatchers.IO]; nothing here calls a network service.
	 */
	@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
	val transcriptResults: Flow<TranscriptSearchResult> = _searchQuery.transcriptSearch(
		searcher = searcher,
		recordings = { _transcriptSearchScope.value },
		lines = { recordingId -> transcriptSearch.lines(recordingId) },
		dispatcher = Dispatchers.IO,
	).stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(5_000),
		initialValue = TranscriptSearchResult(),
	)

	val categories = _categories
		.map { categories -> categories.toImmutableList() }
		.onStart { populateRecordingCategories() }
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = persistentListOf()
		)

	init {
		populateTranscriptSearchScope()
	}

	private val _uiEvents = MutableSharedFlow<UIEvents>()

	override val uiEvent: SharedFlow<UIEvents>
		get() = _uiEvents.asSharedFlow()


	fun onEvent(event: SearchRecordingScreenEvent) {
		when (event) {
			is SearchRecordingScreenEvent.OnCategorySelected -> {
				val category = if (event.category != _categoryFilter.value) event.category else null
				_categoryFilter.update { category }
			}

			is SearchRecordingScreenEvent.OnSelectTimeFilter -> {
				val timeRange = if (event.filter != _timeFilter.value) event.filter else null
				_timeFilter.update { timeRange }
			}

			is SearchRecordingScreenEvent.OnQueryChange -> _searchQuery.update { event.text }
			is SearchRecordingScreenEvent.OnVoiceSearchResults -> {
				if (event.results.isEmpty()) return
				_searchQuery.update { event.results.first() }
			}
		}
	}


	private fun populateTranscriptSearchScope() {
		recordingsUseCase(RecordingCategoryModel.ALL_CATEGORY)
			.onEach { resource ->
				if (resource is Resource.Success) {
					_transcriptSearchScope.update { resource.data.map { TranscriptSearchRecording(it.id, it.displayName) } }
				}
			}.launchIn(viewModelScope)
	}

	private fun populateRecordingCategories() {
		categoriesProvider.recordingCategoryAsResourceFlow.onEach { res ->
			when (res) {
				is Resource.Error -> {
					val message = res.message ?: res.error.message ?: "SOME ERROR"
					_uiEvents.emit(UIEvents.ShowSnackBar(message = message))
				}

				is Resource.Success -> _categories.update { res.data }
				else -> {}
			}
		}.launchIn(viewModelScope)
	}


	@OptIn(ExperimentalCoroutinesApi::class)
	private fun populateRecordings() {
		val recordingsFlow = _categoryFilter.flatMapLatest { model ->
			val category = model ?: RecordingCategoryModel.ALL_CATEGORY
			recordingsUseCase.invoke(category)
		}
		recordingsFlow.onEach { resource ->
			when (resource) {
				is Resource.Success -> _recordings.update { resource.data }
				else -> {}
			}
		}.launchIn(viewModelScope)
	}

	@OptIn(ExperimentalTime::class)
	private fun filterSearch(
		recordings: List<RecordedVoiceModel>,
		query: String,
		timeFilter: SearchFilterTimeOption? = null,
	): List<RecordedVoiceModel> {
		// if all is none then it's empty list
		if (query.isEmpty() && timeFilter == null) return emptyList()

		val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
		val yesterday = today.minus(DatePeriod(days = 1))
		val lastMonth = today.minus(DatePeriod(months = 1))

		return recordings.filter { model ->
			val recordedAt = model.recordedAt.date

			val filter = when (timeFilter) {
				SearchFilterTimeOption.TODAY -> recordedAt == today
				SearchFilterTimeOption.YESTERDAY -> recordedAt == yesterday
				SearchFilterTimeOption.WEEK -> with(recordedAt) {
					val startOfWeek = today.minus(DatePeriod(days = dayOfWeek.isoDayNumber))
					val endOfWeek = today.plus(DatePeriod(days = 7 - dayOfWeek.isoDayNumber))
					recordedAt in startOfWeek..endOfWeek
				}

				SearchFilterTimeOption.THIS_MONTH -> recordedAt.month == today.month
				SearchFilterTimeOption.LAST_MONTH -> recordedAt.month == lastMonth.month
				SearchFilterTimeOption.THIS_YEAR -> recordedAt.year == today.year
				null -> true
			}

			val searchText = model.displayName.lowercase()
			val queryLowerCase = query.lowercase()
			val isMatching =
				searchText.split("\\s+".toRegex()).any { word -> queryLowerCase in word }

			filter && isMatching
		}
	}
}