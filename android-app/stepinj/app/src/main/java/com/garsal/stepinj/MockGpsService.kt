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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AZIONE_FERMA) {
            fermaTutto()
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

        avviaInPrimoPiano(punti.size, durataMin)

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

        MockStato.attivo.value = true
        MockStato.messaggio.value = "In corso su ${punti.size} coordinate."

        val s = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        scope = s
        s.launch {
            val fine = System.currentTimeMillis() + durataMin * 60_000L
            var i = 0
            var sulPuntoDa = 0
            while (System.currentTimeMillis() < fine) {
                MockStato.indice.value = i
                MockStato.restanti.value = (fine - System.currentTimeMillis()) / 1000

                // ⚠️ La posizione si riscrive ogni secondo, non una volta ogni
                // `secondiPer`. Una posizione finta invecchia: le app la
                // scartano se è vecchia di qualche secondo, e il sistema nel
                // frattempo rimette in giro quella vera — il mock «tiene» a
                // scatti e sembra rotto. Il passo di `secondiPer` decide
                // quando si CAMBIA punto, non ogni quanto lo si dice.
                MockGps.scrivi(lm, punti[i])
                delay(1000)

                sulPuntoDa++
                if (sulPuntoDa >= secondiPer) {
                    sulPuntoDa = 0
                    // Si ricomincia da capo: l'elenco è un giro, non una fila.
                    i = (i + 1) % punti.size
                }
            }
            MockStato.messaggio.value = "Finito: erano $durataMin minuti."
            fermaTutto()
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
        scope?.cancel()
        scope = null
        MockStato.attivo.value = false
        MockStato.indice.value = -1
        MockStato.restanti.value = 0
        super.onDestroy()
    }

    private fun fermaTutto() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            .addAction(Notification.Action.Builder(null, "Ferma", stop).build())
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
