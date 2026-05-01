// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid

import androidx.lifecycle.LifecycleOwner
import app.cash.turbine.test
import com.cisco.quadroid.mediacodec.VideoSessionManager
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: MainViewModel
    private val videoSessionManager: VideoSessionManager = mockk(relaxed = true)
    private val lifecycleOwner: LifecycleOwner = mockk()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = MainViewModel(videoSessionManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Lobby`() = runTest {
        assertEquals(CallUiState.Lobby, viewModel.uiState.value)
    }

    @Test
    fun `startCall transitions to InCall and calls startSession`() = runTest {
        viewModel.uiState.test {
            assertEquals(CallUiState.Lobby, awaitItem())
            viewModel.startCall(lifecycleOwner, 0)
            assertEquals(CallUiState.InCall, awaitItem())
            verify { videoSessionManager.startSession(lifecycleOwner, 0, any()) }
        }
    }

    @Test
    fun `endCall transitions back to Lobby and stops session`() = runTest {
        viewModel.startCall(lifecycleOwner, 0)
        viewModel.uiState.test {
            assertEquals(CallUiState.InCall, awaitItem())
            viewModel.endCall()
            assertEquals(CallUiState.Lobby, awaitItem())
            verify { videoSessionManager.stopSession() }
        }
    }
}
