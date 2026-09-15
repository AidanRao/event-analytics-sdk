package top.aidanrao.analytics.example

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import top.aidanrao.analytics.Analytics

class MainActivity : Activity() {
    private lateinit var sdk: Analytics
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        val status = TextView(this).apply { text = "Local endpoint: 10.0.2.2:8787 / app: demo" }
        sdk = Analytics.Builder(applicationContext, "http://10.0.2.2:8787/v1/events", "demo")
            .onError { error -> runOnUiThread { status.text = error.toString() } }.build()
        layout.addView(status)
        layout.addView(Button(this).apply { text = "Track Kotlin event"; setOnClickListener { status.text = sdk.track("button_clicked", mapOf("source" to "android-kotlin")) } })
        layout.addView(Button(this).apply { text = "Track Java event"; setOnClickListener { JavaExample.track(sdk) } })
        layout.addView(Button(this).apply { text = "Flush"; setOnClickListener { sdk.flush { result -> runOnUiThread { status.text = result.toString() } } } })
        setContentView(layout)
    }
    override fun onDestroy() { sdk.close(); super.onDestroy() }
}
