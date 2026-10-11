// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.util

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class PreferencesManagerTest {

    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)
    private val context: Context = mockk()

    @Before
    fun setup() {
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
    }

    @Test
    fun `topN enabled defaults to false`() {
        every { prefs.getBoolean("topn_enabled", false) } returns false
        assertFalse(PreferencesManager.getTopNEnabled(context))
    }

    @Test
    fun `topN count defaults to two`() {
        // Return the default the production code passes through.
        every { prefs.getInt("topn_count", PreferencesManager.TOPN_DEFAULT_COUNT) } returns
            PreferencesManager.TOPN_DEFAULT_COUNT
        assertEquals(2, PreferencesManager.getTopNCount(context))
        assertEquals(2, PreferencesManager.TOPN_DEFAULT_COUNT)
    }

    @Test
    fun `getTopNCount clamps an out-of-range stored value`() {
        every { prefs.getInt("topn_count", PreferencesManager.TOPN_DEFAULT_COUNT) } returns 99
        assertEquals(PreferencesManager.TOPN_MAX_COUNT, PreferencesManager.getTopNCount(context))

        every { prefs.getInt("topn_count", PreferencesManager.TOPN_DEFAULT_COUNT) } returns 0
        assertEquals(PreferencesManager.TOPN_MIN_COUNT, PreferencesManager.getTopNCount(context))
    }

    @Test
    fun `setTopNCount clamps above range before persisting`() {
        val stored = slot<Int>()
        every { editor.putInt(eq("topn_count"), capture(stored)) } returns editor

        PreferencesManager.setTopNCount(context, 10)
        assertEquals(PreferencesManager.TOPN_MAX_COUNT, stored.captured)
    }

    @Test
    fun `setTopNCount clamps below range before persisting`() {
        val stored = slot<Int>()
        every { editor.putInt(eq("topn_count"), capture(stored)) } returns editor

        PreferencesManager.setTopNCount(context, -3)
        assertEquals(PreferencesManager.TOPN_MIN_COUNT, stored.captured)
    }

    @Test
    fun `setTopNEnabled persists the flag`() {
        PreferencesManager.setTopNEnabled(context, true)
        verify { editor.putBoolean("topn_enabled", true) }
        verify { editor.apply() }
    }

    @Test
    fun `setTopNCount within range persists unchanged`() {
        val stored = slot<Int>()
        every { editor.putInt(eq("topn_count"), capture(stored)) } returns editor

        PreferencesManager.setTopNCount(context, 3)
        assertEquals(3, stored.captured)
    }
}
