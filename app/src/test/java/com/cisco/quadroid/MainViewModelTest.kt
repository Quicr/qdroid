package com.cisco.quadroid

import app.cash.turbine.test
import com.cisco.quadroid.webrtc.WebRtcSessionManager
import io.mockk.every
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
    private val webRtcSessionManager: WebRtcSessionManager = mockk(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = MainViewModel(webRtcSessionManager)
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
    fun `startCall transitions to InCall and calls setupLocalStream`() = runTest {
        viewModel.uiState.test {
            assertEquals(CallUiState.Lobby, awaitItem())
            viewModel.startCall()
            assertEquals(CallUiState.InCall, awaitItem())
            verify { webRtcSessionManager.setupLocalStream() }
        }
    }

    @Test
    fun `endCall transitions back to Lobby and disconnects`() = runTest {
        viewModel.startCall()
        viewModel.uiState.test {
            assertEquals(CallUiState.InCall, awaitItem())
            viewModel.endCall()
            assertEquals(CallUiState.Lobby, awaitItem())
            verify { webRtcSessionManager.disconnect() }
        }
    }
}
