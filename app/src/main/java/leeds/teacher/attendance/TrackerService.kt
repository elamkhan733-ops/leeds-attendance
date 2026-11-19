package leeds.teacher.attendance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
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
import java.util.concurrent.Executors

class TrackerService : Service() {

    private var api = ""
    private var k = ""
    private var dev = ""
    private var ck = ""
    private var ua = ""
    private var t0 = 0L
    private var lastT = 0L
    private var lastLa = 0.0
    private var lastLo = 0.0
    private var offSent = false
    private val ex = Executors.newSingleThreadExecutor()
    private var fl: FusedLocationProviderClient? = null

    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            offSent = false
            val now = System.currentTimeMillis()
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
            if (!a.isLocationAvailable && !offSent) {
                offSent = true
                val body = JSONObject().put("k", k).put("dev", dev).put("why", "off").toString()
                ex.execute { post("locoff", body) }
            }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        // Foreground notification fauran dikhana zaroori hai
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("trk", "Attendance tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "trk")
            .setContentTitle("Leeds School")
            .setContentText("Attendance location tracking chal rahi hai")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)

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

        val client = LocationServices.getFusedLocationProviderClient(this)
        fl = client
        client.removeLocationUpdates(cb)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L).build()
        try {
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        fl?.removeLocationUpdates(cb)
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

    private fun post(a: String, body: String): Int {
        return try {
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
            val code = c.responseCode
            c.disconnect()
            code
        } catch (e: Exception) { 0 }
    }
}
