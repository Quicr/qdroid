// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

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

class MainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val viewModel: MainViewModel = mockk(relaxed = true)

    @Test
    fun lobbyState_showsStartCallButton() {
        every { viewModel.uiState } returns MutableStateFlow(CallUiState.Lobby)


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


        composeTestRule.setContent {
            QuadroidTheme {
                MainScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithText("End Call").assertIsDisplayed()
    }
}
