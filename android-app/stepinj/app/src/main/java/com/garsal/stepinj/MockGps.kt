package com.garsal.stepinj

import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Il mock del GPS: si dà un elenco di coordinate, quanti secondi stare su
 * ciascuna e per quanti minuti in tutto, e il telefono dice di essere lì.
 *
 * ⚠️ **Non è una cosa che l'app possa fare da sé.** Android accetta posizioni
 * finte solo dall'app che l'utente ha scelto in *Opzioni sviluppatore → App per
 * posizioni fittizie*; a chiunque altro `addTestProvider` risponde con una
 * SecurityException. Per questo l'errore va **riconosciuto e spiegato**, non
 * lasciato passare come un guasto generico: è l'unica cosa che l'utente deve
 * fare a mano, e senza quella frase sembrerebbe un'app rotta.
 */

/** Una riga dell'elenco. */
data class Punto(val lat: Double, val lon: Double) {
    /** Come si scrive nelle preferenze e come si mostra: sei decimali, ~11 cm. */
    fun testo(): String = "%.6f, %.6f".format(java.util.Locale.US, lat, lon)
    fun chiave(): String = "%.6f,%.6f".format(java.util.Locale.US, lat, lon)
}

/** Quel che la schermata guarda mentre il servizio lavora. */
object MockStato {
    val attivo = MutableStateFlow(false)
    /** Indice del punto su cui si è adesso, −1 quando è fermo. */
    val indice = MutableStateFlow(-1)
    /** Secondi che restano prima che si fermi da sé. */
    val restanti = MutableStateFlow(0L)
    /** L'ultima cosa successa, da scrivere a schermo. */
    val messaggio = MutableStateFlow("")
}

object MockGps {

    private const val PREFS = "stepinj"
    private const val CHIAVE_PUNTI = "mock_punti"
    private const val CHIAVE_SECONDI = "mock_secondi"
    private const val CHIAVE_MINUTI = "mock_minuti"
    private const val CHIAVE_SCELTI = "mock_scelti"

    /** Quanto dura un giro se nessuno l'ha ancora deciso. */
    const val SECONDI_DI_PARTENZA = 5
    const val MINUTI_DI_PARTENZA = 5

    /** I due tempi di un giro: quanto si sta su una coordinata, e quanto dura in tutto. */
    data class Tempi(val secondi: Int, val minuti: Int)

    /**
     * I tre provider che si fingono.
     *
     * ⚠️ **Non basta il solo GPS.** Le app moderne non leggono
     * `GPS_PROVIDER`: chiedono la posizione al *fused provider* di Play
     * Services, che mescola GPS, rete e sensori. Mockando solo il GPS il
     * telefono resterebbe dov'è davvero — e sembrerebbe che il mock non
     * funzioni, mentre sta funzionando su un provider che nessuno guarda.
     * `"fused"` è `LocationManager.FUSED_PROVIDER`, che esiste come costante
     * solo dall'API 31: qui è scritto a mano perché la stringa è la stessa da
     * sempre e il `minSdk` è 26.
     */
    private val PROVIDER = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        "fused",
    )

    // ── L'elenco salvato ─────────────────────────────────────────────────────
    // Nelle preferenze e non in un database: l'app si deve aprire e funzionare
    // senza rete, che è il caso in cui un mock serve davvero.

    fun leggiPunti(ctx: Context): List<Punto> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CHIAVE_PUNTI, "")
            .orEmpty()
            .lineSequence()
            .mapNotNull { puntoDa(it) }
            .toList()

    fun salvaPunti(ctx: Context, punti: List<Punto>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CHIAVE_PUNTI, punti.joinToString("\n") { it.chiave() })
            .apply()
    }

    /**
     * I due tempi e le coordinate spuntate, che stanno nelle preferenze **come
     * l'elenco** e non nella sola schermata.
     *
     * ⚠️ Fino alla v1.2.0 vivevano in un `remember` della schermata: si perdevano
     * chiudendo l'app, e ogni giro ripartiva da 5 e 5 senza che niente lo dicesse. È il
     * pulsantone ⚡ a renderlo un difetto vero — lui la schermata non la apre affatto,
     * quindi senza queste preferenze userebbe dei tempi che l'utente non ha mai scelto.
     */
    fun leggiTempi(ctx: Context): Tempi {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Tempi(
            p.getInt(CHIAVE_SECONDI, SECONDI_DI_PARTENZA).coerceAtLeast(1),
            p.getInt(CHIAVE_MINUTI, MINUTI_DI_PARTENZA).coerceAtLeast(1),
        )
    }

    fun salvaTempi(ctx: Context, secondi: Int, minuti: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(CHIAVE_SECONDI, secondi.coerceAtLeast(1))
            .putInt(CHIAVE_MINUTI, minuti.coerceAtLeast(1))
            .apply()
    }

    /**
     * Le chiavi spuntate. ⚠️ **Vuoto vuol dire «tutte»**, ed è la stessa regola della
     * schermata: è il caso più frequente e non merita una spunta per riga. Le chiavi si
     * rileggono filtrate su quelle che esistono ancora — una coordinata cancellata
     * lascerebbe altrimenti una spunta che non si vede e che restringe il giro.
     */
    fun leggiScelti(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CHIAVE_SCELTI, "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun salvaScelti(ctx: Context, chiavi: Collection<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CHIAVE_SCELTI, chiavi.joinToString("\n"))
            .apply()
    }

    /**
     * Le coordinate che un giro userebbe adesso: le spuntate, o tutte se non ce n'è
     * nessuna.
     *
     * ⚠️ Sta qui e non nelle due schermate: la regola «nessuna spunta = tutte» scritta
     * due volte sono due giri diversi il giorno che una delle due cambia — e il
     * pulsantone ⚡ userebbe coordinate diverse da quelle che la scheda 📍 dichiara.
     */
    fun daUsare(ctx: Context): List<Punto> {
        val punti = leggiPunti(ctx)
        val scelti = leggiScelti(ctx)
        val filtrati = punti.filter { scelti.contains(it.chiave()) }
        return if (filtrati.isEmpty()) punti else filtrati
    }

    /**
     * Legge una riga «lat,lon».
     *
     * ⚠️ Accetta anche la virgola decimale, che è come le scrive una tastiera
     * italiana: `44,5072 11,3621`. Si distingue dal separatore guardando
     * **quante virgole ci sono** — due virgole e nessun punto vogliono dire
     * decimali all'italiana. Rifiutare quelle righe vorrebbe dire un elenco che
     * non si riesce a incollare proprio dal telefono su cui gira l'app.
     * ⚠️ Torna `null` e non un punto a (0,0): lo zero è un posto vero, nel
     * golfo di Guinea, e un errore di battitura non deve diventare una
     * coordinata plausibile.
     */
    fun puntoDa(riga: String): Punto? {
        val pulita = riga.trim().removeSurrounding("(", ")")
        if (pulita.isEmpty()) return null

        val pezzi: List<String> = when {
            pulita.count { it == ',' } == 2 && !pulita.contains('.') ->
                pulita.split(Regex("[;\\s]+")).map { it.replace(',', '.') }
            else ->
                pulita.split(Regex("[,;\\s]+"))
        }
        if (pezzi.size < 2) return null

        val lat = pezzi[0].toDoubleOrNull() ?: return null
        val lon = pezzi[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Punto(lat, lon)
    }

    // ── Il mock vero ─────────────────────────────────────────────────────────

    /** Quel che manca perché il mock possa partire, o `null` se è tutto pronto. */
    fun perche(ctx: Context): String? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "Questo telefono non espone il servizio di posizione."
        return try {
            accendi(lm)
            spegni(lm)
            null
        } catch (e: SecurityException) {
            "Questa app non è ancora quella scelta per le posizioni fittizie. " +
                "Apri Opzioni sviluppatore → «App per posizioni fittizie» e scegli StepInj."
        } catch (e: Exception) {
            e.message ?: "Errore sconosciuto"
        }
    }

    /** Apre le impostazioni dove si fa quella scelta. */
    fun apriOpzioniSviluppatore(ctx: Context): Boolean = runCatching {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    /** Apre un punto in Google Maps (o in qualunque app di mappe ci sia). */
    fun apriInMappa(ctx: Context, p: Punto): Boolean = runCatching {
        val uri = Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}")
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun accendi(lm: LocationManager) {
        for (p in PROVIDER) {
            // ⚠️ Un provider per volta e in runCatching: `"fused"` non si può
            // mockare su alcuni telefoni, e su altri la rete non c'è affatto.
            // Fermarsi al primo rifiuto vorrebbe dire niente mock nemmeno dove
            // il GPS si sarebbe lasciato fingere.
            runCatching {
                lm.addTestProvider(
                    p,
                    false, false, false, false,   // rete, satellite, cella, a pagamento
                    true, true, true,             // quota, velocità, direzione
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE,
                )
                lm.setTestProviderEnabled(p, true)
            }
        }
        // Se NESSUNO è passato, il mock non parte: lo si scopre qui e non dopo
        // cinque minuti di countdown senza che il telefono si sia mosso.
        val acceso = PROVIDER.any { p ->
            runCatching { lm.setTestProviderEnabled(p, true); true }.getOrDefault(false)
        }
        if (!acceso) throw SecurityException("nessun provider si lascia mockare")
    }

    fun spegni(lm: LocationManager) {
        for (p in PROVIDER) {
            // ⚠️ Il provider finto va TOLTO, non solo spento: lasciandolo
            // registrato il telefono continua a rispondere con l'ultima
            // posizione finta anche ad app aperte dopo — cioè un mock che non
            // si vede più da nessuna parte e che nessuno sa come fermare.
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
    }

    /**
     * Scrive una posizione su tutti i provider finti.
     *
     * ⚠️ `elapsedRealtimeNanos` **non è facoltativo**: dall'API 17
     * `setTestProviderLocation` rifiuta una posizione che non ce l'ha, con una
     * IllegalArgumentException che non nomina il campo mancante. È il primo
     * posto da guardare se il mock smette di funzionare.
     */
    fun scrivi(lm: LocationManager, p: Punto) {
        for (provider in PROVIDER) {
            runCatching {
                val loc = Location(provider).apply {
                    latitude = p.lat
                    longitude = p.lon
                    altitude = 0.0
                    accuracy = 3f
                    speed = 0f
                    bearing = 0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 3f
                        speedAccuracyMetersPerSecond = 1f
                        bearingAccuracyDegrees = 1f
                    }
                }
                lm.setTestProviderLocation(provider, loc)
            }
        }
    }
}
