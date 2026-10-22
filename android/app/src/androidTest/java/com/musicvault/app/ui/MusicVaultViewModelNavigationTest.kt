package com.musicvault.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.settings.AppPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only in the explicit instrumentation job; it performs no provider requests. */
@RunWith(AndroidJUnit4::class)
class MusicVaultViewModelNavigationTest {
    @Test fun tabHistorySurvivesRecreationAndBackDoesNotPushAnotherEntry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = AppPreferences.prefs(context)
        val wasOffline = prefs.getBoolean("offline_mode", false)
        prefs.edit().putBoolean("offline_mode", true).commit()
        val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                val saved = SavedStateHandle()
                val model = MusicVaultViewModel(context, database, saved)
                store.put("before", model)
                model.selectSection(AppSection.SEARCH)
                model.selectSection(AppSection.LIBRARY)
                assertEquals("LIBRARY", saved.get<String>("section"))
                assertEquals(listOf("HOME", "SEARCH"), saved.get<ArrayList<String>>("section_stack"))
                store.clear()
                val restored = SavedStateHandle(mapOf(
                    "section" to saved.get<String>("section"),
                    "section_stack" to ArrayList(saved.get<ArrayList<String>>("section_stack")!!)
                ))
                val recreated = MusicVaultViewModel(context, database, restored)
                store.put("after", recreated)
                recreated.navigateBack()
                assertEquals("SEARCH", restored.get<String>("section"))
                assertEquals(listOf("HOME"), restored.get<ArrayList<String>>("section_stack"))
                recreated.navigateBack()
                assertEquals("HOME", restored.get<String>("section"))
                assertTrue(restored.get<ArrayList<String>>("section_stack")!!.isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            database.close()
            prefs.edit().putBoolean("offline_mode", wasOffline).commit()
        }
    }
}
