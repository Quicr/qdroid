// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid

import android.app.Application
import com.meta.wearable.dat.core.Wearables
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class QuadroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Wearables.initialize(this)
    }
}
