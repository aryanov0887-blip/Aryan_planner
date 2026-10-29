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
import android.graphics.drawable.GradientDrawable
import android.icu.text.SimpleDateFormat as IcuDateFormat
import android.icu.util.ULocale
import android.icu.util.PersianCalendar
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
            setTextColor(0xFF172B4D.toInt())
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(7), dp(2), dp(7))
        }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFFFFFFFF.toInt())
        background = rounded(0xFF1769E0.toInt(), 12)
        isAllCaps = false
        elevation = dp(1).toFloat()
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radiusDp: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun addCard(view: View) {
        view.background = rounded(0xFFFFFFFF.toInt(), 16)
        view.elevation = dp(2).toFloat()
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(10)
        content.addView(view, lp)
    }

    private fun baseColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(24))
        setBackgroundColor(0xFFF4F8FF.toInt())
    }

    private fun showUi() {
        val outer = baseColumn()
        val scroller = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(content)
        outer.addView(scroller)
        setContentView(outer)
        content.addView(label("آریان پلنر", 27f, true))
        content.addView(label("برنامه‌ریزی ساده برای روزهای بهتر", 14f))
        val todayCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        todayCard.addView(label("امروز", 13f))
        todayCard.addView(label(persianDate(Date()), 20f, true))
        addCard(todayCard)
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        listOf("خانه" to "home", "کارها" to "tasks", "رئال مادرید" to "matches").forEach { (title, key) ->
            nav.addView(button(title) { tab = key; showUi() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
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
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFFFFFFFF.toInt(), 16)
            elevation = dp(2).toFloat()
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
        val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(10)
        content.addView(row, lp)
    }

    private fun showAddTask() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val title = EditText(this).apply { hint = "اسم کار، مثلاً کتاب‌خوانی"; setSingleLine(true) }
        val note = EditText(this).apply { hint = "یادداشت (اختیاری)"; setSingleLine(true) }
        val date = EditText(this).apply { hint = "تاریخ شمسی YYYY-MM-DD"; setText(todayPersianIso()); setSingleLine(true) }
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
            val parsedInputDate = parseUserDate(dateText)
            if (parsedInputDate == null || !timeText.matches(Regex("\\d{2}:\\d{2}"))) {
                Toast.makeText(this, "تاریخ شمسی را مثل ۱۴۰۵-۰۸-۰۷ و ساعت را مثل 17:00 وارد کن.", Toast.LENGTH_LONG).show(); return@button
            }
            val storedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(parsedInputDate)
            val icons = listOf("📚", "📝", "🏋️", "🧘", "💧", "🎯")
            val reminders = listOf(15, 30, 60, 1440, 0)
            val task = PlannerTask(System.currentTimeMillis(), title.text.toString().trim(),
                icons[icon.selectedItemPosition], storedDate, timeText,
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
        content.addView(label("بازی‌های رئال مادرید", 22f, true))
        content.addView(label("برنامه از تقویم مسابقات ESPN دریافت می‌شود. ساعت‌ها به وقت محلی گوشی نمایش داده می‌شوند.", 13f))
        val status = label("برای دریافت بازی‌های آینده، به‌روزرسانی را بزن.", 14f)
        content.addView(status)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)
        content.addView(button("به‌روزرسانی بازی‌ها") {
            status.text = "در حال دریافت برنامه مسابقات…"
            results.removeAllViews()
            thread {
                var errorText: String? = null
                val found = mutableListOf<Pair<Long, String>>()
                var count = 0
                try {
                    // Team schedule is more reliable than querying a league scoreboard for a 45-day range.
                    val url = URL("https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/teams/86/schedule")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 15000
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "AryanPlanner/1.1 Android")
                    }
                    try {
                        val code = conn.responseCode
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        if (code !in 200..299) throw IllegalStateException("HTTP $code")
                        val json = JSONObject(body)
                        val events = json.optJSONArray("events") ?: json.optJSONObject("teamSchedule")?.optJSONArray("events") ?: JSONArray()
                        count = events.length()
                        for (i in 0 until events.length()) {
                            val ev = events.optJSONObject(i) ?: continue
                            val raw = ev.optString("date")
                            if (raw.isBlank()) continue
                            val date = try { Date.from(java.time.Instant.parse(raw)) } catch (_: Exception) {
                                try { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ", Locale.US).parse(raw) } catch (_: Exception) { null }
                            } ?: continue
                            if (date.before(Date())) continue
                            val comp = ev.optJSONArray("competitions")?.optJSONObject(0)
                            val competitors = comp?.optJSONArray("competitors")
                            var opponent = "حریف مشخص نشده"
                            if (competitors != null) {
                                for (j in 0 until competitors.length()) {
                                    val team = competitors.optJSONObject(j)?.optJSONObject("team") ?: continue
                                    val name = team.optString("displayName", team.optString("name", ""))
                                    if (name.isNotBlank() && !name.contains("Real Madrid", true)) opponent = name
                                }
                            }
                            val localTime = java.text.SimpleDateFormat("HH:mm", Locale.US).format(date)
                            val eventName = ev.optString("name", "Real Madrid")
                            found.add(date.time to "⚽  رئال مادرید
$opponent
${persianDate(date)} · ساعت $localTime")
                        }
                    } finally { conn.disconnect() }
                } catch (e: Exception) {
                    errorText = when (e) {
                        is java.net.UnknownHostException -> "به سرور مسابقات دسترسی نداریم (DNS). اینترنت گوشی ممکن است وصل باشد، اما سرور پاسخ نمی‌دهد."
                        is java.net.SocketTimeoutException -> "سرور مسابقات دیر پاسخ داد؛ دوباره تلاش کن."
                        is org.json.JSONException -> "پاسخ سرور قابل خواندن نبود. بعداً دوباره تلاش کن."
                        else -> "دریافت ناموفق بود: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                runOnUiThread {
                    results.removeAllViews()
                    when {
                        errorText != null -> status.text = errorText
                        found.isEmpty() -> status.text = if (count == 0) "منبع مسابقات فعلاً برنامه‌ای برنگرداند؛ بعداً دوباره امتحان کن." else "بازی آینده‌ای در پاسخ منبع پیدا نشد؛ ممکن است زمان‌بندی مسابقات هنوز اعلام نشده باشد."
                        else -> {
                            status.text = "${found.size} مسابقه پیدا شد"
                            found.sortedBy { it.first }.forEach { (_, text) ->
                                val card = LinearLayout(this).apply {
                                    orientation = LinearLayout.VERTICAL
                                    setPadding(dp(16), dp(12), dp(16), dp(12))
                                }
                                text.split("\n").forEachIndexed { idx, line -> card.addView(label(line, if (idx == 0) 17f else 14f, idx == 0)) }
                                addCard(card)
                            }
                        }
                    }
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
    private fun todayPersianIso(): String = IcuDateFormat("yyyy-MM-dd", ULocale("fa_IR@calendar=persian")).format(Date())
    private fun parseUserDate(value: String): Date? {
        val normalized = value.trim()
            .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
            .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
            .replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4')
            .replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')
        val parts = normalized.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (y < 1300 || m !in 1..12 || d !in 1..31) return null
        return try {
            val cal = PersianCalendar(ULocale("fa_IR@calendar=persian"))
            cal.clear()
            cal.set(PersianCalendar.YEAR, y)
            cal.set(PersianCalendar.MONTH, m - 1)
            cal.set(PersianCalendar.DAY_OF_MONTH, d)
            cal.time
        } catch (_: Exception) { null }
    }
    private fun parseDate(s: String): Date? = try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s) } catch (_: Exception) { null }
    private fun persianDate(d: Date?): String {
        if (d == null) return "تاریخ نامعتبر"
        return try {
            IcuDateFormat("EEEE d MMMM yyyy", ULocale("fa_IR@calendar=persian")).format(d)
        } catch (_: Exception) {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
        }
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
