package ru.mahnet.seller

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * Приложение продавца: окно с кабинетом продавца сайта mahnet.ru в режиме приложения
 * (адрес с ?app=seller). Меню вкладок, их порядок и названия задаёт администратор, а продавец сам
 * выбирает, показывать вкладки списком или столбиком.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Адрес приложения продавца. Поменяйте здесь, если сайт переедет.
        const val START_URL = "https://mahnet.ru/?app=seller#seller"
        const val HOST_SUFFIX = "mahnet.ru"
        const val SITE_URL = "https://mahnet.ru"
    }

    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var camUri: Uri? = null

    // Выбор фото и файлов для кнопки «Выбрать файл» на сайте (добавление товара, документы)
    private val chooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = filePathCallback
            filePathCallback = null
            if (cb == null) return@registerForActivityResult
            if (res.resultCode == Activity.RESULT_OK) {
                val picked = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
                val cam = camUri
                if (picked == null && cam != null) {
                    // снимок с камеры: система кладёт его по нашему адресу, а не в ответ
                    cb.onReceiveValue(arrayOf(cam))
                } else {
                    cb.onReceiveValue(picked)
                }
            } else {
                cb.onReceiveValue(null)
            }
            camUri = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Всегда светлая тема, даже если на телефоне включена тёмная
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        web = WebView(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.userAgentString = s.userAgentString + " MarketSellerApp/1.0"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        restoreLogin()

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                // Для фото: выбор «Камера» или файлы телефона (если сайт просит снимок — сразу камера)
                val wantsImage = params.acceptTypes.isEmpty() || params.acceptTypes.any { it.startsWith("image") || it.isEmpty() }
                var camIntent: Intent? = null
                camUri = null
                if (wantsImage) {
                    try {
                        val dir = File(cacheDir, "photos")
                        dir.mkdirs()
                        val f = File.createTempFile("photo_", ".jpg", dir)
                        val uri = FileProvider.getUriForFile(this@MainActivity, packageName + ".fileprovider", f)
                        val ci = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        ci.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        ci.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        camIntent = ci
                        camUri = uri
                    } catch (e: Exception) {
                        camIntent = null
                        camUri = null
                    }
                }
                return try {
                    val launch: Intent = if (params.isCaptureEnabled && camIntent != null) {
                        camIntent
                    } else {
                        val ch = Intent.createChooser(params.createIntent(), "Выберите фото или снимите на камеру")
                        if (camIntent != null) ch.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camIntent))
                        ch
                    }
                    chooser.launch(launch)
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    Toast.makeText(this@MainActivity, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val u = request.url
                val scheme = u.scheme ?: ""
                val host = u.host ?: ""
                val external = scheme == "tel" || scheme == "mailto" || scheme == "geo" ||
                    ((scheme == "http" || scheme == "https") && !host.endsWith(HOST_SUFFIX))
                if (external) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, "Нет приложения для этой ссылки", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                saveLogin()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                        "<body style='font-family:sans-serif;text-align:center;padding:48px 20px;background:#f1f4f2;color:#17201b'>" +
                        "<h2>Нет связи с сервером</h2><p>Проверьте интернет и повторите.</p>" +
                        "<button style='font-size:18px;padding:14px 28px;border:0;border-radius:12px;background:#0b6b45;color:#fff' " +
                        "onclick=\"location.href='" + START_URL + "'\">Повторить</button></body></html>"
                    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "AndroidSeller")
        web.loadUrl(START_URL)
        Updater.check(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    // из вкладки возвращаемся в меню; из меню — закрываем приложение
                    web.evaluateJavascript(
                        "(function(){var b=document.querySelector('[data-sapp35o=\"\"]');if(b){b.click();return 1}return 0})()"
                    ) { r ->
                        if (r != "1") {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        })
    }

    // Мост для сайта: сайт узнаёт, что он открыт внутри приложения (window.AndroidSeller)
    inner class Bridge {
        @JavascriptInterface
        fun isApp(): Boolean = true

        @JavascriptInterface
        fun logout() {
            // сессия продавца закрывается самим сайтом; здесь ничего делать не нужно
        }
    }

    // Вход продавца хранится до нажатия «Выйти»: cookie сразу пишется на диск и дублируется в памяти приложения
    private fun saveLogin() {
        val cm = CookieManager.getInstance()
        val c = cm.getCookie(SITE_URL) ?: ""
        getSharedPreferences("login", MODE_PRIVATE).edit().putString("c", c).apply()
        cm.flush()
    }

    private fun restoreLogin() {
        val cm = CookieManager.getInstance()
        val saved = getSharedPreferences("login", MODE_PRIVATE).getString("c", "") ?: ""
        if (saved.isBlank()) return
        val now = cm.getCookie(SITE_URL) ?: ""
        saved.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { kv ->
            val name = kv.substringBefore("=")
            if (!now.contains("$name=")) {
                cm.setCookie(SITE_URL, "$kv; Max-Age=31536000; Path=/; Secure")
            }
        }
        cm.flush()
    }

    override fun onPause() {
        saveLogin()
        super.onPause()
    }

    override fun onStop() {
        saveLogin()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Updater.resume(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        web.removeJavascriptInterface("AndroidSeller")
    }
}
