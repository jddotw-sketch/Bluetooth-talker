package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("BlueVoice", appName)
  }

  @Test
  fun `test startRecording does not crash`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = com.example.audio.AudioStreamManager(
        context = context,
        onAudioPacketReady = {},
        onAmplitudeChanged = { _, _ -> },
        onHeadsetStateChanged = {},
        onSampleRateChanged = {}
    )
    manager.startRecording(com.example.model.AudioSourceMode.MICROPHONE, false)
    manager.stopRecording()
    manager.release()
  }
}
