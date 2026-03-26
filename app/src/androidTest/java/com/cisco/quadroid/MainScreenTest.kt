package com.cisco.quadroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cisco.quadroid.ui.theme.QuadroidTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.webrtc.VideoTrack

class MainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val viewModel: MainViewModel = mockk(relaxed = true)

    @Test
    fun lobbyState_showsStartCallButton() {
        every { viewModel.uiState } returns MutableStateFlow(CallUiState.Lobby)
        every { viewModel.localVideoTrack } returns MutableStateFlow(null)
        every { viewModel.remoteVideoTrack } returns MutableStateFlow(null)

        composeTestRule.setContent {
            QuadroidTheme {
                MainScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithText("Welcome to Quadroid").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start Call").assertIsDisplayed()
    }

    @Test
    fun inCallState_showsEndCallButton() {
        every { viewModel.uiState } returns MutableStateFlow(CallUiState.InCall)
        every { viewModel.localVideoTrack } returns MutableStateFlow(null)
        every { viewModel.remoteVideoTrack } returns MutableStateFlow(null)

        composeTestRule.setContent {
            QuadroidTheme {
                MainScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithText("End Call").assertIsDisplayed()
    }
}
