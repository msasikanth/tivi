/*
 * Copyright 2017 Google LLC
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

package app.tivi.util

import androidx.lifecycle.viewModelScope
import androidx.paging.PagedList
import app.tivi.TiviMvRxViewModel
import app.tivi.api.UiError
import app.tivi.api.UiIdle
import app.tivi.api.UiLoading
import app.tivi.api.UiStatus
import app.tivi.api.UiSuccess
import app.tivi.base.InvokeError
import app.tivi.base.InvokeStarted
import app.tivi.base.InvokeStatus
import app.tivi.base.InvokeSuccess
import app.tivi.base.InvokeTimeout
import app.tivi.data.Entry
import app.tivi.data.entities.TiviShow
import app.tivi.data.resultentities.EntryWithShow
import app.tivi.domain.PagingInteractor
import app.tivi.domain.interactors.ChangeShowFollowStatus
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

abstract class EntryViewModel<LI : EntryWithShow<out Entry>, PI : PagingInteractor<*, LI>>(
    initialState: EntryViewState,
    private val pageSize: Int = 21
) : TiviMvRxViewModel<EntryViewState>(initialState) {
    protected abstract val dispatchers: AppCoroutineDispatchers
    protected abstract val pagingInteractor: PI
    protected abstract val logger: Logger
    protected abstract val changeShowFollowStatus: ChangeShowFollowStatus

    private val messages = ConflatedBroadcastChannel<UiStatus>(UiIdle)
    private val loaded = ConflatedBroadcastChannel(false)

    val pagedList: Flow<PagedList<LI>>
        get() = pagingInteractor.observe()

    private val showSelection = ShowStateSelector()

    protected val pageListConfig = PagedList.Config.Builder().run {
        setPageSize(pageSize * 3)
        setPrefetchDistance(pageSize)
        setEnablePlaceholders(false)
        build()
    }

    protected val boundaryCallback = object : PagedList.BoundaryCallback<LI>() {
        override fun onItemAtEndLoaded(itemAtEnd: LI) = onListScrolledToEnd()

        override fun onItemAtFrontLoaded(itemAtFront: LI) {
            loaded.offer(true)
        }

        override fun onZeroItemsLoaded() {
            loaded.offer(true)
        }
    }

    protected fun launchObserves() {
        viewModelScope.launch {
            messages.asFlow().execute {
                copy(status = it() ?: UiSuccess)
            }
        }

        viewModelScope.launch {
            loaded.asFlow().execute {
                copy(isLoaded = it() ?: false)
            }
        }

        viewModelScope.launch {
            showSelection.observeIsSelectionOpen().execute {
                copy(selectionOpen = it() ?: false)
            }
        }

        viewModelScope.launch {
            showSelection.observeSelectedShowIds().execute {
                copy(selectedShowIds = it() ?: emptySet())
            }
        }
    }

    fun onListScrolledToEnd() {
        callLoadMore().also {
            viewModelScope.launch {
                it.catch {
                    messages.send(UiError(it))
                }.map {
                    when (it) {
                        InvokeSuccess -> UiSuccess
                        InvokeStarted -> UiLoading(false)
                        is InvokeError -> UiError(it.throwable)
                        InvokeTimeout -> UiError()
                        else -> UiIdle
                    }
                }.collect {
                    messages.send(it)
                }
            }
        }
    }

    fun refresh() = refresh(true)

    fun clearSelection() {
        showSelection.clearSelection()
    }

    fun onItemClick(show: TiviShow): Boolean {
        return showSelection.onItemClick(show)
    }

    fun onItemLongClick(show: TiviShow): Boolean {
        return showSelection.onItemLongClick(show)
    }

    fun followSelectedShows() {
        changeShowFollowStatus(
                ChangeShowFollowStatus.Params(
                        showSelection.getSelectedShowIds(),
                        ChangeShowFollowStatus.Action.FOLLOW,
                        deferDataFetch = true
                )
        )
        showSelection.clearSelection()
    }

    protected fun refresh(fromUser: Boolean) {
        callRefresh(fromUser).also {
            viewModelScope.launch {
                it.catch {
                    messages.send(UiError(it))
                }.map {
                    when (it) {
                        InvokeSuccess -> UiSuccess
                        InvokeStarted -> UiLoading(true)
                        InvokeTimeout -> UiError()
                        else -> UiIdle
                    }
                }.collect {
                    messages.send(it)
                }
            }
        }
    }

    protected abstract fun callRefresh(fromUser: Boolean): Flow<InvokeStatus>

    protected abstract fun callLoadMore(): Flow<InvokeStatus>
}
