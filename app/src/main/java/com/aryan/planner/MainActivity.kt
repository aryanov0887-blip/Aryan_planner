package com.aryan.planner

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

data class PlannerTask(
    val id: Long, val title: String, val icon: String, val date: String, val time: String,
    val duration: Int, val reminder: Int, val note: String, var done: Boolean = false
) {
    fun json() = JSONObject().put("id", id).put("title", title).put("icon", icon)
        .put("date", date).put("time", time).put("duration", duration)
        .put("reminder", reminder).put("note", note).put("done", done)
}

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("planner", MODE_PRIVATE) }
    private val tasks = mutableListOf<PlannerTask>()
    private lateinit var content: LinearLayout
    private var tab = "home"
    private val persianLocale = Locale("fa", "IR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadTasks()
        createChannel()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        showUi()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 16f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xFFF2F6FC.toInt())
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(7), dp(2), dp(7))
        }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private fun baseColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(24))
        setBackgroundColor(0xFF0B1220.toInt())
    }

    private fun showUi() {
        val outer = baseColumn()
        val scroller = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(content)
        outer.addView(scroller)
        setContentView(outer)
        content.addView(label("✓  برنامه‌ریز من", 25f, true))
        content.addView(label("امروز: ${persianDate(Date())}", 14f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("خانه" to "home", "کارها" to "tasks", "رئال مادرید" to "matches").forEach { (title, key) ->
            nav.addView(button(title) { tab = key; showUi() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        content.addView(nav)
        when (tab) {
            "home" -> showHome()
            "tasks" -> showTasks()
            else -> showMatches()
        }
    }

    private fun showHome() {
        content.addView(label("یه قدم کوچیک، یه پیشرفت واقعی.", 19f, true))
        val today = todayIso()
        val todayTasks = tasks.filter { it.date == today }
        content.addView(label("کارهای امروز: ${todayTasks.size}   |   انجام‌شده: ${todayTasks.count { it.done }}"))
        content.addView(button("➕ افزودن کار") { showAddTask() })
        content.addView(label("کارهای نزدیک", 18f, true))
        tasks.filter { !it.done }.sortedBy { it.date + it.time }.take(5).forEach { addTaskRow(it) }
        content.addView(button("⚽ دیدن مسابقات رئال") { tab = "matches"; showUi() })
    }

    private fun showTasks() {
        content.addView(button("➕ افزودن کار جدید") { showAddTask() })
        if (tasks.isEmpty()) content.addView(label("هنوز کاری ثبت نکردی."))
        tasks.sortedBy { it.date + it.time }.forEach { addTaskRow(it) }
    }

    private fun addTaskRow(t: PlannerTask) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF172338.toInt())
        }
        row.addView(label("${t.icon}  ${t.title}", 17f, true))
        row.addView(label("${persianDate(parseDate(t.date))} · ${t.time} · ${t.duration} دقیقه", 13f))
        if (t.note.isNotBlank()) row.addView(label(t.note, 13f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button(if (t.done) "برگردان" else "انجام شد") {
            t.done = !t.done; saveTasks(); showUi()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(button("حذف") {
            tasks.removeAll { it.id == t.id }; saveTasks(); showUi()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(actions)
        val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8)
        content.addView(row, lp)
    }

    private fun showAddTask() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(0xFF121D30.toInt())
        }
        val title = EditText(this).apply { hint = "اسم کار، مثلاً کتاب‌خوانی"; setSingleLine(true) }
        val note = EditText(this).apply { hint = "یادداشت (اختیاری)"; setSingleLine(true) }
        val date = EditText(this).apply { hint = "تاریخ میلادی YYYY-MM-DD"; setText(todayIso()); setSingleLine(true) }
        val time = EditText(this).apply { hint = "ساعت HH:MM"; setText("17:00"); setSingleLine(true) }
        val duration = EditText(this).apply { hint = "مدت به دقیقه"; setText("30"); inputType = 2 }
        val icon = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("📚 کتاب‌خوانی", "📝 درس", "🏋️ ورزش", "🧘 استراحت", "💧 آب خوردن", "🎯 متفرقه"))
        }
        val reminder = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("۱۵ دقیقه قبل", "۳۰ دقیقه قبل", "۱ ساعت قبل", "۱ روز قبل", "بدون یادآوری"))
        }
        box.addView(label("افزودن کار", 21f, true))
        listOf(title, date, time, duration, icon, reminder, note).forEach { box.addView(it) }
        box.addView(button("ذخیره") {
            if (title.text.isNullOrBlank()) { title.error = "اسم کار رو وارد کن"; return@button }
            val dateText = date.text.toString().trim()
            val timeText = time.text.toString().trim()
            if (!dateText.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || !timeText.matches(Regex("\\d{2}:\\d{2}"))) {
                Toast.makeText(this, "تاریخ و ساعت را به شکل خواسته‌شده وارد کن.", Toast.LENGTH_LONG).show(); return@button
            }
            val icons = listOf("📚", "📝", "🏋️", "🧘", "💧", "🎯")
            val reminders = listOf(15, 30, 60, 1440, 0)
            val task = PlannerTask(System.currentTimeMillis(), title.text.toString().trim(),
                icons[icon.selectedItemPosition], dateText, timeText,
                duration.text.toString().toIntOrNull()?.coerceIn(5, 600) ?: 30,
                reminders[reminder.selectedItemPosition], note.text.toString().trim())
            tasks.add(task); saveTasks(); scheduleReminder(task)
            tab = "tasks"; showUi()
            Toast.makeText(this, "کار ذخیره شد", Toast.LENGTH_SHORT).show()
        })
        val dialog = AlertDialog.Builder(this).setView(box).setNegativeButton("بستن", null).create()
        dialog.show()
    }

    private fun showMatches() {
        content.addView(label("⚽ مسابقات رئال مادرید", 20f, true))
        content.addView(label("برنامه از منبع عمومی ESPN دریافت می‌شود؛ تاریخ و ساعت را پیش از اتکا بررسی کن.", 13f))
        val status = label("برای دریافت برنامه، دکمه زیر رو بزن.")
        content.addView(status)
        content.addView(button("به‌روزرسانی بازی‌ها") {
            status.text = "در حال دریافت برنامه مسابقات…"
            thread {
                try {
                    val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
                    val start = Calendar.getInstance()
                    val end = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 45) }
                    val url = URL("https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard?dates=${sdf.format(start.time)}-${sdf.format(end.time)}&limit=100")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000; conn.readTimeout = 10000
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val events = org.json.JSONObject(json).optJSONArray("events") ?: JSONArray()
                    val found = mutableListOf<String>()
                    for (i in 0 until events.length()) {
                        val ev = events.getJSONObject(i)
                        val competitors = ev.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors") ?: continue
                        var madrid = false; val rivals = mutableListOf<String>()
                        for (j in 0 until competitors.length()) {
                            val team = competitors.getJSONObject(j).optJSONObject("team") ?: continue
                            val name = team.optString("displayName", team.optString("name"))
                            if (name.contains("Real Madrid", true)) madrid = true else rivals.add(name)
                        }
                        if (madrid) {
                            val raw = ev.optString("date")
                            val d = java.time.Instant.parse(raw).toDate()
                            if (d.after(Date())) found.add("⚽ رئال مادرید — ${rivals.joinToString(" / ")}\\n${persianDate(d)} · ${SimpleDateFormat("HH:mm", Locale.US).format(d)}")
                        }
                    }
                    runOnUiThread {
                        content.removeView(status)
                        if (found.isEmpty()) content.addView(label("بازی‌ای پیدا نشد؛ ممکن است برنامه هنوز منتشر نشده باشد."))
                        else found.forEach { content.addView(label(it, 15f)) }
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "دریافت ناموفق بود؛ اتصال اینترنت یا منبع مسابقات رو بررسی کن." }
                }
            }
        })
    }

    private fun scheduleReminder(t: PlannerTask) {
        if (t.reminder <= 0) return
        val date = parseDate(t.date) ?: return
        val parts = t.time.split(":")
        if (parts.size != 2) return
        val cal = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, parts[0].toIntOrNull() ?: 17)
            set(Calendar.MINUTE, parts[1].toIntOrNull() ?: 0)
            set(Calendar.SECOND, 0)
        }
        val trigger = cal.timeInMillis - t.reminder * 60_000L
        if (trigger <= System.currentTimeMillis()) return
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("title", t.title); putExtra("icon", t.icon); putExtra("reminder", t.reminder)
        }
        val pi = PendingIntent.getBroadcast(this, t.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        else am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("planner_reminders", "یادآوری کارها", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun loadTasks() {
        val arr = try { JSONArray(prefs.getString("tasks", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            tasks.add(PlannerTask(o.optLong("id"), o.optString("title"), o.optString("icon", "✨"),
                o.optString("date"), o.optString("time"), o.optInt("duration", 30), o.optInt("reminder", 15),
                o.optString("note"), o.optBoolean("done", false)))
        }
    }
    private fun saveTasks() {
        val arr = JSONArray(); tasks.forEach { arr.put(it.json()) }
        prefs.edit().putString("tasks", arr.toString()).apply()
    }
    private fun todayIso(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun parseDate(s: String): Date? = try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s) } catch (_: Exception) { null }
    private fun persianDate(d: Date?): String {
        if (d == null) return "تاریخ نامعتبر"
        return SimpleDateFormat("EEEE d MMMM yyyy", persianLocale).format(d)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel("planner_reminders") == null) {
            manager.createNotificationChannel(NotificationChannel("planner_reminders", "یادآوری کارها", NotificationManager.IMPORTANCE_HIGH))
        }
        val title = intent.getStringExtra("title") ?: "وقت انجام کارته"
        val icon = intent.getStringExtra("icon") ?: "🔔"
        val mins = intent.getIntExtra("reminder", 15)
        val whenText = if (mins == 1440) "یک روز" else if (mins == 60) "یک ساعت" else "$mins دقیقه"
        val notification = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(context, "planner_reminders")
        else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        notification.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$icon $title")
            .setContentText("$whenText تا زمان شروع باقی مانده.")
            .setAutoCancel(true)
        manager.notify((title + System.currentTimeMillis()).hashCode(), notification.build())
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // نسخه اولیه: برای بازیابی زمان‌بندی‌ها بعد از راه‌اندازی مجدد، گام بعدی نیازمند بازسازی فهرست آلارم‌ها از ذخیره‌سازی است.
    }
}
