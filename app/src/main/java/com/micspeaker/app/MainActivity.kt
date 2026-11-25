package com.micspeaker.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var am: AudioManager

    private var inputs by mutableStateOf(listOf<Choice>())
    private var outputs by mutableStateOf(listOf<Choice>())
    private var selIn by mutableStateOf<Choice?>(null)
    private var selOut by mutableStateOf<Choice?>(null)
    private var scanning by mutableStateOf(false)
    private var volume by mutableFloatStateOf(0.8f)
    private var echo by mutableStateOf(true)
    private var noise by mutableStateOf(false)
    private var boost by mutableStateOf(false)
    private var update by mutableStateOf<Updater.Info?>(null)
    private var updateMsg by mutableStateOf<String?>(null)
    private var downloaded: File? = null

    private val deviceWatcher = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = rescan()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = rescan()
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMic()) startLoop() else Engine.error.value = "Allow the microphone so the app can listen."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        am = getSystemService(AudioManager::class.java)
        am.registerAudioDeviceCallback(deviceWatcher, Handler(Looper.getMainLooper()))
        Engine.volume = volume
        rescan()
        lifecycleScope.launch { update = Updater.check() }

        setContent {
            MicScreen(
                inputs = inputs,
                outputs = outputs,
                selIn = selIn,
                selOut = selOut,
                scanning = scanning,
                volume = volume,
                echo = echo,
                noise = noise,
                boost = boost,
                update = update,
                updateMsg = updateMsg,
                version = BuildConfig.VERSION_NAME,
                onPickIn = { selIn = it; Engine.input = it; Engine.restartIfRunning(this) },
                onPickOut = { selOut = it; Engine.output = it; Engine.restartIfRunning(this) },
                onScan = { rescan() },
                onVolume = { volume = it; Engine.volume = it },
                onEcho = { echo = !echo; Engine.echo = echo; Engine.restartIfRunning(this) },
                onNoise = { noise = !noise; Engine.noise = noise; Engine.restartIfRunning(this) },
                onBoost = { boost = !boost; Engine.boost = boost },
                onStartStop = { if (Engine.running.value) stopLoop() else tryStart() },
                onUpdate = { runUpdate() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from "allow installs" settings: continue the update.
        downloaded?.let { if (packageManager.canRequestPackageInstalls()) Updater.install(this, it) }
    }

    override fun onDestroy() {
        am.unregisterAudioDeviceCallback(deviceWatcher)
        super.onDestroy()
    }

    private fun rescan() {
        scanning = true
        val (i, o) = Devices.scan(am)
        inputs = i
        outputs = o
        val newIn = i.firstOrNull { it.id == selIn?.id } ?: i.first()
        val newOut = o.firstOrNull { it.id == selOut?.id } ?: Devices.best(o)
        val changed = newIn.id != Engine.input?.id || newOut?.id != Engine.output?.id
        selIn = newIn
        selOut = newOut
        Engine.input = newIn
        Engine.output = newOut
        if (changed) Engine.restartIfRunning(this)
        lifecycleScope.launch { delay(700); scanning = false }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun tryStart() {
        if (hasMic()) { startLoop(); return }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
        askPermissions.launch(perms.toTypedArray())
    }

    private fun startLoop() {
        Engine.input = selIn
        Engine.output = selOut
        Engine.echo = echo
        Engine.noise = noise
        Engine.boost = boost
        Engine.volume = volume
        ContextCompat.startForegroundService(this, Intent(this, LoopService::class.java))
    }

    private fun stopLoop() {
        stopService(Intent(this, LoopService::class.java))
        Engine.stop()
    }

    private fun runUpdate() {
        val info = update ?: return
        downloaded?.let { Updater.install(this, it); return }
        lifecycleScope.launch {
            updateMsg = "Downloading… 0%"
            try {
                val f = Updater.download(this@MainActivity, info) { p -> updateMsg = "Downloading… $p%" }
                downloaded = f
                updateMsg = "Tap Install on the next screen"
                if (!Updater.install(this@MainActivity, f)) {
                    updateMsg = "Allow installs from Mic to Speaker, then come back"
                }
            } catch (e: Exception) {
                updateMsg = "Download failed. Check your internet and try again."
            }
        }
    }
}
