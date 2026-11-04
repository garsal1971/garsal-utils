package com.garsal.stepinj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Il giro del mock, in un **servizio in primo piano** e non nella schermata.
 *
 * ⚠️ Non è un dettaglio di comodità: un mock serve mentre si usa **un'altra
 * app**, quindi la schermata è per definizione in secondo piano — e da lì
 * Android ferma le coroutine e uccide il processo quando gli pare. Un
 * countdown di dieci minuti dentro l'Activity si interromperebbe al primo
 * cambio di app, cioè esattamente quando il mock deve lavorare. È la stessa
 * ragione per cui in SOS il countdown vive nel servizio.
 */
class MockGpsService : Service() {

    private var scope: CoroutineScope? = null
    private var giro: Job? = null
    private var fused: FusedLocationProviderClient? = null
    /** Quando è partito il giro: l'inizio della riga dei passi dal percorso. */
    private var inizioGiro = 0L
    /** Una chiusura sola, anche se Ferma arriva mentre il giro sta finendo da sé. */
    private var chiudendo = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AZIONE_FERMA) {
            // ⚠️ Non si ferma e basta: se ⚡ ha armato i passi dal percorso, i tratti
            // già fatti vanno scritti prima di spegnere — fermare prima vuol dire
            // averli fatti per niente.
            chiudi()
            return START_NOT_STICKY
        }

        val righe = intent?.getStringArrayListExtra(EXTRA_PUNTI).orEmpty()
        val punti = righe.mapNotNull { MockGps.puntoDa(it) }
        val secondiPer = (intent?.getIntExtra(EXTRA_SECONDI, 5) ?: 5).coerceAtLeast(1)
        val durataMin = (intent?.getIntExtra(EXTRA_MINUTI, 5) ?: 5).coerceAtLeast(1)

        if (punti.isEmpty()) {
            MockStato.messaggio.value = "Nessuna coordinata da usare."
            stopSelf()
            return START_NOT_STICKY
        }

        // ⚠️ `startForeground` con tipo `location` PUÒ essere rifiutato, e il
        // rifiuto è un'eccezione: da Android 14 pretende che l'app abbia il
        // permesso di posizione concesso in quel momento, anche se il mock non
        // legge niente. Non intercettarlo vuol dire l'app che si chiude di
        // colpo premendo Avvia — che è precisamente quel che faceva la v1.1.0.
        // La schermata il permesso ora lo chiede prima; questo è il secondo
        // muro, perché un permesso può essere revocato mentre l'app è aperta.
        try {
            avviaInPrimoPiano(punti.size, durataMin)
        } catch (e: Exception) {
            MockStato.messaggio.value =
                "Android non lascia partire il servizio: ${e.message ?: "permesso di posizione mancante"}. " +
                    "Concedi a StepInj il permesso di posizione e riprova."
            MockStato.attivo.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            MockGps.accendi(lm)
        } catch (e: SecurityException) {
            MockStato.messaggio.value =
                "Android ha rifiutato le posizioni finte. Apri Opzioni sviluppatore → " +
                    "«App per posizioni fittizie» e scegli StepInj."
            MockStato.attivo.value = false
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            MockStato.messaggio.value = "Non sono riuscito ad accendere il mock: ${e.message}"
            MockStato.attivo.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        // Dopo i provider di Android e non prima: se quelli sono stati rifiutati
        // il mock non parte, e accendere Play Services sarebbe una mezza misura.
        val f = runCatching { LocationServices.getFusedLocationProviderClient(this) }.getOrNull()
        f?.let { MockGps.accendiFused(it) }
        fused = f

        MockStato.punti.value = punti
        // Un giro nuovo non scrive passi finché ⚡ non lo arma, e riparte da zero metri.
        MockStato.passiDalGiro.value = false
        MockStato.metri.value = 0.0
        inizioGiro = System.currentTimeMillis()
        chiudendo = false
        MockStato.attivo.value = true
        MockStato.messaggio.value = "In corso su ${punti.size} coordinate."

        val s = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        scope = s
        giro = s.launch {
            val fine = System.currentTimeMillis() + durataMin * 60_000L
            var i = 0
            var sulPuntoDa = 0
            while (System.currentTimeMillis() < fine) {
                // ⚠️ Riletto a ogni giro e non preso una volta all'avvio: le
                // frecce N/S/E/O lo sostituiscono mentre il mock è acceso.
                val elenco = MockStato.punti.value
                if (elenco.isEmpty()) break
                if (i >= elenco.size) i = 0
                MockStato.indice.value = i
                MockStato.restanti.value = (fine - System.currentTimeMillis()) / 1000

                // ⚠️ La posizione si riscrive ogni secondo, non una volta ogni
                // `secondiPer`. Una posizione finta invecchia: le app la
                // scartano se è vecchia di qualche secondo, e il sistema nel
                // frattempo rimette in giro quella vera — il mock «tiene» a
                // scatti e sembra rotto. Il passo di `secondiPer` decide
                // quando si CAMBIA punto, non ogni quanto lo si dice.
                MockGps.scrivi(lm, elenco[i])
                f?.let { MockGps.scriviFused(it, elenco[i]) }
                delay(1000)

                sulPuntoDa++
                if (sulPuntoDa >= secondiPer) {
                    sulPuntoDa = 0
                    // Si ricomincia da capo: l'elenco è un giro, non una fila.
                    val da = elenco[i]
                    i = (i + 1) % elenco.size
                    // 👣 Il tratto appena fatto, compreso il ritorno dall'ultimo al
                    // primo: è l'anello che il telefono ha davvero «camminato».
                    if (elenco.size > 1) MockStato.metri.value += MockGps.distanza(da, elenco[i])
                }
            }
            MockStato.messaggio.value = "Finito: erano $durataMin minuti."
            chiudi()
        }

        return START_NOT_STICKY
    }

    /**
     * ⚠️ Si spegne il mock **anche qui**, e non solo alla fine del countdown:
     * `onDestroy` arriva pure quando il sistema uccide il servizio o l'utente
     * chiude l'app dai recenti, e senza questo il provider finto resterebbe
     * registrato — il telefono continuerebbe a rispondere con l'ultima
     * posizione falsa, senza più niente a schermo che lo dica.
     */
    override fun onDestroy() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null) MockGps.spegni(lm)
        fused?.let { MockGps.spegniFused(it) }
        fused = null
        scope?.cancel()
        scope = null
        MockStato.attivo.value = false
        MockStato.indice.value = -1
        MockStato.restanti.value = 0
        MockStato.punti.value = emptyList()
        super.onDestroy()
    }

    private fun fermaTutto() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Ferma il giro, scrive i passi del percorso se ⚡ li ha armati, e solo dopo
     * spegne il servizio: la scrittura avviene ancora in primo piano.
     *
     * ⚠️ Gira in uno scope suo e non in quello del giro, che viene annullato. E se
     * Android uccide il servizio (`onDestroy` senza passare di qui) i passi non si
     * scrivono: lì non c'è più tempo per una chiamata a Health Connect.
     */
    private fun chiudi() {
        if (chiudendo) return
        chiudendo = true
        val g = giro
        CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            // Annullato e atteso: i metri si leggono quando il giro non li tocca più.
            g?.cancelAndJoin()
            runCatching { scriviPassi() }
                .onFailure { avvisa("👣 Passi non scritti: ${it.message ?: it.javaClass.simpleName}") }
            fermaTutto()
        }
    }

    private suspend fun scriviPassi() {
        if (!MockStato.passiDalGiro.value) return
        MockStato.passiDalGiro.value = false
        val metri = MockStato.metri.value
        val passi = Math.round(metri / METRI_PER_PASSO).coerceAtMost(MAX_PASSI_PER_VOLTA)
        if (passi <= 0) {
            avvisa("👣 Nessun passo da scrivere: il giro non ha percorso nessun tratto.")
            return
        }
        PassiRepository.statoSdk(this)?.let { avvisa("👣 Passi non scritti: $it"); return }
        val testo = when (
            val e = PassiRepository.aggiungi(this, passi, Instant.ofEpochMilli(inizioGiro), ricorda = false)
        ) {
            is EsitoPassi.Ok -> "👣 ${e.messaggio.removeSuffix(".")} dal percorso."
            is EsitoPassi.Errore -> "👣 Passi non scritti: ${e.messaggio}"
            EsitoPassi.PermessiRichiesti -> "👣 Passi non scritti: Health Connect non ha i permessi."
        }
        avvisa(testo)
    }

    /**
     * L'esito dei passi sta in una notifica a sé, oltre che in `MockStato`: a fine
     * giro si è in un'altra app, e un risultato che si legge solo riaprendo StepInj
     * è un risultato che non si legge.
     */
    private fun avvisa(testo: String) {
        MockStato.messaggio.value = testo
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            val n = Notification.Builder(this, CANALE)
                .setContentTitle("StepInj")
                .setContentText(testo)
                .setStyle(Notification.BigTextStyle().bigText(testo))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setAutoCancel(true)
                .build()
            nm.notify(ID_ESITO, n)
        }
    }

    private fun avviaInPrimoPiano(quanti: Int, minuti: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CANALE, "Posizione finta", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val apri = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MockGpsService::class.java).setAction(AZIONE_FERMA),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, CANALE)
            .setContentTitle("Posizione finta attiva")
            .setContentText("$quanti coordinate · $minuti minuti")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(apri)
            // ⚠️ Il tasto Ferma sta sulla notifica e non solo nell'app: un mock
            // che si spegne solo riaprendo la schermata è un mock che resta
            // acceso quando ci si dimentica di lui.
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this, android.R.drawable.ic_menu_close_clear_cancel
                    ),
                    "Ferma", stop,
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_NOTIFICA, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(ID_NOTIFICA, n)
        }
    }

    companion object {
        const val EXTRA_PUNTI = "punti"
        const val EXTRA_SECONDI = "secondi"
        const val EXTRA_MINUTI = "minuti"
        const val AZIONE_FERMA = "com.garsal.stepinj.FERMA_MOCK"
        private const val CANALE = "mockgps"
        private const val ID_NOTIFICA = 4242
        private const val ID_ESITO = 4243

        fun avvia(ctx: Context, punti: List<Punto>, secondi: Int, minuti: Int) {
            val i = Intent(ctx, MockGpsService::class.java)
                .putStringArrayListExtra(EXTRA_PUNTI, ArrayList(punti.map { it.chiave() }))
                .putExtra(EXTRA_SECONDI, secondi)
                .putExtra(EXTRA_MINUTI, minuti)
            ctx.startForegroundService(i)
        }

        fun ferma(ctx: Context) {
            ctx.startService(
                Intent(ctx, MockGpsService::class.java).setAction(AZIONE_FERMA)
            )
        }
    }
}
