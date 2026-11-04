package com.garsal.stepinj

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 🔋 Tenere StepInj al lavoro a display spento (v1.10.0).
 *
 * ⚠️ **Il servizio in primo piano da solo non basta**: Android non lo chiude, ma a
 * schermo spento il processore si addormenta, e i `delay` della camminata e del
 * mock scattano solo al risveglio successivo — i passi arrivano a blocchi e la
 * posizione finta invecchia. Il **wake lock parziale** tiene acceso il processore
 * (non lo schermo) finché il giro dura, con un tetto: un lock dimenticato è una
 * batteria scarica.
 */
object Sveglia {

    /** Un wake lock parziale che si rilascia da solo dopo [millis]. */
    @SuppressLint("WakelockTimeout")
    fun prendi(context: Context, nome: String, millis: Long): PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StepInj:$nome").apply {
            setReferenceCounted(false)
            acquire(millis)
        }
    }.getOrNull()

    fun lascia(lock: PowerManager.WakeLock?) {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }

    /** StepInj è ancora sotto il risparmio energetico di Android? */
    fun ottimizzata(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        !pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * Apre la richiesta di sistema per escludere StepInj dal risparmio energetico;
     * se il telefono non la offre, l'elenco delle app escluse.
     */
    @SuppressLint("BatteryLife")
    fun chiediEsclusione(context: Context) {
        val diretta = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(diretta) }.isSuccess) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Xiaomi, Redmi e POCO: MIUI/HyperOS chiude i servizi anche con la notifica. */
    fun xiaomi(): Boolean = Build.MANUFACTURER.lowercase().let {
        "xiaomi" in it || "redmi" in it || "poco" in it
    }

    /** La pagina di StepInj nelle impostazioni, da dove si arriva ad Avvio automatico e batteria. */
    fun apriScheda(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
