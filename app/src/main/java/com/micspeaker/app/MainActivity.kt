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
import android.provider.Settings
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
    private var micGain by mutableFloatStateOf(1f)
    private var echo by mutableStateOf(true)
    private var noise by mutableStateOf(false)
    private var boost by mutableStateOf(false)
    private var update by mutableStateOf<Updater.Info?>(null)
    private var updateMsg by mutableStateOf<String?>(null)
    private var downloaded: File? = null
    // Set when the user went to pick a Bluetooth device; the next one that connects gets used.
    private var wantBtIn = false
    private var wantBtOut = false

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
                onPickIn = { c ->
                    if (c.pickBt) { wantBtIn = true; openBluetooth() }
                    else if (c.usable) { selIn = c; Engine.input = c; Engine.restartIfRunning(this) }
                },
                onPickOut = { c ->
                    if (c.pickBt) { wantBtOut = true; openBluetooth() }
                    else if (c.usable) { selOut = c; Engine.output = c; Engine.restartIfRunning(this) }
                },
                onSwitchBtIn = { wantBtIn = true; openBluetooth() },
                onSwitchBtOut = { wantBtOut = true; openBluetooth() },
                onScan = { rescan() },
                onVolume = { volume = it; Engine.volume = it },
                micGain = micGain,
                onMicGain = { micGain = it; Engine.micGain = it },
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
        // Scan twice each time the app opens: once right away, once after devices
        // that are still waking up (Bluetooth, USB) have had time to show up.
        rescan()
        lifecycleScope.launch { delay(1500); rescan() }
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
        val btIn = i.firstOrNull { it.kind == Kind.BT && it.usable && it.id != selIn?.id }
        val btOut = o.firstOrNull { it.kind == Kind.BT && it.usable && it.group == "Main" && it.id != selOut?.id }
        val newIn = (if (wantBtIn && btIn != null) btIn.also { wantBtIn = false } else null)
            ?: i.firstOrNull { it.id == selIn?.id && it.usable } ?: i.first { it.usable }
        val newOut = (if (wantBtOut && btOut != null) btOut.also { wantBtOut = false } else null)
            ?: o.firstOrNull { it.id == selOut?.id && it.usable } ?: Devices.best(o)
        val changed = newIn.id != Engine.input?.id || newOut?.id != Engine.output?.id
        selIn = newIn
        selOut = newOut
        Engine.input = newIn
        Engine.output = newOut
        if (changed) Engine.restartIfRunning(this)
        lifecycleScope.launch { delay(700); scanning = false }
    }

    /** Android doesn't let apps connect Bluetooth audio directly, so open the phone's Bluetooth list. */
    private fun openBluetooth() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
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
        Engine.micGain = micGain
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
