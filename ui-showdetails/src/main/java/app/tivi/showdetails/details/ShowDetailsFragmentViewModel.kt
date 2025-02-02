/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.showdetails.details

import androidx.lifecycle.viewModelScope
import app.tivi.TiviMvRxViewModel
import app.tivi.domain.interactors.ChangeSeasonFollowStatus
import app.tivi.domain.interactors.ChangeSeasonWatchedStatus
import app.tivi.domain.interactors.ChangeSeasonWatchedStatus.Action
import app.tivi.domain.interactors.ChangeSeasonWatchedStatus.Params
import app.tivi.domain.interactors.ChangeShowFollowStatus
import app.tivi.domain.interactors.ChangeShowFollowStatus.Action.TOGGLE
import app.tivi.domain.interactors.GetEpisodeDetails
import app.tivi.domain.interactors.UpdateRelatedShows
import app.tivi.domain.interactors.UpdateShowDetails
import app.tivi.domain.interactors.UpdateShowSeasonData
import app.tivi.domain.launchObserve
import app.tivi.domain.observers.ObserveRelatedShows
import app.tivi.domain.observers.ObserveShowDetails
import app.tivi.domain.observers.ObserveShowFollowStatus
import app.tivi.domain.observers.ObserveShowNextEpisodeToWatch
import app.tivi.domain.observers.ObserveShowSeasonData
import app.tivi.domain.observers.ObserveShowViewStats
import app.tivi.util.ObservableLoadingCounter
import app.tivi.util.collectFrom
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.ViewModelContext
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal class ShowDetailsFragmentViewModel @AssistedInject constructor(
    @Assisted initialState: ShowDetailsViewState,
    private val updateShowDetails: UpdateShowDetails,
    observeShowDetails: ObserveShowDetails,
    private val updateRelatedShows: UpdateRelatedShows,
    observeRelatedShows: ObserveRelatedShows,
    private val updateShowSeasons: UpdateShowSeasonData,
    observeShowSeasons: ObserveShowSeasonData,
    private val changeSeasonWatchedStatus: ChangeSeasonWatchedStatus,
    observeShowFollowStatus: ObserveShowFollowStatus,
    observeNextEpisodeToWatch: ObserveShowNextEpisodeToWatch,
    observeShowViewStats: ObserveShowViewStats,
    private val changeShowFollowStatus: ChangeShowFollowStatus,
    private val changeSeasonFollowStatus: ChangeSeasonFollowStatus,
    private val getEpisode: GetEpisodeDetails
) : TiviMvRxViewModel<ShowDetailsViewState>(initialState) {

    private val loadingState = ObservableLoadingCounter()

    private val pendingActions = Channel<ShowDetailsAction>(Channel.BUFFERED)

    init {
        viewModelScope.launchObserve(observeShowFollowStatus) { flow ->
            flow.distinctUntilChanged().execute {
                copy(isFollowed = if (it is Success) it() else false)
            }
        }

        viewModelScope.launchObserve(observeShowDetails) { flow ->
            flow.distinctUntilChanged().execute {
                if (it is Success) {
                    val value = it()
                    copy(show = value.show, posterImage = value.poster, backdropImage = value.backdrop)
                } else {
                    this
                }
            }
        }

        viewModelScope.launch {
            loadingState.observable.collect { setState { copy(refreshing = it) } }
        }

        viewModelScope.launchObserve(observeRelatedShows) { flow ->
            flow.distinctUntilChanged().execute { copy(relatedShows = it) }
        }

        viewModelScope.launchObserve(observeNextEpisodeToWatch) { flow ->
            flow.distinctUntilChanged().execute { copy(nextEpisodeToWatch = it) }
        }

        viewModelScope.launchObserve(observeShowSeasons) { flow ->
            flow.distinctUntilChanged().execute { copy(seasons = it) }
        }

        viewModelScope.launchObserve(observeShowViewStats) { flow ->
            flow.distinctUntilChanged().execute { copy(viewStats = it) }
        }

        viewModelScope.launch {
            for (action in pendingActions) when (action) {
                is RefreshAction -> refresh(true)
                FollowShowToggleAction -> onToggleMyShowsButtonClicked()
                is MarkSeasonWatchedAction -> onMarkSeasonWatched(action)
                is MarkSeasonUnwatchedAction -> onMarkSeasonUnwatched(action)
                is ChangeSeasonFollowedAction -> onChangeSeasonFollowState(action)
                is ChangeSeasonExpandedAction -> onChangeSeasonExpandState(action)
                is UnfollowPreviousSeasonsFollowedAction -> onUnfollowPreviousSeasonsFollowState(action)
                is OpenEpisodeDetails -> openEpisodeDetails(action)
            }
        }

        withState { state ->
            observeShowFollowStatus(ObserveShowFollowStatus.Params(state.showId))
            observeShowDetails(ObserveShowDetails.Params(state.showId))
            observeRelatedShows(ObserveRelatedShows.Params(state.showId))
            observeShowSeasons(ObserveShowSeasonData.Params(state.showId))
            observeNextEpisodeToWatch(ObserveShowNextEpisodeToWatch.Params(state.showId))
            observeShowViewStats(ObserveShowViewStats.Params(state.showId))

            if (state.openEpisodeUiEffect is PendingOpenEpisodeUiEffect) {
                openEpisodeDetails(OpenEpisodeDetails(state.openEpisodeUiEffect.episodeId))
            }
        }

        refresh(false)
    }

    private fun refresh(fromUserInteraction: Boolean) = withState {
        updateShowDetails(UpdateShowDetails.Params(it.showId, fromUserInteraction)).also {
            viewModelScope.launch {
                loadingState.collectFrom(it)
            }
        }
        updateRelatedShows(UpdateRelatedShows.Params(it.showId, fromUserInteraction)).also {
            viewModelScope.launch {
                loadingState.collectFrom(it)
            }
        }
        updateShowSeasons(UpdateShowSeasonData.Params(it.showId, fromUserInteraction)).also {
            viewModelScope.launch {
                loadingState.collectFrom(it)
            }
        }
    }

    fun submitAction(action: ShowDetailsAction) {
        viewModelScope.launch { pendingActions.send(action) }
    }

    private fun onToggleMyShowsButtonClicked() = withState {
        changeShowFollowStatus(ChangeShowFollowStatus.Params(it.showId, TOGGLE)).also {
            viewModelScope.launch {
                loadingState.collectFrom(it)
            }
        }
    }

    private fun openEpisodeDetails(action: OpenEpisodeDetails) {
        viewModelScope.launch {
            val episode = getEpisode(GetEpisodeDetails.Params(action.episodeId))
            if (episode != null) {
                setState {
                    copy(expandedSeasonIds = expandedSeasonIds + episode.seasonId,
                            openEpisodeUiEffect = ExecutableOpenEpisodeUiEffect(action.episodeId, episode.seasonId))
                }
            }
        }
    }

    fun clearExpandedEpisode() {
        setState { copy(openEpisodeUiEffect = null) }
    }

    private fun onMarkSeasonWatched(action: MarkSeasonWatchedAction) {
        changeSeasonWatchedStatus(Params(action.seasonId, Action.WATCHED, action.onlyAired, action.date))
    }

    private fun onMarkSeasonUnwatched(action: MarkSeasonUnwatchedAction) {
        changeSeasonWatchedStatus(Params(action.seasonId, Action.UNWATCH))
    }

    private fun onChangeSeasonExpandState(action: ChangeSeasonExpandedAction) {
        if (action.expanded) {
            setState {
                copy(focusedSeason = FocusSeasonUiEffect(action.seasonId),
                        expandedSeasonIds = expandedSeasonIds + action.seasonId)
            }
        } else {
            setState {
                copy(expandedSeasonIds = expandedSeasonIds - action.seasonId)
            }
        }
    }

    fun clearFocusedSeason() {
        setState { copy(focusedSeason = null) }
    }

    private fun onChangeSeasonFollowState(action: ChangeSeasonFollowedAction) {
        changeSeasonFollowStatus(ChangeSeasonFollowStatus.Params(
                action.seasonId,
                when {
                    action.followed -> ChangeSeasonFollowStatus.Action.FOLLOW
                    else -> ChangeSeasonFollowStatus.Action.IGNORE
                }
        ))
    }

    private fun onUnfollowPreviousSeasonsFollowState(action: UnfollowPreviousSeasonsFollowedAction) {
        changeSeasonFollowStatus(
                ChangeSeasonFollowStatus.Params(action.seasonId,
                        ChangeSeasonFollowStatus.Action.IGNORE_PREVIOUS)
        )
    }

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: ShowDetailsViewState): ShowDetailsFragmentViewModel
    }

    companion object : MvRxViewModelFactory<ShowDetailsFragmentViewModel, ShowDetailsViewState> {
        override fun create(
            viewModelContext: ViewModelContext,
            state: ShowDetailsViewState
        ): ShowDetailsFragmentViewModel? {
            val f: ShowDetailsFragment = (viewModelContext as FragmentViewModelContext).fragment()
            return f.showDetailsViewModelFactory.create(state).apply {
                val args = f.requireArguments()

                // If the fragment arguments contain an episode id, deep link into it
                if (args.containsKey("episode_id")) {
                    submitAction(OpenEpisodeDetails(args.getLong("episode_id")))
                }
            }
        }

        override fun initialState(
            viewModelContext: ViewModelContext
        ): ShowDetailsViewState? {
            val f: ShowDetailsFragment = (viewModelContext as FragmentViewModelContext).fragment()
            val args = f.requireArguments()
            return ShowDetailsViewState(showId = args.getLong("show_id"))
        }
    }
}
