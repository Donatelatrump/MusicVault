package com.musicvault.app.jams

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.musicvault.app.MainActivity
import com.musicvault.app.data.MusicDatabase
import com.musicvault.app.ui.MusicVaultViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit, disposable emulator smoke test; never runs against a device without the live-room argument. */
class JamsVisualFlowTest {
    @Test fun visualRoomAndWarmAppInvitationShowRealMembers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val link = InstrumentationRegistry.getArguments().getString("musicvault.jamsRoom")
        assumeTrue(!link.isNullOrBlank())
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.runOnMainSync { HardwareRenderer.setDrawingEnabled(true) }
        lateinit var vm: MusicVaultViewModel
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        fun waitUntil(condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 35_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(150)
            assertTrue("Room state did not settle", condition())
        }
        fun open(url: String) {
            scenario.onActivity { activity -> activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        }
        fun capture(name: String) {
            scenario.onActivity { it.window.decorView.invalidate() }
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            assertTrue("Missing screen", bitmap.width > 0 && bitmap.height > 0)
            assertTrue("ATD rendering remained disabled", bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) != android.graphics.Color.BLACK)
            File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        try {
            scenario.onActivity { activity ->
                vm = ViewModelProvider(activity, MusicVaultViewModel.Factory(context, MusicDatabase.get(context)))[MusicVaultViewModel::class.java]
            }
            waitUntil { vm.estadoInicialCargado.value && vm.jams.state.value.uid != null }
            assumeTrue("Complete onboarding in this disposable emulator first", !vm.esPrimeraVez.value)
            scenario.onActivity { vm.jams.createRoom() }
            waitUntil { vm.jams.state.value.active && !vm.jams.state.value.busy && vm.jams.state.value.memberCount == 1 }
            val own = vm.jams.state.value
            assertEquals(listOf(own.uid), own.members)
            assertTrue(own.shortCode?.matches(Regex("[0-9]{6}")) == true)
            open(JamInvitation.roomUrl(own.roomCode!!))
            waitUntil { vm.jamLink.value == null }
            capture("jams-own-room.png")
            scenario.onActivity { vm.jams.leave() }
            waitUntil { !vm.jams.state.value.active && !vm.jams.state.value.busy }
            open(link!!)
            waitUntil { vm.jams.state.value.active && !vm.jams.state.value.busy && vm.jams.state.value.memberCount >= 2 }
            assertEquals(JamInvitation.roomCode(link), vm.jams.state.value.roomCode)
            assertTrue(vm.jams.state.value.members.contains(vm.jams.state.value.uid))
            capture("jams-invited-room.png")
            val short = InstrumentationRegistry.getArguments().getString("musicvault.jamsCode")
            if (short != null) {
                scenario.onActivity { vm.jams.leave() }
                waitUntil { !vm.jams.state.value.active && !vm.jams.state.value.busy }
                scenario.onActivity { vm.jams.joinRoom(short) }
                waitUntil { vm.jams.state.value.active && !vm.jams.state.value.busy }
                assertEquals(short, vm.jams.state.value.shortCode)
            }
            if (InstrumentationRegistry.getArguments().getString("musicvault.testAudio") == "true") {
                scenario.onActivity { vm.selectTrack(com.musicvault.app.data.Track("youtube:QAGZJg_OGVU", "How can we go back to being friends", "Money House", "jam", "JAMS", 134000,
                    source = "youtube", sourceId = "QAGZJg_OGVU", url = "https://www.youtube.com/watch?v=QAGZJg_OGVU")) }
                waitUntil { vm.jams.state.value.playback.playing && !vm.jams.state.value.playback.buffering && vm.isPlaying.value }
                scenario.onActivity { vm.togglePlayback(); assertFalse(vm.jams.state.value.playback.playing) }
                Thread.sleep(1000)
                assertFalse(vm.jams.state.value.playback.playing)
                scenario.onActivity { vm.seekTo(30000) }
                waitUntil { vm.jams.state.value.playback.positionMs == 30000L }
                scenario.onActivity { vm.togglePlayback() }
                waitUntil { vm.jams.state.value.playback.playing && vm.isPlaying.value }
                capture("jams-playing-room.png")
            }
            scenario.onActivity { vm.jams.leave() }
            waitUntil { !vm.jams.state.value.active && !vm.jams.state.value.busy }
        } finally {
            if (Build.VERSION.SDK_INT >= 33) instrumentation.runOnMainSync { HardwareRenderer.setDrawingEnabled(false) }
            scenario.onActivity { it.finishAndRemoveTask() }
        }
    }
}
