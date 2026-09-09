package com.garsal.stepinj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val BluBarra = Color(0xFF0081C8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Contenitore() }
            }
        }
    }
}

/**
 * Le due utility dentro la stessa APK: 👟 i passi e 📍 la posizione finta.
 *
 * ⚠️ **Due schede e non un menù a cassetto**: sono due, e un cassetto costa tre
 * tocchi (aprilo, scegli, si chiude) per una scelta che sta in uno. È la stessa
 * ragione per cui `calorie.html` ha una barra di icone invece del ☰.
 */
@Composable
private fun Contenitore() {
    var scheda by remember { mutableStateOf(0) }
    var mostraVersione by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BarraAlta(onVersione = { mostraVersione = true })

        // ⚠️ La riga delle schede SCORRE e non si stringe: coi caratteri di
        // sistema grandi due etichette larghe su 360 px non ci stanno, e
        // schiacciarle sotto il polpastrello le renderebbe non toccabili.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("👟 Passi", "📍 MockGps").forEachIndexed { i, nome ->
                if (i == scheda) {
                    Button(onClick = { scheda = i }) { Text(nome) }
                } else {
                    OutlinedButton(onClick = { scheda = i }) { Text(nome) }
                }
            }
        }

        when (scheda) {
            0 -> SchermataPassi()
            else -> MockGpsScreen()
        }
    }

    if (mostraVersione) DialogoAggiornamento(onChiudi = { mostraVersione = false })
}

@Composable
internal fun SchermataPassi() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var passiOggi by remember { mutableStateOf<Long?>(null) }
    var quanti by remember { mutableStateOf("") }
    var occupato by remember { mutableStateOf(false) }
    val log = remember { mutableListOf<String>().toMutableStateList() }

    fun scrivi(riga: String) {
        val ora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        log.add(0, "[$ora] $riga")
        // Il log serve a capire l'ultimo gesto, non a tenere la storia: oltre
        // una certa lunghezza è solo roba da scorrere.
        while (log.size > 40) log.removeAt(log.size - 1)
    }

    // Il permesso si chiede col contratto ufficiale di Health Connect: è
    // l'unico modo per cui le letture e le scritture successive passino.
    // ⚠️ Dopo la concessione l'azione si rifà **da sola**, invece di lasciare
    // l'utente a ripremere un pulsante che gli è appena sembrato non funzionare.
    var daRifare by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    val chiediPermessi = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { concessi ->
        val azione = daRifare
        daRifare = null
        if (concessi.containsAll(PERMESSI_PASSI)) {
            scrivi("Permessi concessi.")
            if (azione != null) scope.launch { azione() }
        } else {
            scrivi("ERRORE: permessi negati. Senza, non posso né leggere né scrivere i passi.")
        }
    }

    fun conPermessi(azione: suspend () -> Unit) {
        val guasto = PassiRepository.statoSdk(context)
        if (guasto != null) { scrivi("ERRORE: $guasto"); return }
        scope.launch {
            occupato = true
            try {
                if (!PassiRepository.permessiConcessi(context)) {
                    daRifare = azione
                    chiediPermessi.launch(PERMESSI_PASSI)
                } else {
                    azione()
                }
            } finally { occupato = false }
        }
    }

    fun aggiorna() = conPermessi {
        PassiRepository.leggiOggi(context)
            .onSuccess { passiOggi = it; scrivi("Letti ${"%,d".format(it)} passi oggi.") }
            .onFailure { scrivi("ERRORE in lettura: ${it.message}") }
    }

    // ⚠️ Il tipo di ritorno è scritto a mano e non dedotto: `esegui` **chiama sé
    // stessa** nel ramo dei permessi revocati, e con un corpo a espressione senza
    // tipo il compilatore ci gira attorno — «Type checking has run into a
    // recursive problem». Non è pignoleria: senza, non compila.
    fun esegui(nome: String, azione: suspend () -> EsitoPassi): Unit = conPermessi {
        when (val esito = azione()) {
            is EsitoPassi.Ok -> {
                scrivi(esito.messaggio)
                PassiRepository.leggiOggi(context).onSuccess { passiOggi = it }
            }
            is EsitoPassi.Errore -> scrivi("ERRORE in $nome: ${esito.messaggio}")
            // Health Connect ha risposto che i permessi non ci sono più: capita
            // se vengono revocati dalle impostazioni mentre l'app è aperta.
            EsitoPassi.PermessiRichiesti -> {
                daRifare = { esegui(nome, azione) }
                chiediPermessi.launch(PERMESSI_PASSI)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {

            // ── Quanti ne risultano oggi ──────────────────────────────────
            Riquadro("PASSI DI OGGI") {
                Text(
                    passiOggi?.let { "%,d".format(it) } ?: "—",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("passi, tutte le sorgenti sommate", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { aggiorna() }, enabled = !occupato, modifier = Modifier.fillMaxWidth()) {
                    Text("Aggiorna lettura")
                }
            }

            // ── Aggiungerne ───────────────────────────────────────────────
            Riquadro("AGGIUNGI PASSI") {
                OutlinedTextField(
                    value = quanti,
                    onValueChange = { nuovo -> quanti = nuovo.filter { it.isDigit() }.take(7) },
                    label = { Text("Numero di passi") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Scorciatoie — si sommano:", style = MaterialTheme.typography.bodyMedium)
                // ⚠️ La riga SCORRE e non va a capo: coi caratteri di sistema
                // grandi quattro pulsanti su 360 px non ci stanno, e schiacciarli
                // sotto il polpastrello li renderebbe non toccabili.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(100L, 500L, 1_000L, 5_000L, 10_000L).forEach { passo ->
                        OutlinedButton(onClick = {
                            // ⚠️ Si SOMMA a quel che c'è nella casella, non lo
                            // sostituisce: non esiste un pulsante per ogni
                            // quantità, e sostituendo il secondo tocco non
                            // farebbe niente di visibile — si leggerebbe come un
                            // tocco non passato.
                            val ora = quanti.toLongOrNull() ?: 0L
                            quanti = (ora + passo).coerceAtMost(MAX_PASSI_PER_VOLTA).toString()
                        }) { Text("+${"%,d".format(passo)}") }
                    }
                    OutlinedButton(onClick = { quanti = "" }) { Text("↺") }
                }

                Button(
                    onClick = {
                        val n = quanti.toLongOrNull() ?: 0L
                        esegui("scrittura") { PassiRepository.aggiungi(context, n) }
                        quanti = ""
                    },
                    enabled = !occupato && (quanti.toLongOrNull() ?: 0L) > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Aggiungi a Health Connect") }
            }

            // ── Disfare ───────────────────────────────────────────────────
            Riquadro("HO SBAGLIATO") {
                Text(
                    "Toglie le righe che ha scritto questa app oggi. ⚠️ Solo le sue: " +
                        "Health Connect non lascia cancellare i dati di un'altra app, quindi " +
                        "i passi contati dal telefono restano dove sono.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { esegui("cancellazione") { PassiRepository.togliMiei(context) } },
                    enabled = !occupato,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Togli quello che ho aggiunto oggi") }
            }

            // ── Il log, come nella vecchia app ────────────────────────────
            Riquadro("LOG") {
                if (log.isEmpty()) {
                    Text("Nessuna operazione, per ora.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    log.forEach {
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

}

@Composable
internal fun Riquadro(titolo: String, contenuto: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(titolo, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            contenuto()
        }
    }
}

/**
 * ⚠️ `heightIn(min = …)` e non `height`: sul telefono i caratteri di sistema sono
 * ingranditi, e su un'altezza fissa la scritta è più alta del contenitore — quel
 * che avanza sparisce.
 */
@Composable
private fun BarraAlta(onVersione: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(BluBarra).heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "StepInj",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onVersione) { Text("⚙️", fontSize = 18.sp) }
    }
}
