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
    /**
     * Le coordinate del giro in corso, vuoto quando è fermo.
     *
     * ⚠️ Il servizio le rilegge **a ogni secondo** da qui, e non le tiene solo nella
     * sua copia presa all'avvio: le frecce N/S/E/O della schermata le sostituiscono
     * anche qui, e il telefono si sposta al secondo dopo senza riavviare il giro —
     * che azzererebbe il countdown di un giro che sta lavorando.
     */
    val punti = MutableStateFlow<List<Punto>>(emptyList())
}

/** Una lista di coordinate con un nome. L'id non cambia rinominandola. */
data class Lista(val id: String, val nome: String)

/** Le quattro frecce. */
enum class Direzione(val etichetta: String) {
    NORD("⬆️ N"), SUD("⬇️ S"), OVEST("⬅️ O"), EST("➡️ E")
}

object MockGps {

    private const val PREFS = "stepinj"
    private const val CHIAVE_PUNTI = "mock_punti"
    private const val CHIAVE_SECONDI = "mock_secondi"
    private const val CHIAVE_MINUTI = "mock_minuti"
    private const val CHIAVE_SCELTI = "mock_scelti"
    private const val CHIAVE_LISTE = "mock_liste"
    private const val CHIAVE_ATTIVA = "mock_lista_attiva"
    private const val CHIAVE_MODIFICA = "mock_modifica"
    private const val CHIAVE_METRI = "mock_metri"

    /** Quanto dura un giro se nessuno l'ha ancora deciso. */
    const val SECONDI_DI_PARTENZA = 5
    const val MINUTI_DI_PARTENZA = 5
    /** Di quanto si sposta una freccia se nessuno l'ha ancora deciso. */
    const val METRI_DI_PARTENZA = 10

    /** Metri in un grado di latitudine (e di longitudine all'equatore). */
    private const val METRI_PER_GRADO = 111_320.0

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

    // ── Le liste ─────────────────────────────────────────────────────────────
    // Nelle preferenze e non in un database: l'app si deve aprire e funzionare
    // senza rete, che è il caso in cui un mock serve davvero.
    //
    // ⚠️ Ogni lista ha le sue coordinate e le sue spunte, in `mock_punti_<id>` e
    // `mock_scelti_<id>`, e se ne usa UNA alla volta: quella attiva. È lei che
    // leggono sia la scheda 📍 sia il pulsantone ⚡ — due liste attive diverse
    // sarebbero due giri diversi per lo stesso tocco.

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Le liste, nell'ordine in cui sono state create. Una riga «id\tnome».
     *
     * ⚠️ Fino alla v1.3.0 la lista era una sola, in `mock_punti` e `mock_scelti`
     * senza id: la prima lettura la travasa in una lista «Principale», o chi
     * aggiorna l'app si ritroverebbe l'elenco vuoto e le coordinate perse.
     */
    fun leggiListe(ctx: Context): List<Lista> {
        val p = prefs(ctx)
        val righe = p.getString(CHIAVE_LISTE, null)
        if (righe == null) {
            val prima = Lista("1", "Principale")
            p.edit()
                .putString(CHIAVE_LISTE, "${prima.id}\t${prima.nome}")
                .putString(CHIAVE_ATTIVA, prima.id)
                .putString(chiavePunti(prima.id), p.getString(CHIAVE_PUNTI, "").orEmpty())
                .putString(chiaveScelti(prima.id), p.getString(CHIAVE_SCELTI, "").orEmpty())
                .remove(CHIAVE_PUNTI)
                .remove(CHIAVE_SCELTI)
                .apply()
            return listOf(prima)
        }
        return righe.lineSequence()
            .mapNotNull { r ->
                val pezzi = r.split('\t', limit = 2)
                if (pezzi.size == 2 && pezzi[0].isNotBlank()) Lista(pezzi[0], pezzi[1]) else null
            }
            .toList()
            .ifEmpty { listOf(Lista("1", "Principale")) }
    }

    private fun salvaListe(ctx: Context, liste: List<Lista>) {
        prefs(ctx).edit()
            .putString(CHIAVE_LISTE, liste.joinToString("\n") { "${it.id}\t${it.nome}" })
            .apply()
    }

    /** La lista attiva; se quella scritta non esiste più, la prima. */
    fun listaAttiva(ctx: Context): Lista {
        val liste = leggiListe(ctx)
        val id = prefs(ctx).getString(CHIAVE_ATTIVA, null)
        return liste.firstOrNull { it.id == id } ?: liste.first()
    }

    fun scegliLista(ctx: Context, id: String) {
        prefs(ctx).edit().putString(CHIAVE_ATTIVA, id).apply()
    }

    /**
     * Crea una lista e la rende attiva. Con [da] ne clona le coordinate — non le
     * spunte: una lista nuova parte senza, cioè «si usano tutte».
     */
    fun nuovaLista(ctx: Context, nome: String, da: Lista?): Lista {
        val liste = leggiListe(ctx)
        val id = ((liste.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        val nuova = Lista(id, nome.trim())
        salvaListe(ctx, liste + nuova)
        salvaPunti(ctx, nuova, if (da != null) leggiPunti(ctx, da) else emptyList())
        salvaScelti(ctx, nuova, emptyList())
        scegliLista(ctx, nuova.id)
        return nuova
    }

    fun rinominaLista(ctx: Context, id: String, nome: String) {
        salvaListe(ctx, leggiListe(ctx).map { if (it.id == id) it.copy(nome = nome.trim()) else it })
    }

    /** Toglie una lista. ⚠️ L'ultima non si toglie: senza liste non c'è niente da attivare. */
    fun eliminaLista(ctx: Context, id: String): Boolean {
        val liste = leggiListe(ctx)
        if (liste.size <= 1) return false
        val resto = liste.filter { it.id != id }
        salvaListe(ctx, resto)
        prefs(ctx).edit().remove(chiavePunti(id)).remove(chiaveScelti(id)).apply()
        if (listaAttiva(ctx).id == id) scegliLista(ctx, resto.first().id)
        return true
    }

    private fun chiavePunti(id: String) = "${CHIAVE_PUNTI}_$id"
    private fun chiaveScelti(id: String) = "${CHIAVE_SCELTI}_$id"

    fun leggiPunti(ctx: Context, lista: Lista = listaAttiva(ctx)): List<Punto> =
        prefs(ctx)
            .getString(chiavePunti(lista.id), "")
            .orEmpty()
            .lineSequence()
            .mapNotNull { puntoDa(it) }
            .toList()

    fun salvaPunti(ctx: Context, lista: Lista, punti: List<Punto>) {
        prefs(ctx).edit()
            .putString(chiavePunti(lista.id), punti.joinToString("\n") { it.chiave() })
            .apply()
    }

    /**
     * La modalità modifica: accesa, il giro passa sulle spuntate; spenta, su tutte.
     *
     * ⚠️ Spenta, le spunte **restano scritte** e non si cancellano: spegnerla per un
     * giro su tutte non deve buttare via la scelta fatta, che torna buona la volta
     * dopo che la si riaccende.
     */
    fun leggiModifica(ctx: Context): Boolean = prefs(ctx).getBoolean(CHIAVE_MODIFICA, false)

    fun salvaModifica(ctx: Context, accesa: Boolean) {
        prefs(ctx).edit().putBoolean(CHIAVE_MODIFICA, accesa).apply()
    }

    fun leggiMetri(ctx: Context): Int =
        prefs(ctx).getInt(CHIAVE_METRI, METRI_DI_PARTENZA).coerceAtLeast(1)

    fun salvaMetri(ctx: Context, metri: Int) {
        prefs(ctx).edit().putInt(CHIAVE_METRI, metri.coerceAtLeast(1)).apply()
    }

    /**
     * Il punto spostato di [metri] verso [direzione].
     *
     * Un grado di latitudine vale ~111 km ovunque; uno di longitudine si stringe
     * verso i poli col coseno della latitudine. ⚠️ Il coseno ha un pavimento: a
     * un passo dal polo varrebbe quasi zero, e dieci metri diventerebbero mezzo
     * giro del mondo.
     */
    fun sposta(p: Punto, direzione: Direzione, metri: Int): Punto {
        val dLat = metri / METRI_PER_GRADO
        val cos = Math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.01)
        val dLon = metri / (METRI_PER_GRADO * cos)
        val (lat, lon) = when (direzione) {
            Direzione.NORD -> p.lat + dLat to p.lon
            Direzione.SUD -> p.lat - dLat to p.lon
            Direzione.EST -> p.lat to p.lon + dLon
            Direzione.OVEST -> p.lat to p.lon - dLon
        }
        // La longitudine fa il giro: 180,0001 est è 179,9999 ovest.
        val lonGirata = ((lon + 540.0) % 360.0) - 180.0
        return Punto(lat.coerceIn(-90.0, 90.0), lonGirata)
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
    fun leggiScelti(ctx: Context, lista: Lista = listaAttiva(ctx)): Set<String> =
        prefs(ctx)
            .getString(chiaveScelti(lista.id), "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun salvaScelti(ctx: Context, lista: Lista, chiavi: Collection<String>) {
        prefs(ctx).edit()
            .putString(chiaveScelti(lista.id), chiavi.joinToString("\n"))
            .apply()
    }

    /**
     * Le coordinate che un giro userebbe adesso, dalla lista attiva: con la
     * modalità modifica accesa le spuntate (o tutte se non ce n'è nessuna), spenta
     * tutte. Una sola spuntata vuol dire **fermi lì**, senza giro.
     *
     * ⚠️ Sta qui e non nelle due schermate: la regola scritta due volte sono due giri
     * diversi il giorno che una delle due cambia — e il pulsantone ⚡ userebbe
     * coordinate diverse da quelle che la scheda 📍 dichiara.
     */
    fun daUsare(ctx: Context): List<Punto> {
        val lista = listaAttiva(ctx)
        val punti = leggiPunti(ctx, lista)
        if (!leggiModifica(ctx)) return punti
        val scelti = leggiScelti(ctx, lista)
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
