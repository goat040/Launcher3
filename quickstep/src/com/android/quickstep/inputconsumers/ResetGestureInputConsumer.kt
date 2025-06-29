/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep.inputconsumers

import android.view.MotionEvent
import com.android.launcher3.taskbar.TaskbarActivityContext
import com.android.quickstep.InputConsumer
import com.android.quickstep.TaskAnimationManager
import java.util.function.Supplier

/** A NO_OP input consumer which also resets any pending gesture */
class ResetGestureInputConsumer(
    private val displayId: Int,
    private val taskAnimationManager: TaskAnimationManager,
    private val activityContextSupplier: Supplier<TaskbarActivityContext?>,
) : InputConsumer {
    override fun getType() = InputConsumer.TYPE_RESET_GESTURE

    override fun getDisplayId() = displayId

    override fun onMotionEvent(ev: MotionEvent) {
        if (
            ev.action == MotionEvent.ACTION_DOWN && taskAnimationManager.isRecentsAnimationRunning
        ) {
            val tac = activityContextSupplier.get()
            taskAnimationManager.finishRunningRecentsAnimation(
                /* toHome= */ tac != null && !tac.isInApp
            )
        }
    }
}
