package com.micspeaker.app

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build

enum class Kind { MIC, VOICE, BACK, RAW, BT, JACK, USB, SPEAKER, EARPIECE, HEARING, CAR, TV, CAST, DOCK, LINE, OTHER }

/** One thing the user can pick in a dropdown: an input (mic) or an output (where sound plays). */
data class Choice(
    val id: String,
    val name: String,
    val sub: String,
    val kind: Kind,
    val group: String,
    val source: Int = MediaRecorder.AudioSource.MIC,
    val device: AudioDeviceInfo? = null,
    /** Bluetooth isn't connected: choosing this opens the device list instead. */
    val pickBt: Boolean = false,
    /** A connected Bluetooth device: shows a "Switch" button to pick a different one. */
    val canSwitch: Boolean = false,
    val enabled: Boolean = true,
) {
    val usable get() = enabled && !pickBt
}

/** Reads every audio port and device Android reports, right now. */
object Devices {
    // Raw AudioDeviceInfo type numbers, so older phones still compile and run.
    private const val TYPE_HEARING_AID = 23
    private const val TYPE_BUILTIN_SPEAKER_SAFE = 24
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27
    private const val TYPE_BLE_BROADCAST = 30

    private val builtInKinds = setOf(Kind.MIC, Kind.VOICE, Kind.BACK, Kind.RAW)
    fun isBuiltInMic(c: Choice?) = c != null &&
        (c.kind in builtInKinds || c.device?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)

    fun scan(am: AudioManager): Pair<List<Choice>, List<Choice>> = scanInputs(am) to scanOutputs(am)

    private fun label(d: AudioDeviceInfo, fallback: String): String {
        val n = d.productName?.toString()?.trim().orEmpty()
        return if (n.isEmpty() || n == Build.MODEL) fallback else n
    }

    private fun scanInputs(am: AudioManager): List<Choice> {
        val out = mutableListOf<Choice>()
        val g = "Built in"
        out += Choice("main", "Phone mic", "Main mic, normal sound", Kind.MIC, g, MediaRecorder.AudioSource.MIC)
        out += Choice("voice", "Clear voice", "Call-style echo and noise cleanup", Kind.VOICE, g, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        out += Choice("camera", "Camera mic", "Tuned for video, often the back mic", Kind.BACK, g, MediaRecorder.AudioSource.CAMCORDER)
        out += Choice("speech", "Voice pickup", "Tuned for speech", Kind.VOICE, g, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            out += Choice("raw", "Raw mic", "No processing at all", Kind.RAW, g, MediaRecorder.AudioSource.UNPROCESSED)
        }

        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val builtIn = devices.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (builtIn.size > 1) {
            builtIn.forEachIndexed { i, d ->
                val where = d.address.takeIf { it.isNotBlank() } ?: "Built-in mic"
                out += Choice("bm${d.id}", "Mic ${i + 1}", where, Kind.MIC, "Each mic on its own", MediaRecorder.AudioSource.MIC, d)
            }
        }

        val c = "Bluetooth and plugged in"
        for (d in devices) {
            val id = "in${d.id}"
            when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                    out += Choice(id, "Bluetooth mic", label(d, "Headset or earbuds"), Kind.BT, c, MediaRecorder.AudioSource.VOICE_COMMUNICATION, d, canSwitch = true)
                TYPE_BLE_HEADSET ->
                    out += Choice(id, "Bluetooth LE mic", label(d, "Earbuds"), Kind.BT, c, MediaRecorder.AudioSource.VOICE_COMMUNICATION, d, canSwitch = true)
                AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                    out += Choice(id, "Headset mic", "Headphone jack", Kind.JACK, c, MediaRecorder.AudioSource.MIC, d)
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                    out += Choice(id, "USB mic", label(d, "Phone port"), Kind.USB, c, MediaRecorder.AudioSource.MIC, d)
                AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL ->
                    out += Choice(id, "Line in", label(d, "Line input"), Kind.LINE, c, MediaRecorder.AudioSource.MIC, d)
                AudioDeviceInfo.TYPE_DOCK ->
                    out += Choice(id, "Dock mic", label(d, "Dock"), Kind.DOCK, c, MediaRecorder.AudioSource.MIC, d)
                AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_TELEPHONY,
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_FM_TUNER, AudioDeviceInfo.TYPE_TV_TUNER -> Unit
                else ->
                    out += Choice(id, "Other mic", label(d, "Input ${d.type}"), Kind.OTHER, c, MediaRecorder.AudioSource.MIC, d)
            }
        }
        if (out.none { it.kind == Kind.BT }) {
            out += Choice("btin-pick", "Bluetooth mic", "Not connected · tap to pick a device", Kind.BT, c, pickBt = true)
        }
        if (out.none { it.kind == Kind.USB }) {
            out += Choice("usbin-none", "USB mic", "Plug a USB mic into the phone port", Kind.USB, c, enabled = false)
        }
        return out.distinctBy { it.id }
    }

    private fun scanOutputs(am: AudioManager): List<Choice> {
        val main = mutableListOf<Choice>()
        val more = mutableListOf<Choice>()
        val m = "Main"
        val a = "Also found"
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val id = "out${d.id}"
            when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> main += Choice(id, "Bluetooth", label(d, "Earbuds or speaker") + " · connected", Kind.BT, m, device = d, canSwitch = true)
                TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER -> main += Choice(id, "Bluetooth", label(d, "Earbuds or speaker") + " · connected", Kind.BT, m, device = d, canSwitch = true)
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                    main += Choice(id, "Headphone jack", "Wired headphones", Kind.JACK, m, device = d)
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                    main += Choice(id, "USB port", label(d, "USB-C audio"), Kind.USB, m, device = d)
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> main += Choice(id, "Phone speaker", "Loudspeaker", Kind.SPEAKER, m, device = d)
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> more += Choice(id, "Earpiece", "Top call speaker", Kind.EARPIECE, a, device = d)
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> more += Choice(id, "Bluetooth (call mode)", label(d, "Headset"), Kind.BT, a, device = d)
                TYPE_HEARING_AID -> more += Choice(id, "Hearing aid", label(d, "Hearing aid"), Kind.HEARING, a, device = d)
                AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC ->
                    more += Choice(id, "TV / HDMI", label(d, "Display adapter"), Kind.TV, a, device = d)
                AudioDeviceInfo.TYPE_BUS -> more += Choice(id, "Car", label(d, "Car audio"), Kind.CAR, a, device = d)
                AudioDeviceInfo.TYPE_DOCK -> more += Choice(id, "Dock", label(d, "Dock speaker"), Kind.DOCK, a, device = d)
                AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE ->
                    more += Choice(id, "Line out", label(d, "Line output"), Kind.LINE, a, device = d)
                AudioDeviceInfo.TYPE_IP -> more += Choice(id, "Cast speaker", label(d, "Wi-Fi speaker"), Kind.CAST, a, device = d)
                TYPE_BLE_BROADCAST -> more += Choice(id, "Bluetooth broadcast", label(d, "Auracast"), Kind.BT, a, device = d)
                TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
                AudioDeviceInfo.TYPE_FM, AudioDeviceInfo.TYPE_BUILTIN_MIC -> Unit
                else -> more += Choice(id, "Other output", label(d, "Output ${d.type}"), Kind.OTHER, a, device = d)
            }
        }
        if (main.none { it.kind == Kind.SPEAKER }) {
            main += Choice("speaker", "Phone speaker", "Loudspeaker", Kind.SPEAKER, m)
        }
        if (main.none { it.kind == Kind.BT }) {
            main += Choice("bt-pick", "Bluetooth", "Not connected · tap to pick a device", Kind.BT, m, pickBt = true)
        }
        if (main.none { it.kind == Kind.USB }) {
            main += Choice("usb-none", "USB port", "Nothing plugged in", Kind.USB, m, enabled = false)
        }
        // Headphone jack is only listed when the phone reports one; no jack, no row.
        val order = listOf(Kind.BT, Kind.JACK, Kind.USB, Kind.SPEAKER)
        main.sortBy { order.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }
        return (main + more).distinctBy { it.id }
    }

    /** When nothing is picked yet, or the picked device goes away, use the best one present. */
    fun best(choices: List<Choice>): Choice? {
        val usable = choices.filter { it.usable }
        val order = listOf(Kind.BT, Kind.USB, Kind.JACK, Kind.HEARING, Kind.CAR, Kind.SPEAKER)
        for (k in order) usable.firstOrNull { it.kind == k }?.let { return it }
        return usable.firstOrNull()
    }
}
