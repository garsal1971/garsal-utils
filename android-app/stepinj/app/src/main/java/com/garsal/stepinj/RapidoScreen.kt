package com.garsal.stepinj

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ⚡ Le tre cose in fila, con un tocco solo: **accendi il mock, aggiungi i passi, apri
 * l'app**. È la prima scheda e quella su cui l'app si apre, perché è il gesto che si fa
 * tutti i giorni — le altre tre configurano, questa esegue.
 *
 * ⚠️ **Non configura niente, di proposito.** Quanti passi, quali coordinate, per quanti
 * minuti e quale app sono l'**ultima cosa scelta nelle altre schede**: qui non c'è una
 * seconda casella dove riscriverli, o sarebbero due verità sullo stesso numero e non si
 * saprebbe quale ha usato il giro. È la stessa scelta per cui in 📍 MockGps le
 * coordinate spuntate *sono* quelle che si useranno, senza un pulsante «Usa».
 *
 * ⚠️ **Un passo fallito non ferma gli altri**, e li fa tutt'e tre comunque: le tre cose
 * sono indipendenti — il mock non autorizzato non è una ragione per non scrivere i passi
 * — e fermarsi al primo intoppo vorrebbe dire ripremere il pulsante e rifare da capo
 * anche quel che era già riuscito, cioè **scrivere i passi due volte**. Alla fine la
 * schermata elenca com'è andato ciascuno.
 */

/** Com'è andato un passo: `ok` decide il segno, la riga dice cosa è successo. */
private data class Esito(val ok: Boolean, val riga: String)

/**
 * Quanto si aspetta che il mock sia davvero partito prima di aprire l'app.
 *
 * ⚠️ Non è prudenza generica: `startForegroundService` torna **subito**, mentre i
 * provider finti li registra il servizio un istante dopo. Aprendo l'app in quell'istante
 * la prima posizione che legge è quella **vera** — cioè il mock sembrerebbe non aver
 * funzionato proprio nel giro in cui serviva, e senza niente che lo spieghi.
 */
private const val ATTESA_MOCK_MS = 3_000

@Composable
fun RapidoScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var occupato by remember { mutableStateOf(false) }
    val esiti = remember { mutableStateListOf<Esito>() }

    // ── I tre passi ───────────────────────────────────────────────────────────
    suspend fun sequenza() {
        try {
            // 1 ── La posizione finta ─────────────────────────────────────────
            val daUsare = MockGps.daUsare(ctx)
            when {
                daUsare.isEmpty() -> esiti.add(
                    Esito(false, "MockGps: nessuna coordinata salvata. Aggiungine dalla scheda 📍.")
                )
                // Già in corso: fermarlo e rifarlo partire azzererebbe il countdown
                // di un giro che sta lavorando, senza che nessuno l'abbia chiesto.
                MockStato.attivo.value -> esiti.add(
                    Esito(true, "MockGps: era già in corso, lasciato com'è.")
                )
                else -> {
                    val guasto = MockGps.perche(ctx)
                    if (guasto != null) {
                        esiti.add(Esito(false, "MockGps: $guasto"))
                    } else {
                        val t = MockGps.leggiTempi(ctx)
                        MockGpsService.avvia(ctx, daUsare, t.secondi, t.minuti)
                        var atteso = 0
                        while (!MockStato.attivo.value && atteso < ATTESA_MOCK_MS) {
                            delay(100); atteso += 100
                        }
                        if (MockStato.attivo.value) {
                            esiti.add(
                                Esito(
                                    true,
                                    "MockGps: partito su ${daUsare.size} coordinate, " +
                                        "${t.secondi} s ciascuna, ${t.minuti} min in tutto."
                                )
                            )
                        } else {
                            // Il servizio, quando non parte, scrive **perché** in
                            // MockStato.messaggio: ripeterlo qui a parole nostre
                            // direbbe meno di quel che il servizio già sa.
                            esiti.add(
                                Esito(
                                    false,
                                    "MockGps: non è partito. " +
                                        MockStato.messaggio.value.ifBlank { "Nessun motivo riportato." }
                                )
                            )
                        }
                    }
                }
            }

            // 2 ── I passi ────────────────────────────────────────────────────
            val quanti = PassiRepository.ultimoNumero(ctx)
            val guastoHc = PassiRepository.statoSdk(ctx)
            when {
                quanti <= 0 -> esiti.add(
                    Esito(false, "Passi: non c'è ancora un numero. Scrivilo una volta nella scheda 👟.")
                )
                guastoHc != null -> esiti.add(Esito(false, "Passi: $guastoHc"))
                else -> when (val e = PassiRepository.aggiungi(ctx, quanti)) {
                    is EsitoPassi.Ok -> esiti.add(Esito(true, "Passi: ${e.messaggio}"))
                    is EsitoPassi.Errore -> esiti.add(Esito(false, "Passi: ${e.messaggio}"))
                    // Qui i permessi erano stati chiesti prima di partire: se
                    // rispondono ancora così, sono stati negati.
                    EsitoPassi.PermessiRichiesti -> esiti.add(
                        Esito(false, "Passi: Health Connect non ha i permessi. Concedili e riprova.")
                    )
                }
            }

            // 3 ── L'app ──────────────────────────────────────────────────────
            // ⚠️ Per ultima, e non è un ordine qualunque: aprirla manda StepInj in
            // secondo piano, e quel che venisse dopo lo farebbe un'app che non è più
            // a schermo.
            val pacchetto = Scorciatoia.scelta(ctx)
            when {
                pacchetto == null -> esiti.add(
                    Esito(false, "Apri: nessuna app scelta. Scegline una nella scheda 🚀.")
                )
                Scorciatoia.apri(ctx, pacchetto) -> esiti.add(
                    Esito(true, "Aperta ${Scorciatoia.nomeDi(ctx, pacchetto) ?: pacchetto}.")
                )
                Scorciatoia.nomeDi(ctx, pacchetto) == null -> esiti.add(
                    Esito(false, "Apri: «$pacchetto» non risulta più installata.")
                )
                else -> esiti.add(
                    Esito(false, "Apri: quell'app non ha una schermata da aprire.")
                )
            }
        } finally {
            occupato = false
        }
    }

    // ── I permessi, TUTTI prima di cominciare ─────────────────────────────────
    // ⚠️ Si chiedono in fila **davanti** alla sequenza e non man mano: una finestra di
    // sistema aperta a metà giro può ricreare l'Activity, e la sequenza si fermerebbe
    // lì — mock acceso e passi mai scritti, senza niente a schermo che lo dica. Ogni
    // risposta riprende dal passo **successivo** e mai da capo, o un permesso concesso
    // farebbe ripartire una sequenza già eseguita: due volte i passi.
    //
    // ⚠️ I launcher si dichiarano in ordine inverso rispetto a come si usano, perché in
    // Kotlin una funzione locale si può chiamare solo dopo essere stata scritta.

    val chiediPassi = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { scope.launch { sequenza() } }

    suspend fun passoPassi() {
        // Health Connect non c'è o non è aggiornato: niente da chiedere, e la sequenza
        // lo dirà come esito del suo secondo passo.
        if (PassiRepository.statoSdk(ctx) == null && !PassiRepository.permessiConcessi(ctx)) {
            chiediPassi.launch(PERMESSI_PASSI)
        } else {
            sequenza()
        }
    }

    val chiediAndroid = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scope.launch { passoPassi() } }

    fun vai() {
        occupato = true
        esiti.clear()
        scope.launch {
            // ⚠️ La posizione serve anche a chi non la legge: da Android 14 un servizio
            // in primo piano di tipo `location` pretende il permesso concesso in quel
            // momento, e `startForeground` risponde altrimenti con una SecurityException.
            // Le notifiche servono al tasto Ferma fuori dall'app: negate, il mock parte
            // lo stesso.
            val mancanti = buildList<String> {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (mancanti.isNotEmpty()) chiediAndroid.launch(mancanti.toTypedArray())
            else passoPassi()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ⚠️ `heightIn(min = …)` e non `height`: coi caratteri di sistema grandi su
        // un'altezza fissa la scritta è più alta del pulsante, e quel che avanza sparisce.
        Button(
            onClick = { vai() },
            enabled = !occupato,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        ) {
            Text(
                if (occupato) "…" else "⚡ VAI",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (esiti.isNotEmpty()) {
            Riquadro("COM'È ANDATA") {
                esiti.forEach {
                    Text(
                        (if (it.ok) "✅ " else "⚠️ ") + it.riga,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
