/*
 * Copyright 2019 Google LLC
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

package app.tivi.common.imageloading

import android.graphics.drawable.Drawable
import androidx.core.animation.doOnEnd
import app.tivi.ui.animations.SATURATION_ANIMATION_DURATION
import app.tivi.ui.animations.saturateDrawableAnimator
import coil.annotation.ExperimentalCoil
import coil.transition.Transition
import kotlinx.coroutines.suspendCancellableCoroutine

/** A [Transition] that saturates and fades in the new drawable on load */
@ExperimentalCoil
class SaturatingTransformation(
    private val durationMillis: Long = SATURATION_ANIMATION_DURATION
) : Transition {
    init {
        require(durationMillis > 0) { "durationMillis must be > 0." }
    }

    override suspend fun transition(
        adapter: Transition.Adapter,
        drawable: Drawable?
    ) {
        if (drawable != null) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val animator = saturateDrawableAnimator(drawable, durationMillis, adapter.view)
                animator.duration = durationMillis
                animator.doOnEnd {
                    continuation.resume(Unit) { animator.cancel() }
                }
                animator.start()

                continuation.invokeOnCancellation { animator.cancel() }
                adapter.drawable = drawable
            }
        } else {
            adapter.drawable = drawable
        }
    }

    object Factory : Transition.Factory {
        private val transition = SaturatingTransformation()

        override fun newTransition(event: Transition.Event): Transition? {
            return transition.takeIf { event != Transition.Event.CACHED }
        }
    }
}