package com.garsal.stepinj

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Correggere i passi della giornata scrivendoli in **Health Connect**.
 *
 * ⚠️ NON Google Fit, ed è la ragione per cui questo file esiste. La vecchia
 * FitStepsInjector passava dalle API Android di Google Fit, che vogliono un
 * login Google, un progetto Cloud, uno scope OAuth e l'impronta SHA-1 della
 * chiave di firma registrata là: il 9 settembre 2026 ha smesso di funzionare da
 * un giorno all'altro con `ApiException: 12500` (SIGN_IN_FAILED) senza che
 * l'APK fosse cambiata — cioè il guasto stava tutto dalla parte di Google. E
 * quelle API sono comunque supportate solo fino a fine 2026.
 *
 * Health Connect non ha niente di tutto questo: è un permesso di sistema sul
 * telefono. Sparisce l'intera famiglia di guasti di cui il 12500 fa parte.
 *
 * ⚠️ Che i passi si vedano poi **dentro l'app Google Fit** dipende da lei: Fit
 * deve avere la sincronizzazione con Health Connect accesa. Non è una cosa che
 * questo codice possa garantire, ed è l'unica incognita del passaggio.
 */

/**
 * Leggere per dire quanti ce ne sono, scrivere per aggiungerne — e scrivere la
 * distanza che quei passi fanno (v1.6.0).
 *
 * ⚠️ Della distanza basta la **scrittura**: non la si legge da nessuna parte, e
 * cancellare le proprie righe chiede lo stesso permesso di scriverle. Chiedere
 * anche la lettura sarebbe un permesso in più senza niente che lo usi.
 */
val PERMESSI_PASSI: Set<String> = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getWritePermission(StepsRecord::class),
    HealthPermission.getWritePermission(DistanceRecord::class),
)

/**
 * La lunghezza di un passo, per la distanza scritta insieme ai passi.
 *
 * ⚠️ Fissa di proposito: è una stima, e una casella in più per una stima sarebbe
 * un'altra cosa da sbagliare. Cambiandola, cambia solo la distanza delle aggiunte
 * **successive** — quelle già scritte restano come sono.
 */
const val METRI_PER_PASSO = 0.75

/**
 * ⚠️ Health Connect rifiuta un `StepsRecord` con `count` fuori scala. Il tetto è
 * per **record**, non per giornata: chiedendone di più si scrive più di una
 * riga, e qui invece la casella si ferma — un numero che non si può scrivere
 * dev'essere impedito prima, non spiegato dopo con un errore della libreria.
 */
const val MAX_PASSI_PER_VOLTA = 1_000_000L

sealed class EsitoPassi {
    data class Ok(val messaggio: String) : EsitoPassi()
    data class Errore(val messaggio: String) : EsitoPassi()
    /** Il permesso non è (ancora) concesso: il chiamante lo chiede e ritenta. */
    object PermessiRichiesti : EsitoPassi()
}

object PassiRepository {

    /** Il nome della preferenza dove sta la fine dell'ultima riga scritta. */
    private const val PREFS = "stepinj"
    private const val ULTIMO_FINE = "ultimo_fine_passi"
    private const val ULTIMI_PASSI = "ultimi_passi"
    private const val DA_PERCORSO = "passi_da_percorso"
    private const val MINUTI_CAMMINATA = "camminata_minuti"

    /**
     * 👣 L'interruttore di ⚡: i passi non dalla casella ma dalla camminata
     * (`CamminataService`, v1.8.0), che percorre l'anello e li scrive ogni 5 secondi.
     */
    fun daPercorso(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DA_PERCORSO, false)

    fun salvaDaPercorso(context: Context, acceso: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DA_PERCORSO, acceso).apply()
    }

    /** I minuti della camminata (v1.8.0): suoi, non quelli del mock. */
    fun minutiCamminata(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(MINUTI_CAMMINATA, 10).coerceIn(1, 600)

    fun salvaMinutiCamminata(context: Context, minuti: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(MINUTI_CAMMINATA, minuti.coerceIn(1, 600)).apply()
    }

    /**
     * L'ultimo numero di passi **scritto davvero**, o 0 se non è mai successo.
     *
     * ⚠️ Lo scrive `aggiungi()` dopo che l'inserimento è riuscito, e **non** la casella
     * mentre la si digita: il valore di partenza dev'essere l'ultimo numero *usato*,
     * non l'ultimo abbozzato e poi cancellato.
     *
     * ⚠️ È **uno solo per tutta l'app**: lo leggono la scheda 👟 e il pulsantone ⚡, e
     * due preferenze diverse sarebbero due default diversi per la stessa domanda — con
     * il pulsantone che scrive un numero che nella casella non si legge da nessuna parte.
     */
    fun ultimoNumero(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(ULTIMI_PASSI, 0L)

    /** Cosa risponde Health Connect, e cosa può farci l'utente. */
    fun statoSdk(context: Context): String? = runCatching {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> null
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                "Health Connect va aggiornato dal Play Store."
            else ->
                "Health Connect non è disponibile su questo telefono. " +
                    "Su Android 13 e precedenti va installato dal Play Store; " +
                    "dal 14 in poi è già nelle impostazioni di sistema."
        }
    }.getOrElse { "Health Connect non raggiungibile: ${it.message}" }

    suspend fun permessiConcessi(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            HealthConnectClient.getOrCreate(context)
                .permissionController.getGrantedPermissions()
                .containsAll(PERMESSI_PASSI)
        }.getOrDefault(false)
    }

    /**
     * I passi di **oggi**, sommati su tutte le sorgenti — è il numero che si
     * legge nell'app della salute, non solo quello che abbiamo scritto noi.
     *
     * ⚠️ Si usa `aggregate` e non `readRecords`: sommare a mano le righe
     * conterebbe due volte i tratti che due app hanno registrato insieme
     * (il telefono e l'orologio), mentre l'aggregazione applica le priorità
     * fra sorgenti che Health Connect già conosce.
     */
    suspend fun leggiOggi(context: Context): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val risposta: AggregationResult = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(inizioDiOggi(), Instant.now()),
                )
            )
            // ⚠️ `null` non è zero: vuol dire «nessun dato in quell'intervallo».
            // Qui però la domanda è «quanti passi oggi», e la risposta onesta a
            // giornata senza dati è 0 — lo dice la schermata, non questo conto.
            risposta[StepsRecord.COUNT_TOTAL] ?: 0L
        }
    }

    /**
     * Aggiunge `quanti` passi in una finestra che finisce **adesso**.
     *
     * ⚠️ La finestra non parte mai prima della fine di quella scritta l'ultima
     * volta (`ULTIMO_FINE`): due aggiunte ravvicinate darebbero due righe
     * sovrapposte, e sovrapposte non è come affiancate — chi le legge deve
     * decidere se sommarle o sceglierne una, e le due risposte differiscono
     * proprio del numero che si sta cercando di correggere.
     *
     * ⚠️ Il `clientRecordId` è ricavato dalla finestra: se la scrittura va a
     * buon fine ma la risposta si perde per strada e si ripreme, Health Connect
     * **sostituisce** la riga invece di aggiungerne una seconda. Senza, un
     * tocco ripetuto per incertezza raddoppierebbe i passi in silenzio.
     *
     * [daQuando] (v1.7.0) è l'inizio voluto della finestra — per la camminata la
     * scrittura precedente — al posto del minuto prima: 2.000 passi in un minuto sono una cadenza
     * impossibile, e Google Fit una riga così la scarta. Vale comunque la regola
     * della non sovrapposizione. [ricorda] = false non tocca `ultimi_passi`: i passi
     * calcolati dal percorso non sono il numero scritto nella casella.
     */
    suspend fun aggiungi(
        context: Context, quanti: Long, daQuando: Instant? = null, ricorda: Boolean = true,
    ): EsitoPassi = withContext(Dispatchers.IO) {
        if (quanti <= 0) return@withContext EsitoPassi.Errore("Scrivi quanti passi aggiungere.")
        if (quanti > MAX_PASSI_PER_VOLTA) {
            return@withContext EsitoPassi.Errore(
                "Health Connect ne accetta al massimo ${"%,d".format(MAX_PASSI_PER_VOLTA)} per volta."
            )
        }
        try {
            val client = HealthConnectClient.getOrCreate(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            val fine = Instant.now()
            val ultimo = prefs.getLong(ULTIMO_FINE, 0L)
            var inizio = daQuando?.takeIf { it.isBefore(fine) } ?: fine.minusSeconds(60)
            if (ultimo > 0L) {
                val fineVecchia = Instant.ofEpochMilli(ultimo)
                if (fineVecchia.isAfter(inizio)) inizio = fineVecchia
            }
            // Due tocchi nello stesso millisecondo: una finestra vuota non è un
            // record valido. Un millisecondo di sovrapposizione è trascurabile,
            // una riga rifiutata no.
            if (!inizio.isBefore(fine)) inizio = fine.minusMillis(1)

            val zona = ZoneId.systemDefault()
            val finestra = "${inizio.toEpochMilli()}-${fine.toEpochMilli()}"
            val metri = quanti * METRI_PER_PASSO
            // ⚠️ Passi e distanza nella STESSA chiamata e nella stessa finestra: una
            // `insertRecords` è tutta o niente, quindi non può restare scritto uno solo
            // dei due — i passi senza la loro distanza, o una distanza senza passi.
            // Il `clientRecordId` della distanza viene dalla stessa finestra per la
            // stessa ragione di quello dei passi: ripremendo si sostituisce, non si somma.
            client.insertRecords(
                listOf(
                    StepsRecord(
                        count = quanti,
                        startTime = inizio,
                        endTime = fine,
                        startZoneOffset = zona.rules.getOffset(inizio),
                        endZoneOffset = zona.rules.getOffset(fine),
                        metadata = Metadata.manualEntry(clientRecordId = "stepinj-$finestra"),
                    ),
                    DistanceRecord(
                        distance = Length.meters(metri),
                        startTime = inizio,
                        endTime = fine,
                        startZoneOffset = zona.rules.getOffset(inizio),
                        endZoneOffset = zona.rules.getOffset(fine),
                        metadata = Metadata.manualEntry(clientRecordId = "stepinj-dist-$finestra"),
                    ),
                )
            )
            prefs.edit()
                .putLong(ULTIMO_FINE, fine.toEpochMilli())
                .apply {
                    if (ricorda) putLong(ULTIMI_PASSI, quanti)
                }
                .apply()
            EsitoPassi.Ok("Aggiunti ${"%,d".format(quanti)} passi (${km(metri)}).")
        } catch (e: SecurityException) {
            EsitoPassi.PermessiRichiesti
        } catch (e: Exception) {
            EsitoPassi.Errore(e.message ?: "Errore Health Connect")
        }
    }

    /**
     * Toglie le righe scritte da questa app oggi: i passi **e la loro distanza**,
     * che senza i passi accanto sarebbe un tragitto fatto senza camminare.
     *
     * ⚠️ **Solo le proprie**: Health Connect non lascia cancellare i dati di
     * un'altra app, e non c'è modo di aggirarlo. Quindi da qui i passi si
     * aggiungono e si disfa quel che si è aggiunto — **non** si tolgono i passi
     * contati dal telefono o dall'orologio. Vale la pena saperlo prima di
     * premere, non dopo aver visto il totale fermo.
     */
    suspend fun togliMiei(context: Context): EsitoPassi = withContext(Dispatchers.IO) {
        try {
            val client = HealthConnectClient.getOrCreate(context)
            val oggi = TimeRangeFilter.between(inizioDiOggi(), Instant.now())
            client.deleteRecords(recordType = StepsRecord::class, timeRangeFilter = oggi)
            client.deleteRecords(recordType = DistanceRecord::class, timeRangeFilter = oggi)
            // Il cursore riparte: le righe di oggi non ci sono più, quindi
            // niente da cui tenersi alla larga.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(ULTIMO_FINE).apply()
            EsitoPassi.Ok("Tolto quello che avevo aggiunto oggi.")
        } catch (e: SecurityException) {
            EsitoPassi.PermessiRichiesti
        } catch (e: Exception) {
            EsitoPassi.Errore(e.message ?: "Errore Health Connect")
        }
    }

    /** «3,75 km», o «750 m» sotto il chilometro. */
    fun km(metri: Double): String =
        if (metri < 1000) "${metri.toLong()} m" else "%.2f km".format(metri / 1000)

    /** La mezzanotte di oggi in ora **locale**: il giorno è quello del telefono,
     *  non quello di UTC — fra mezzanotte e le due sarebbe ancora ieri. */
    private fun inizioDiOggi(): Instant {
        val zona = ZoneId.systemDefault()
        return LocalDate.now(zona).atStartOfDay(zona).toInstant()
    }
}
