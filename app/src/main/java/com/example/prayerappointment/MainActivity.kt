package com.example.prayerappointment

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// ==========================================
// 1. DATA MODELS & ENUMS
// ==========================================

enum class PrayerType(val displayName: String, val arabicName: String) {
    FAJR("Fajr", "الفجر"),
    SUNRISE("Sunrise", "الشروق"),
    DHUHR("Dhuhr", "الظهر"),
    ASR("Asr", "العصر"),
    MAGHRIB("Maghrib", "المغرب"),
    ISHA("Isha", "العشاء")
}

enum class OffsetDirection(val displayName: String) {
    BEFORE("Before"),
    AFTER("After"),
    EXACT("At exact time")
}

enum class AlertType(val displayName: String) {
    VISUAL_POPUP("Visual Notification Only"),
    CUSTOM_ALARM("Custom Alarm Sound"),
    BOTH("Full Alarm + Visual Banner")
}

data class Appointment(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val linkedPrayer: PrayerType,
    val offsetMinutes: Int,
    val offsetDirection: OffsetDirection,
    val alertType: AlertType,
    val volumeLevel: Int = 80,
    val isEnabled: Boolean = true
)

data class LocationConfig(
    val latitude: Double = 21.4225,  // Makkah
    val longitude: Double = 39.8262,
    val cityName: String = "Makkah Al-Mukarramah",
    val calcMethod: String = "MWL"   // Muslim World League
)

data class PrayerTimeItem(
    val type: PrayerType,
    val timeFormatted: String,
    val timestampMs: Long,
    val isNext: Boolean = false
)

enum class ScreenState {
    HOME_GRID,
    PRAYER_TIMES,
    APPOINTMENTS,
    WALLPAPERS_THEMES,
    SETTINGS
}

// ==========================================
// 2. PRAYER TIME CALCULATION ENGINE
// ==========================================

object PrayerCalculator {

    fun calculateDailyPrayers(
        date: Date = Date(),
        location: LocationConfig = LocationConfig()
    ): List<PrayerTimeItem> {
        val cal = Calendar.getInstance().apply { time = date }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)

        // Solar Declination & Equation of Time approximations
        val gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma)
                - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
                0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma)

        // Timezone Offset in hours
        val tzOffsetHours = TimeZone.getDefault().getOffset(date.time) / (1000.0 * 3600.0)

        // Solar Noon
        val noonMinutes = 720.0 - (4.0 * location.longitude) - eqTime + (tzOffsetHours * 60.0)

        // Angle calculations for Fajr (-18 deg) and Isha (-17 deg)
        val fajrHA = calculateHourAngle(-18.0, location.latitude, decl)
        val ishaHA = calculateHourAngle(-17.0, location.latitude, decl)
        val sunriseHA = calculateHourAngle(-0.833, location.latitude, decl)

        val fajrMinutes = noonMinutes - (fajrHA * 4.0)
        val sunriseMinutes = noonMinutes - (sunriseHA * 4.0)
        val dhuhrMinutes = noonMinutes + 2.0 // +2 mins safety buffer
        val asrMinutes = noonMinutes + (calculateAsrHourAngle(1, location.latitude, decl) * 4.0)
        val maghribMinutes = noonMinutes + (sunriseHA * 4.0)
        val ishaMinutes = noonMinutes + (ishaHA * 4.0)

        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val nowMs = System.currentTimeMillis()

        val rawList = listOf(
            PrayerType.FAJR to fajrMinutes,
            PrayerType.SUNRISE to sunriseMinutes,
            PrayerType.DHUHR to dhuhrMinutes,
            PrayerType.ASR to asrMinutes,
            PrayerType.MAGHRIB to maghribMinutes,
            PrayerType.ISHA to ishaMinutes
        )

        var foundNext = false
        return rawList.map { (type, mins) ->
            val prayerCal = Calendar.getInstance().apply {
                time = date
                set(Calendar.HOUR_OF_DAY, (mins / 60).toInt())
                set(Calendar.MINUTE, (mins % 60).toInt())
                set(Calendar.SECOND, 0)
            }
            val prayerMs = prayerCal.timeInMillis
            val isNext = !foundNext && prayerMs > nowMs
            if (isNext) foundNext = true

            PrayerTimeItem(
                type = type,
                timeFormatted = sdf.format(prayerCal.time),
                timestampMs = prayerMs,
                isNext = isNext
            )
        }
    }

    private fun calculateHourAngle(angle: Double, lat: Double, decl: Double): Double {
        val latRad = Math.toRadians(lat)
        val angleRad = Math.toRadians(angle)
        val cosHA = (sin(angleRad) - (sin(latRad) * sin(decl))) / (cos(latRad) * cos(decl))
        return Math.toDegrees(acos(cosHA.coerceIn(-1.0, 1.0))) / 15.0
    }

    private fun calculateAsrHourAngle(factor: Int, lat: Double, decl: Double): Double {
        val latRad = Math.toRadians(lat)
        val phi = abs(latRad - decl)
        val cotFactor = factor + tan(phi)
        val asrAngleRad = atan(1.0 / cotFactor)
        val cosHA = (sin(asrAngleRad) - (sin(latRad) * sin(decl))) / (cos(latRad) * cos(decl))
        return Math.toDegrees(acos(cosHA.coerceIn(-1.0, 1.0))) / 15.0
    }
}

// ==========================================
// 3. ALARM & NOTIFICATION RECEIVER
// ==========================================

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("EXTRA_TITLE") ?: "Prayer Appointment Alert"
        val prayerName = intent.getStringExtra("EXTRA_PRAYER") ?: "Prayer"

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = "prayer_appointment_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Prayer Appointments",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts linked relative to Islamic prayer times"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ $title")
            .setContentText("Scheduled relative to $prayerName time")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}

object AlarmScheduler {
    fun scheduleAppointmentAlarm(context: Context, appointment: Appointment) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EXTRA_TITLE", appointment.title)
            putExtra("EXTRA_PRAYER", appointment.linkedPrayer.displayName)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appointment.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Mock 10-second test trigger for demonstration
        val triggerTimeMs = System.currentTimeMillis() + 10_000

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}

// ==========================================
// 4. MAIN ACTIVITY & UI
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainAppHost(this)
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainAppHost(context: Context) {
    var currentScreen by remember { mutableStateOf(ScreenState.HOME_GRID) }
    var appointments by remember {
        mutableStateOf(
            listOf(
                Appointment(
                    title = "Preparation for Jumu'ah",
                    linkedPrayer = PrayerType.DHUHR,
                    offsetMinutes = 45,
                    offsetDirection = OffsetDirection.BEFORE,
                    alertType = AlertType.BOTH
                ),
                Appointment(
                    title = "Evening Azkar & Reflection",
                    linkedPrayer = PrayerType.ASR,
                    offsetMinutes = 20,
                    offsetDirection = OffsetDirection.AFTER,
                    alertType = AlertType.CUSTOM_ALARM
                )
            )
        )
    }
    var showCreateDialog by remember { mutableStateOf(false) }

    val dailyPrayers = remember { PrayerCalculator.calculateDailyPrayers() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF064E3B).copy(alpha = 0.40f), // Dark emerald glow
                        Color(0xFF020617)                      // Deep midnight background
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // TOP HEADER: Contains ONLY the exact localized logo asset R.drawable.ic_olive_leaf_logo
            HeaderSection(onLogoClick = { currentScreen = ScreenState.HOME_GRID })

            // SCREEN BODY WITH TRANSITIONS
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentScreen) {
                    ScreenState.HOME_GRID -> HomeGridScreen(
                        onOpenPrayerTimes = { currentScreen = ScreenState.PRAYER_TIMES },
                        onOpenAppointments = { currentScreen = ScreenState.APPOINTMENTS },
                        onOpenWallpapers = { currentScreen = ScreenState.WALLPAPERS_THEMES },
                        onOpenSettings = { currentScreen = ScreenState.SETTINGS }
                    )
                    ScreenState.PRAYER_TIMES -> PrayerTimesScreen(
                        prayers = dailyPrayers,
                        onBack = { currentScreen = ScreenState.HOME_GRID }
                    )
                    ScreenState.APPOINTMENTS -> AppointmentsScreen(
                        appointments = appointments,
                        onBack = { currentScreen = ScreenState.HOME_GRID },
                        onAddNew = { showCreateDialog = true },
                        onDelete = { app -> appointments = appointments.filter { it.id != app.id } },
                        onTestAlert = { app ->
                            AlarmScheduler.scheduleAppointmentAlarm(context, app)
                            Toast.makeText(context, "Test alert scheduled for '${app.title}' in 10s", Toast.LENGTH_LONG).show()
                        }
                    )
                    ScreenState.WALLPAPERS_THEMES -> WallpapersThemesScreen(
                        onBack = { currentScreen = ScreenState.HOME_GRID }
                    )
                    ScreenState.SETTINGS -> SettingsScreen(
                        onBack = { currentScreen = ScreenState.HOME_GRID }
                    )
                }
            }
        }

        if (showCreateDialog) {
            CreateAppointmentDialog(
                onDismiss = { showCreateDialog = false },
                onSave = { newApp ->
                    appointments = appointments + newApp
                    AlarmScheduler.scheduleAppointmentAlarm(context, newApp)
                    showCreateDialog = false
                    Toast.makeText(context, "Appointment created & linked!", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
fun HeaderSection(onLogoClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_olive_leaf_logo),
            contentDescription = "Official Curved Olive Leaf Logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(120.dp)
                .background(Color.Transparent)
                .clickable { onLogoClick() }
        )
    }
}

// ==========================================
// 5. 2x2 HOME GRID MENU SCREEN
// ==========================================

@Composable
fun HomeGridScreen(
    onOpenPrayerTimes: () -> Unit,
    onOpenAppointments: () -> Unit,
    onOpenWallpapers: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                GridTileButton(
                    line1 = "Prayer",
                    line2 = "Times",
                    icon = Icons.Default.Schedule,
                    onClick = onOpenPrayerTimes,
                    modifier = Modifier.weight(1f)
                )
                GridTileButton(
                    line1 = "Prayer",
                    line2 = "Appointments",
                    icon = Icons.Default.Event,
                    onClick = onOpenAppointments,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                GridTileButton(
                    line1 = "Wallpapers &",
                    line2 = "Themes",
                    icon = Icons.Default.Palette,
                    onClick = onOpenWallpapers,
                    modifier = Modifier.weight(1f)
                )
                GridTileButton(
                    line1 = "Settings",
                    line2 = "",
                    icon = Icons.Default.Settings,
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun GridTileButton(
    line1: String,
    line2: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0x33065F46) // Glass dark emerald
        ),
        modifier = modifier
            .aspectRatio(1f)
            .border(1.dp, Color(0x3310B981), RoundedCornerShape(24.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF34D399),
                    modifier = Modifier
                        .size(32.dp)
                        .padding(bottom = 6.dp)
                )
                Text(
                    text = line1,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                if (line2.isNotEmpty()) {
                    Text(
                        text = line2,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ==========================================
// 6. SUB-SCREENS
// ==========================================

@Composable
fun PrayerTimesScreen(
    prayers: List<PrayerTimeItem>,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "Today's Prayer Times",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(prayers) { prayer ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (prayer.isNext) Color(0xFF065F46) else Color(0x1F1E293B)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (prayer.isNext) Color(0xFF10B981) else Color(0x33475569),
                            RoundedCornerShape(16.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = prayer.type.displayName,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = prayer.type.arabicName,
                                fontSize = 14.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Text(
                            text = prayer.timeFormatted,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = if (prayer.isNext) Color(0xFFF59E0B) else Color(0xFF34D399)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppointmentsScreen(
    appointments: List<Appointment>,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    onDelete: (Appointment) -> Unit,
    onTestAlert: (Appointment) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = "Prayer Appointments",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Button(
                onClick = onAddNew,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("New")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (appointments.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No appointments linked yet. Tap '+ New' to add.", color = Color(0xFF94A3B8))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(appointments) { app ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x221E293B)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0x33334155), RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = app.title,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${app.offsetMinutes}m ${app.offsetDirection.displayName} ${app.linkedPrayer.displayName}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFF59E0B)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Alert Mode: ${app.alertType.displayName}",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onTestAlert(app) }) {
                                    Text("Test Alert", color = Color(0xFF34D399))
                                }
                                OutlinedButton(onClick = { onDelete(app) }) {
                                    Text("Delete", color = Color(0xFFEF4444))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WallpapersThemesScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "Wallpapers & Islamic Themes",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Select Background Atmosphere",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp
        )
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "App Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Calculation Method: Muslim World League",
            color = Color.White,
            fontSize = 16.sp
        )
    }
}

// ==========================================
// 7. CREATE APPOINTMENT DIALOG
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAppointmentDialog(
    onDismiss: () -> Unit,
    onSave: (Appointment) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedPrayer by remember { mutableStateOf(PrayerType.DHUHR) }
    var offsetMinutes by remember { mutableStateOf("30") }
    var offsetDirection by remember { mutableStateOf(OffsetDirection.BEFORE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link Appointment to Prayer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = offsetMinutes,
                    onValueChange = { offsetMinutes = it },
                    label = { Text("Offset Minutes (e.g. 15, 30, 45)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val app = Appointment(
                        title = title.ifEmpty { "Prayer Linked Task" },
                        linkedPrayer = selectedPrayer,
                        offsetMinutes = offsetMinutes.toIntOrNull() ?: 30,
                        offsetDirection = offsetDirection,
                        alertType = AlertType.BOTH
                    )
                    onSave(app)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
