// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.cisco.quadroid.ui.theme.QuadroidTheme
import com.cisco.quadroid.util.PreferencesManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests for the Settings "Top-N" section (toggle + 1-5 stepper).
 * Initial preference state is controlled via mockkObject so each state renders deterministically.
 */
class SettingsScreenTopNTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val viewModel: MainViewModel = mockk(relaxed = true)

    @Before
    fun setup() {
        mockkObject(PreferencesManager)
    }

    @After
    fun tearDown() {
        unmockkObject(PreferencesManager)
    }

    private fun renderSettings() {
        composeTestRule.setContent {
            QuadroidTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    relayUrl = "moq://relay.example.com:443",
                    onRelayUrlChange = {},
                    onSave = {}
                )
            }
        }
    }

    @Test
    fun topNSection_isDisplayed() {
        every { PreferencesManager.getTopNEnabled(any()) } returns false
        every { PreferencesManager.getTopNCount(any()) } returns 2

        renderSettings()

        composeTestRule.onNodeWithText("Top-N").assertIsDisplayed()
        composeTestRule.onNodeWithText("Active Speaker Top-N").assertIsDisplayed()
    }

    @Test
    fun stepper_hidden_whenDisabled() {
        every { PreferencesManager.getTopNEnabled(any()) } returns false
        every { PreferencesManager.getTopNCount(any()) } returns 2

        renderSettings()

        composeTestRule.onNodeWithText("Active Speakers").assertDoesNotExist()
    }

    @Test
    fun stepper_shown_withDefaultCount_whenEnabled() {
        every { PreferencesManager.getTopNEnabled(any()) } returns true
        every { PreferencesManager.getTopNCount(any()) } returns 2

        renderSettings()

        composeTestRule.onNodeWithText("Active Speakers").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
    }
}
