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

package app.tivi.tmdb

import app.tivi.extensions.fetchBodyWithRetry
import app.tivi.inject.ProcessLifetime
import app.tivi.util.AppCoroutineDispatchers
import com.uwetrottmann.tmdb2.Tmdb
import com.uwetrottmann.tmdb2.entities.Configuration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class TmdbManager @Inject constructor(
    private val dispatchers: AppCoroutineDispatchers,
    private val tmdbClient: Tmdb,
    @ProcessLifetime val processScope: CoroutineScope
) {
    private val imageProviderSubject = ConflatedBroadcastChannel(TmdbImageUrlProvider())
    val imageProviderFlow: Flow<TmdbImageUrlProvider> = imageProviderSubject.asFlow()

    fun getLatestImageProvider() = imageProviderSubject.value

    fun refreshConfiguration() {
        processScope.launch {
            try {
                val config = withContext(dispatchers.io) {
                    tmdbClient.configurationService().configuration().fetchBodyWithRetry()
                }
                onConfigurationLoaded(config)
            } catch (e: Exception) {
                // TODO
            }
        }
    }

    private fun onConfigurationLoaded(configuration: Configuration) {
        configuration.images?.let { images ->
            processScope.launch {
                val newProvider = TmdbImageUrlProvider(
                        images.secure_base_url!!,
                        images.poster_sizes ?: emptyList(),
                        images.backdrop_sizes ?: emptyList(),
                        images.logo_sizes ?: emptyList()
                )
                imageProviderSubject.send(newProvider)
            }
        }
    }
}
