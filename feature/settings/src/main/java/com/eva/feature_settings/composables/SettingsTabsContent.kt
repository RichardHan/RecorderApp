package com.eva.feature_settings.composables

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eva.feature_settings.utils.SettingsTabs
import com.eva.ui.R
import kotlinx.coroutines.launch

@Composable
internal fun SettingsTabContent(
    audioSettings: @Composable () -> Unit,
    filesSettings: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    transcriptionSettings: @Composable () -> Unit = {},
	cloudSettings: @Composable () -> Unit = {},
	aiSettings: @Composable () -> Unit = {},
    initialTab: SettingsTabs = SettingsTabs.AUDIO_SETTINGS,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = initialTab.tabIndex,
        pageCount = { SettingsTabs.entries.size },
    )

    val selectedTabIndex by remember(pagerState) {
        derivedStateOf(pagerState::currentPage)
    }

    Column(
        modifier = modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 5 fixed equal-width tabs, all visible at once (short single-line labels; full name for accessibility)
        PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
            SettingsTabs.entries.forEach { tab ->
                val fullName = tab.textRes
                Tab(
                    selected = tab.tabIndex == selectedTabIndex,
                    onClick = {
                        val index = tab.tabIndex
                        if (index != selectedTabIndex)
                            scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    modifier = Modifier.semantics { contentDescription = fullName },
                    text = {
                        Text(
                            text = tab.shortLabel,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapPositionalThreshold = .4f,
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            ),
            pageSpacing = dimensionResource(id = R.dimen.sc_padding_secondary),
            modifier = Modifier.fillMaxSize(),
        ) { idx ->
            when (idx) {
                SettingsTabs.AUDIO_SETTINGS.tabIndex -> audioSettings()
                SettingsTabs.FILES_SETTINGS.tabIndex -> filesSettings()
                SettingsTabs.TRANSCRIPTIONS_SETTINGS.tabIndex -> transcriptionSettings()
				SettingsTabs.CLOUD_SETTINGS.tabIndex -> cloudSettings()
				SettingsTabs.AI_SETTINGS.tabIndex -> aiSettings()
                else -> {}
            }
        }
    }
}
