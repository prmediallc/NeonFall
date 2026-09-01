package com.neonfall.game

import android.content.Context

/** Tiny local save file. No accounts, no network. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("neonfall", Context.MODE_PRIVATE)

    var bestScore: Int
        get() = sp.getInt("best_score", 0)
        set(v) = sp.edit().putInt("best_score", v).apply()

    var bestStage: Int
        get() = sp.getInt("best_stage", 0)
        set(v) = sp.edit().putInt("best_stage", v).apply()

    var gamesPlayed: Int
        get() = sp.getInt("games", 0)
        set(v) = sp.edit().putInt("games", v).apply()
}
