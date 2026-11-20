package leeds.teacher.attendance

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    companion object {
        const val PAGE = "https://leedsschool.infinityfree.io/teacher-attendance.html"
    }

    private lateinit var web: WebView
    private var pending: Array<String>? = null
    private var geoCb: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null

    private fun hasLoc() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setGeolocationEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)

        web.addJavascriptInterface(Bridge(), "LeedsNative")

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) {
                if (hasLoc()) {
                    callback?.invoke(origin, true, false)
                } else {
                    geoOrigin = origin
                    geoCb = callback
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ), 1
                    )
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                        "<body style='font-family:sans-serif;text-align:center;padding:40px'>" +
                        "<h3>Internet nahi hai</h3><p>Internet check karein aur dobara koshish karein.</p>" +
                        "<button style='font-size:18px;padding:12px 24px' onclick=\"location.href='$PAGE'\">Dobara koshish karein</button>" +
                        "</body></html>"
                    view?.loadData(html, "text/html; charset=utf-8", null)
                }
            }
        }

        web.loadUrl(PAGE)
    }

    inner class Bridge {
        @JavascriptInterface
        fun start(api: String, k: String, dev: String, cookie: String, ua: String) {
            runOnUiThread {
                pending = arrayOf(api, k, dev, cookie, ua)
                ensureAndStart()
            }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread {
                stopService(Intent(this@MainActivity, TrackerService::class.java))
                getSharedPreferences("trk", 0).edit().clear().apply()
            }
        }
    }

    private fun ensureAndStart() {
        val need = ArrayList<String>()
        if (!hasLoc()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)

        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 2)
            return
        }
        launchService()
    }

    private fun launchService() {
        val p = pending ?: return
        pending = null
        val i = Intent(this, TrackerService::class.java)
            .putExtra("api", p[0]).putExtra("k", p[1]).putExtra("dev", p[2])
            .putExtra("cookie", p[3]).putExtra("ua", p[4])
        startForegroundService(i)
        askBg()
    }

    // "Allow all the time" - taake app band hone par bhi location chalti rahe
    private fun askBg() {
        val sp = getSharedPreferences("app", 0)
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            !sp.getBoolean("bgAsked", false)
        ) {
            sp.edit().putBoolean("bgAsked", true).apply()
            AlertDialog.Builder(this)
                .setMessage("Agli screen par Location ke liye 'Allow all the time' (Hamesha allow) chunein, taake app band hone par bhi attendance tracking chalti rahe.")
                .setPositiveButton("Theek hai") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 3)
                }
                .setCancelable(false)
                .show()
        } else askBattery()
    }

    // Battery optimization: sirf ek dafa poochein
    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val sp = getSharedPreferences("app", 0)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !sp.getBoolean("batAsked", false)) {
            sp.edit().putBoolean("batAsked", true).apply()
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) { }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        geoCb?.let {
            it.invoke(geoOrigin, hasLoc(), false)
            geoCb = null
        }
        if (requestCode == 3) askBattery()
        if (requestCode == 2) {
            if (hasLoc()) launchService()
            else {
                pending = null
                web.evaluateJavascript("window.nativeDenied&&nativeDenied()", null)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }
}
