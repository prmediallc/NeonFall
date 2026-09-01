package com.neonfall.game

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MenuActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)
        prefs = Prefs(this)

        findViewById<Button>(R.id.btnPlay).setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java))
        }
        findViewById<Button>(R.id.btnHow).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.how_to_play)
                .setMessage(R.string.how_to_play_body)
                .setPositiveButton(R.string.got_it, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.bestScore).text = prefs.bestScore.toString()
        findViewById<TextView>(R.id.bestStage).text = prefs.bestStage.toString()
    }
}
