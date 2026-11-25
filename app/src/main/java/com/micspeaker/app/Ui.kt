package com.micspeaker.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sin

private val Bg = Color(0xFF070D10)
private val Surface = Color(0xFF16242B)
private val Surface2 = Color(0xFF1D2F37)
private val Line = Color(0xFF26404A)
private val Fg = Color(0xFFE7F1F4)
private val Muted = Color(0xFF8AA3AD)
private val Accent = Color(0xFF3FD0C4)
private val AccentSoft = Color(0xFF123B3A)
private val AccentFg = Color(0xFF04201E)
private val Live = Color(0xFFFF6B5E)
private val LiveSoft = Color(0xFF3B1A17)
private val Warn = Color(0xFFF2B54A)
private val WarnSoft = Color(0xFF33270F)

@Suppress("DEPRECATION")
fun iconFor(k: Kind): ImageVector = when (k) {
    Kind.MIC -> Icons.Filled.Mic
    Kind.VOICE -> Icons.Filled.RecordVoiceOver
    Kind.BACK -> Icons.Filled.PhotoCamera
    Kind.RAW -> Icons.Filled.GraphicEq
    Kind.BT -> Icons.Filled.Bluetooth
    Kind.JACK -> Icons.Filled.Headphones
    Kind.USB -> Icons.Filled.Usb
    Kind.SPEAKER -> Icons.Filled.VolumeUp
    Kind.EARPIECE -> Icons.Filled.PhoneInTalk
    Kind.HEARING -> Icons.Filled.Hearing
    Kind.CAR -> Icons.Filled.DirectionsCar
    Kind.TV -> Icons.Filled.Tv
    Kind.CAST -> Icons.Filled.Cast
    Kind.DOCK, Kind.LINE, Kind.OTHER -> Icons.Filled.Speaker
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MicScreen(
    inputs: List<Choice>, outputs: List<Choice>, selIn: Choice?, selOut: Choice?,
    scanning: Boolean, volume: Float, echo: Boolean, noise: Boolean, boost: Boolean,
    update: Updater.Info?, updateMsg: String?, version: String,
    onPickIn: (Choice) -> Unit, onPickOut: (Choice) -> Unit,
    onSwitchBtIn: () -> Unit, onSwitchBtOut: () -> Unit, onScan: () -> Unit,
    onVolume: (Float) -> Unit, micGain: Float, onMicGain: (Float) -> Unit, onEcho: () -> Unit, onNoise: () -> Unit, onBoost: () -> Unit,
    onStartStop: () -> Unit, onUpdate: () -> Unit,
) {
    val running by Engine.running.collectAsState()
    val level by Engine.level.collectAsState()
    val error by Engine.error.collectAsState()

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Surface2, onSurface = Fg)) {
        Column(
            Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ---- Top: things you only look at. Fixed page, no scrolling; the route card takes the leftover space. ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mic to Speaker", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("Version $version", color = Muted, fontSize = 11.sp)
                }
                StatusPill(running)
            }
            if (update != null) {
                Spacer(Modifier.height(10.dp))
                UpdateBanner(update, updateMsg, onUpdate)
            }
            Spacer(Modifier.height(12.dp))
            // The card sits at the top of the free space and stops at 220dp; any extra room stays empty
            // so the controls keep their place at the bottom.
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp)).background(Surface).padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RouteNode("From", selIn, Modifier.weight(1f))
                        Text("→", color = if (running) Live else Muted, fontSize = 24.sp)
                        RouteNode("To", selOut, Modifier.weight(1f))
                    }
                    Meter(running, level)
                }
            }

            // ---- Bottom: everything you touch, in thumb reach ----
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val feedbackRisk = Devices.isBuiltInMic(selIn) && selOut?.kind == Kind.SPEAKER && !echo
                if (error != null) Notice(error!!)
                else if (feedbackRisk) Notice("Phone mic into phone speaker can squeal. Turn on Echo guard or use headphones.")

                Picker("Input", selIn, inputs, onPickIn, onSwitchBtIn)
                Picker("Output", selOut, outputs, onPickOut, onSwitchBtOut)

                @Suppress("DEPRECATION")
                LevelSlider("Mic sensitivity", Icons.Filled.Mic, micGain, 0.1f..3f, onMicGain)
                @Suppress("DEPRECATION")
                LevelSlider("Volume", Icons.Filled.VolumeUp, volume, 0f..1.5f, onVolume)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Toggle("Echo guard", echo, Modifier.weight(1f), onEcho)
                    Toggle("Noise cleanup", noise, Modifier.weight(1f), onNoise)
                    Toggle("Voice boost", boost, Modifier.weight(1f), onBoost)
                }
                BigButton(running, onStartStop)
            }
        }
    }
}

@Composable
private fun LevelSlider(label: String, icon: ImageVector, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = Muted, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = value, onValueChange = onChange, valueRange = range,
                modifier = Modifier.height(32.dp),
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line)
            )
        }
        Text("${(value * 100).toInt()}%", color = Fg, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val fg = if (running) Live else Muted
    Row(
        Modifier.clip(CircleShape).background(if (running) LiveSoft else Surface).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(6.dp))
        Text(if (running) "LIVE" else "OFF", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UpdateBanner(info: Updater.Info, msg: String?, onUpdate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(AccentSoft).clickable { onUpdate() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.SystemUpdate, null, tint = Accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Update ${info.versionName} is ready", color = Fg, fontWeight = FontWeight.Bold)
            Text(msg ?: "Tap to download and install", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RouteNode(label: String, c: Choice?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Surface2), contentAlignment = Alignment.Center) {
            Icon(iconFor(c?.kind ?: Kind.OTHER), null, tint = Accent, modifier = Modifier.size(28.dp))
        }
        Text(label.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(c?.name ?: "—", color = Fg, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Meter(running: Boolean, level: Float) {
    val t = rememberInfiniteTransition(label = "meter")
    val phase by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Row(Modifier.height(30.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 16) {
            val shape = 0.5f + 0.5f * abs(sin(phase + i * 0.7f))
            val h = if (running) (0.18f + 0.82f * (level * 1.6f).coerceAtMost(1f) * shape) else 0.18f
            Box(Modifier.width(7.dp).fillMaxHeight(h).clip(RoundedCornerShape(4.dp)).background(if (running) Live else Line))
        }
    }
}

@Composable
private fun PortChip(text: String, on: Boolean) {
    Row(
        Modifier.clip(CircleShape).background(Surface).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Accent else Line))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (on) Fg else Muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text, color = Warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(WarnSoft).padding(horizontal = 12.dp, vertical = 9.dp)
    )
}

@Composable
private fun Picker(label: String, current: Choice?, options: List<Choice>, onPick: (Choice) -> Unit, onSwitchBt: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(18.dp)).background(Surface)
                    .border(2.dp, if (open) Accent else Line, RoundedCornerShape(18.dp))
                    .clickable { open = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(iconFor(current?.kind ?: Kind.OTHER), null, tint = Accent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(current?.name ?: "Pick one", color = Fg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(current?.sub ?: "", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Muted)
            }
            DropdownMenu(
                expanded = open, onDismissRequest = { open = false },
                modifier = Modifier.background(Surface2).heightIn(max = 420.dp)
            ) {
                var group: String? = null
                for (o in options) {
                    if (o.group != group) {
                        group = o.group
                        Text(o.group.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp))
                    }
                    val picked = o.id == current?.id
                    DropdownMenuItem(
                        enabled = o.enabled,
                        text = {
                            Column {
                                Text(o.name, color = if (o.enabled) Fg else Muted, fontWeight = FontWeight.Bold)
                                Text(o.sub, color = if (o.pickBt) Accent else Muted, fontSize = 12.sp)
                            }
                        },
                        leadingIcon = { Icon(iconFor(o.kind), null, tint = if (picked) Accent else Muted) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (o.canSwitch) {
                                    Text(
                                        "Switch", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                        modifier = Modifier.clip(CircleShape).border(1.5.dp, Line, CircleShape)
                                            .clickable { open = false; onSwitchBt() }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (picked) Icon(Icons.Filled.Check, null, tint = Accent)
                            }
                        },
                        onClick = { open = false; onPick(o) },
                        modifier = Modifier.heightIn(min = 56.dp).background(if (picked) AccentSoft else Color.Transparent)
                    )
                }
            }
        }
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (on) AccentSoft else Surface)
            .border(2.dp, if (on) Accent else Line, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Fg, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BigButton(running: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (running) Live else Accent, label = "go")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(22.dp)).background(bg).clickable { onClick() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (running) Color(0xFF2A0805) else AccentFg
        Icon(if (running) Icons.Filled.Stop else Icons.Filled.Mic, null, tint = fg, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Text(if (running) "Stop" else "Start", color = fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}
