package com.garsal.stepinj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** Quel che la camminata sta facendo, per la scheda ⚡. */
object CamminataStato {
    val attiva = MutableStateFlow(false)
    /** Passi già scritti in Health Connect in questa camminata. */
    val passi = MutableStateFlow(0L)
    /** Secondi che restano. */
    val restanti = MutableStateFlow(0L)
    val messaggio = MutableStateFlow("")
}

/**
 * 👣 La camminata (v1.8.0): percorre l'anello della lista attiva a passo d'uomo e
 * scrive i passi in Health Connect **ogni [OGNI_MS]**, in righe che si susseguono.
 *
 * ⚠️ **È separata dal mock, di proposito.** Il mock decide dove il telefono dice di
 * essere; la camminata decide quanti passi si sono fatti. Si accendono insieme da ⚡,
 * ma ognuna vive nel suo servizio e fermarne una non ferma l'altra.
 *
 * ⚠️ **La velocità è quella del cursore di ⚡** (v1.9.0, 2–10 km/h, 4,7 di partenza),
 * letta alla partenza, e non segue i «secondi per punto» del mock: con quelli due punti a 500 m varrebbero 670 passi in 5 secondi,
 * una cadenza che Google Fit scarta. È la ragione per cui la camminata esiste: le
 * righe da migliaia di passi in un minuto in Health Connect c'erano, in Fit no.
 *
 * ⚠️ **Si scrive spesso e a pezzi, non alla fine**: se Android chiude l'app a metà, i
 * passi fatti fin lì restano. Una scrittura che fallisce non perde niente: i metri
 * restano da scrivere e si sommano alla successiva, con la finestra che parte
 * dall'ultima scrittura riuscita. Il resto sotto il passo intero si porta avanti.
 */
class CamminataService : Service() {

    private var scope: CoroutineScope? = null
    private var giro: Job? = null
    private var chiudendo = false

    /** Metri camminati e non ancora scritti come passi. */
    private var daScrivere = 0.0
    private var ultimaScrittura = 0L
    private var metriTotali = 0.0
    private var anello = 0.0
    private var minuti = 10
    /** Metri al secondo, dal cursore: si legge alla partenza e resta fino alla fine. */
    private var velocita = VELOCITA_MS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AZIONE_FERMA) {
            // Ferma senza una camminata in corso: niente da chiudere né da riepilogare.
            if (!CamminataStato.attiva.value) { stopSelf(); return START_NOT_STICKY }
            chiudi("Fermata.")
            return START_NOT_STICKY
        }
        // Già in corso: non si riparte, o si azzererebbe il conto di una camminata viva.
        if (CamminataStato.attiva.value) return START_NOT_STICKY

        anello = MockGps.anello(MockGps.daUsare(this))
        if (anello <= 0.0) {
            CamminataStato.messaggio.value = "Servono almeno due coordinate nella lista: senza, non c'è un anello."
            stopSelf()
            return START_NOT_STICKY
        }
        minuti = PassiRepository.minutiCamminata(this)
        velocita = PassiRepository.kmhCamminata(this) / 3.6

        try {
            avviaInPrimoPiano()
        } catch (e: Exception) {
            CamminataStato.messaggio.value = "Android non lascia partire la camminata: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }

        chiudendo = false
        daScrivere = 0.0
        metriTotali = 0.0
        ultimaScrittura = System.currentTimeMillis()
        CamminataStato.passi.value = 0L
        CamminataStato.attiva.value = true
        CamminataStato.messaggio.value = "In corso: $minuti min."

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        giro = s.launch {
            val fine = System.currentTimeMillis() + minuti * 60_000L
            var prima = System.currentTimeMillis()
            while (System.currentTimeMillis() < fine) {
                delay(OGNI_MS)
                val ora = System.currentTimeMillis()
                // Il tempo vero trascorso e non i 5 s nominali: un telefono che dorme
                // ritarda i giri, e i metri devono seguire l'orologio.
                val metri = velocita * (minOf(ora, fine) - prima) / 1000.0
                prima = ora
                daScrivere += metri
                metriTotali += metri
                CamminataStato.restanti.value = ((fine - ora) / 1000).coerceAtLeast(0)
                scrivi()
                aggiornaNotifica()
            }
            chiudi("Finita: erano $minuti minuti.")
        }
        return START_NOT_STICKY
    }

    /** Scrive i passi interi maturati; il resto resta per la prossima volta. */
    private suspend fun scrivi() {
        val passi = Math.floor(daScrivere / METRI_PER_PASSO).toLong().coerceAtMost(MAX_PASSI_PER_VOLTA)
        if (passi <= 0) return
        if (PassiRepository.statoSdk(this) != null) {
            CamminataStato.messaggio.value = PassiRepository.statoSdk(this) ?: ""
            return
        }
        when (val e = PassiRepository.aggiungi(this, passi, Instant.ofEpochMilli(ultimaScrittura), ricorda = false)) {
            is EsitoPassi.Ok -> {
                daScrivere -= passi * METRI_PER_PASSO
                ultimaScrittura = System.currentTimeMillis()
                CamminataStato.passi.value += passi
                CamminataStato.messaggio.value = "In corso."
            }
            is EsitoPassi.Errore -> CamminataStato.messaggio.value = "Scrittura non riuscita, riprovo: ${e.messaggio}"
            EsitoPassi.PermessiRichiesti ->
                CamminataStato.messaggio.value = "Health Connect non ha i permessi: i passi restano da scrivere."
        }
    }

    /** Ferma il giro, scrive quel che è maturato e chiude con un riepilogo. */
    private fun chiudi(come: String) {
        if (chiudendo) return
        chiudendo = true
        val g = giro
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            g?.cancelAndJoin()
            if (CamminataStato.attiva.value) runCatching { scrivi() }
            val passi = CamminataStato.passi.value
            val testo = "👣 Camminata: $come Scritti ${"%,d".format(passi)} passi " +
                "(${PassiRepository.km(passi * METRI_PER_PASSO)}, " +
                "${"%.1f".format(if (anello > 0) metriTotali / anello else 0.0)} giri dell'anello)."
            CamminataStato.messaggio.value = testo
            avvisa(testo)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        CamminataStato.attiva.value = false
        CamminataStato.restanti.value = 0
        super.onDestroy()
    }

    private fun notifica(testo: String): Notification {
        val apri = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, CamminataService::class.java).setAction(AZIONE_FERMA),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CANALE)
            .setContentTitle("👣 Camminata in corso")
            .setContentText(testo)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(apri)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this, android.R.drawable.ic_menu_close_clear_cancel
                    ),
                    "Ferma", stop,
                ).build()
            )
            .build()
    }

    private fun testoInCorso(): String =
        "${"%,d".format(CamminataStato.passi.value)} passi · " +
            "${(CamminataStato.restanti.value + 59) / 60} min restanti"

    private fun avviaInPrimoPiano() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANALE, "Camminata", NotificationManager.IMPORTANCE_LOW)
        )
        val n = notifica("$minuti minuti a ${"%.1f".format(velocita * 3.6)} km/h")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_NOTIFICA, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_NOTIFICA, n)
        }
    }

    private fun aggiornaNotifica() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(ID_NOTIFICA, notifica(testoInCorso()))
        }
    }

    /** Il riepilogo in una notifica a sé: a fine camminata si è in un'altra app. */
    private fun avvisa(testo: String) {
        runCatching {
            val n = Notification.Builder(this, CANALE)
                .setContentTitle("StepInj")
                .setContentText(testo)
                .setStyle(Notification.BigTextStyle().bigText(testo))
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(ID_ESITO, n)
        }
    }

    companion object {
        /** ~4,7 km/h, ~104 passi al minuto con [METRI_PER_PASSO]: la velocità di partenza. */
        const val VELOCITA_MS = 1.3
        const val OGNI_MS = 5_000L
        const val AZIONE_FERMA = "com.garsal.stepinj.FERMA_CAMMINATA"
        private const val CANALE = "camminata"
        private const val ID_NOTIFICA = 4343
        private const val ID_ESITO = 4344

        /** I passi che una camminata di [minuti] a [kmh] scriverebbe. */
        fun stimaPassi(minuti: Int, kmh: Float): Long = Math.round(minuti * 60 * (kmh / 3.6) / METRI_PER_PASSO)

        /** Passi al minuto a [kmh]: è la cadenza che Fit vede. */
        fun passiAlMinuto(kmh: Float): Long = Math.round(60 * (kmh / 3.6) / METRI_PER_PASSO)

        fun avvia(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CamminataService::class.java))
        }

        fun ferma(ctx: Context) {
            ctx.startService(Intent(ctx, CamminataService::class.java).setAction(AZIONE_FERMA))
        }
    }
}
