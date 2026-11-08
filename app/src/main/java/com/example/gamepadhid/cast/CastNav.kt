package com.example.gamepadhid.cast

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings

object CastNav {
    /** Abre el panel de Android donde se elige la TV Miracast. false si el equipo no lo ofrece. */
    fun openCastSettings(activity: Activity): Boolean = try {
        activity.startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
