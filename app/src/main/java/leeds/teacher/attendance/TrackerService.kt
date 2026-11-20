package leeds.teacher.attendance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class TrackerService : Service() {

    private var api = ""
    private var k = ""
    private var dev = ""
    private var ck = ""
    private var ua = ""
    private var t0 = 0L
    @Volatile private var lastT = 0L
    @Volatile private var lastResult = 0L
    private var lastLa = 0.0
    private var lastLo = 0.0
    @Volatile private var offSent = false
    private val ex = Executors.newSingleThreadExecutor()
    private var fl: FusedLocationProviderClient? = null
    private var wl: PowerManager.WakeLock? = null
    private val h = Handler(Looper.getMainLooper())
    private var recv: BroadcastReceiver? = null

    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            val now = System.currentTimeMillis()
            lastResult = now
            offSent = false
            if (now - lastT < 4000) return
            if (lastT != 0L && now - lastT < 20000 &&
                dist(lastLa, lastLo, l.latitude, l.longitude) < 3.0
            ) return
            lastT = now; lastLa = l.latitude; lastLo = l.longitude

            // 10 ghante baad khud band (school ka din khatam)
            if (now - t0 > 10 * 3600 * 1000L) {
                clear(); stopSelf(); return
            }
            val body = JSONObject().put("k", k).put("dev", dev)
                .put("lat", l.latitude).put("lng", l.longitude)
                .put("acc", Math.round(l.accuracy)).toString()
            ex.execute {
                if (post("ping", body) == 403) { clear(); stopSelf() }
            }
        }

        override fun onLocationAvailability(a: LocationAvailability) {
            if (!a.isLocationAvailable) sendOff()
        }
    }

    // Har 20 second: agar location kaafi der se nahi aayi aur GPS on hai, dobara shuru karo
    private val dog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (locEnabled() && now - lastResult > 45000) requestUpdates()
            h.postDelayed(this, 20000)
        }
    }

    private fun locEnabled(): Boolean = try {
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
    } catch (e: Exception) { true }

    private fun sendOff() {
        if (offSent) return
        offSent = true
        val body = JSONObject().put("k", k).put("dev", dev).put("why", "off").toString()
        ex.execute { post("locoff", body) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "trk")
            .setContentTitle("Leeds School - attendance tracking")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun showStatus(a: String, info: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, buildNotif("Aakhri $a: $t - $info"))
        } catch (e: Exception) { }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("trk", "Attendance tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotif("Attendance location tracking chal rahi hai")
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val sp = getSharedPreferences("trk", 0)
        if (i != null && i.hasExtra("k")) {
            sp.edit()
                .putString("api", i.getStringExtra("api"))
                .putString("k", i.getStringExtra("k"))
                .putString("dev", i.getStringExtra("dev"))
                .putString("cookie", i.getStringExtra("cookie"))
                .putString("ua", i.getStringExtra("ua"))
                .putLong("t0", System.currentTimeMillis())
                .apply()
        }
        api = sp.getString("api", "") ?: ""
        k = sp.getString("k", "") ?: ""
        dev = sp.getString("dev", "") ?: ""
        ck = sp.getString("cookie", "") ?: ""
        ua = sp.getString("ua", "") ?: ""
        t0 = sp.getLong("t0", System.currentTimeMillis())
        if (k.isEmpty() || api.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // CPU jagta rakhne ke liye (screen band ho tab bhi)
        if (wl == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leeds:trk")
            try { wl?.acquire(10 * 3600 * 1000L) } catch (e: Exception) { }
        }

        // GPS on/off ka foran pata chalne ke liye
        if (recv == null) {
            recv = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (locEnabled()) {
                        offSent = false
                        requestUpdates()
                    } else sendOff()
                }
            }
            val f = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(recv, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(recv, f)
        }

        lastResult = System.currentTimeMillis()
        requestUpdates()
        h.removeCallbacks(dog)
        h.postDelayed(dog, 20000)
        if (!locEnabled()) sendOff()
        return START_STICKY
    }

    private fun requestUpdates() {
        val client = fl ?: LocationServices.getFusedLocationProviderClient(this).also { fl = it }
        client.removeLocationUpdates(cb)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L).build()
        try {
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            showStatus("location", "permission nahi hai")
        }
    }

    override fun onDestroy() {
        h.removeCallbacks(dog)
        fl?.removeLocationUpdates(cb)
        try { recv?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        try { wl?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        ex.shutdown()
        super.onDestroy()
    }

    private fun clear() {
        getSharedPreferences("trk", 0).edit().clear().apply()
    }

    private fun dist(a: Double, b: Double, c: Double, d: Double): Double {
        val r = 6371000.0
        val x = Math.toRadians(c - a)
        val y = Math.toRadians(d - b)
        val m = Math.sin(x / 2) * Math.sin(x / 2) +
            Math.cos(Math.toRadians(a)) * Math.cos(Math.toRadians(c)) *
            Math.sin(y / 2) * Math.sin(y / 2)
        return 2 * r * Math.asin(Math.sqrt(m))
    }

    private fun cookieNow(): String = try {
        CookieManager.getInstance().getCookie(api) ?: ck
    } catch (e: Exception) { ck }

    // Return: HTTP code; -1 agar hosting ne HTML (bot-check) bheja; 0 agar internet nahi
    private fun post(a: String, body: String): Int {
        var code = 0
        var info: String
        try {
            val c = URL(api + "teacherattend.php?a=" + a).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Requested-With", "fetch")
            val cookie = cookieNow()
            if (cookie.isNotEmpty()) c.setRequestProperty("Cookie", cookie)
            if (ua.isNotEmpty()) c.setRequestProperty("User-Agent", ua)
            c.outputStream.use { it.write(body.toByteArray()) }
            code = c.responseCode
            val st = if (code in 200..399) c.inputStream else c.errorStream
            val txt = try { st?.bufferedReader()?.use { it.readText().take(300) } ?: "" } catch (e: Exception) { "" }
            c.disconnect()
            if (txt.trimStart().startsWith("<")) {
                code = -1
                info = "HOSTING ne rok diya (HTML)"
            } else info = if (code in 200..299) "OK $code" else "error $code"
        } catch (e: Exception) {
            code = 0
            info = "internet/server nahi mila"
        }
        showStatus(a, info)
        return code
    }
}
