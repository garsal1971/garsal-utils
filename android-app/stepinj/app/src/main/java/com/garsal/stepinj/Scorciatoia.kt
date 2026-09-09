package com.garsal.stepinj

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Una scorciatoia: si sceglie un'app fra quelle installate e il pulsante la apre.
 *
 * ⚠️ **Apre e basta, e non è una limitazione da togliere.** Non fa partire
 * niente prima né dopo: è un collegamento, non una sequenza. Attaccarci davanti
 * altre azioni la trasformerebbe in un'altra cosa.
 */

/** Un'app che si può aprire: il pacchetto è l'identità, il nome è quel che si legge. */
data class AppInstallata(val pacchetto: String, val nome: String)

object Scorciatoia {

    private const val PREFS = "stepinj"
    private const val CHIAVE = "scorciatoia_pacchetto"

    /**
     * Le app che hanno un'icona nel cassetto.
     *
     * ⚠️ Da Android 11 un'app **non vede** i pacchetti installati, a meno che
     * non dichiari cosa cerca: senza il blocco `<queries>` nel manifest questo
     * elenco torna praticamente vuoto, e non con un errore — con un elenco
     * corto, che sembra un telefono senza app. È la via giusta e non
     * `QUERY_ALL_PACKAGES`, che chiede di vedere *tutto* per una cosa che ha
     * bisogno di vedere solo ciò che si può aprire.
     */
    @Suppress("DEPRECATION")
    suspend fun elenco(ctx: Context): List<AppInstallata> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching {
            pm.queryIntentActivities(query, 0)
                .mapNotNull { ri ->
                    val pacchetto = ri.activityInfo?.packageName ?: return@mapNotNull null
                    // Una scorciatoia a sé stessa non porta da nessuna parte.
                    if (pacchetto == ctx.packageName) return@mapNotNull null
                    AppInstallata(pacchetto, ri.loadLabel(pm).toString())
                }
                // Un'app può avere più icone nel cassetto: qui conta il pacchetto.
                .distinctBy { it.pacchetto }
                .sortedBy { it.nome.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun scelta(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CHIAVE, null)
            ?.takeIf { it.isNotBlank() }

    fun scegli(ctx: Context, pacchetto: String?) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (pacchetto == null) p.remove(CHIAVE) else p.putString(CHIAVE, pacchetto)
        p.apply()
    }

    /** Il nome da mostrare per un pacchetto, o `null` se non è più installato. */
    fun nomeDi(ctx: Context, pacchetto: String): String? = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pacchetto, 0)).toString()
    }.getOrNull()

    /**
     * Apre l'app scelta.
     *
     * ⚠️ Torna `false` anche quando il pacchetto c'è ma non ha una schermata da
     * aprire (`getLaunchIntentForPackage` è `null`): capita con i servizi e con
     * le app di sistema senza icona. «Non è installata» e «non si apre» sono due
     * cose diverse, e la schermata le distingue invece di dire genericamente che
     * non ha funzionato.
     */
    fun apri(ctx: Context, pacchetto: String): Boolean = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(pacchetto) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
