# PoE Meta Advisor

Sito web da far girare **in locale** che analizza il meta delle build e il mercato di
**Path of Exile 1**, usando i dati pubblici di [poe.ninja](https://poe.ninja).

Lo scopo è aiutare:

- **chi farma**, a capire cosa sale o scende di prezzo in questo momento;
- **chi crafta**, a capire quali item gialli vengono usati dalle build più giocate;
- **chi gioca una build**, a vedere com'è fatta davvero la versione "tipo" giocata dalla maggior parte dei giocatori.

> Progetto amatoriale, non affiliato a Grinding Gear Games né a poe.ninja.

---

## Avvio rapido

Requisiti: **Java 21** (per esempio [Temurin](https://adoptium.net/)). Maven non serve: c'è il Maven Wrapper.

```bash
git clone <url-del-repository>
cd progettoPoe
```

Su Windows:

```bash
mvnw.cmd spring-boot:run
```

Su Linux / macOS:

```bash
./mvnw spring-boot:run
```

Poi apri **http://localhost:8080**.

Il primo avvio scarica le dipendenze e impiega qualche minuto; i successivi sono veloci.
I dati (analisi, storico prezzi, preferite) vengono salvati nella cartella `data/`, che
**non** finisce su GitHub: ognuno ha il suo database.

Per usare un'altra porta: imposta la variabile d'ambiente `PORT` (es. `PORT=9090`).

---

## Cosa fa

### Build (pagina *Build*)

1. Mostra tutte le **coppie skill + ascendancy** della lega, con numero di personaggi e quota.
   La stessa skill giocata da due ascendancy diverse è considerata come due build diverse.
2. Selezioni una o più coppie e premi **Analizza**: per ogni coppia vengono scaricati
   **200 personaggi** (configurabile) in background, circa **17 minuti a coppia**
   (vedi [Limiti di poe.ninja](#limiti-di-poeninja)). Le analisi vanno in coda e continuano
   anche se chiudi la pagina; se riavvii il sito riprendono da dove erano.
3. Appena finita, l'analisi mostra anche le **statistiche su tutti i personaggi della coppia**
   (unique, gemme, keystone, anoint, bloodline, slot con item giallo), fornite direttamente da poe.ninja.
4. I personaggi scaricati vengono raggruppati in **archetipi**, cioè gruppi che giocano sostanzialmente
   la stessa build (vedi [Come funzionano gli archetipi](#come-funzionano-gli-archetipi)).
5. Per ogni archetipo vedi:
   - quota di giocatori e numero stimato nella lega;
   - **build rappresentativa** (il personaggio più simile a tutti gli altri del gruppo),
     con link al profilo su poe.ninja e **codice Path of Building** da copiare;
   - gemme di supporto, unique, keystone, mastery e bloodline, divise in
     **core** (≥ 70% dei personaggi) e **flessibili**;
   - **item gialli per slot**: basi e mod più frequenti. I mod del profilo (presenti in almeno
     il 40% degli item, massimo 6) sono in **grassetto**, gli altri in grigio; il link
     **"Vedi item simili"** cerca sul trade la base con **tutti** i mod in grassetto;
   - **gemme costose da comprare** e **costo stimato** della build (unique e gemme core ai prezzi attuali);
   - lista di unique/gemme da copiare (per esempio su Discord).
6. **Estendi analisi** aggiunge altri 200 personaggi per avere dati più precisi.
7. Con la ☆ una coppia diventa **preferita** e si aggiorna da sola ogni 6 ore.

### Item richiesti (pagina *Item richiesti*)

Elenco dei profili di item gialli (slot + base + mod principali) usati dagli archetipi delle
coppie analizzate, ordinati per numero stimato di giocatori. Per ognuno c'è il pulsante
**"Item simili"** che apre il trade ufficiale con una ricerca già compilata
(stessa base, item raro e **tutti** i mod del profilo, ognuno accettato come normale, crafted o fractured).
Il profilo contiene i mod presenti in almeno il 40% degli item di quello slot (massimo 6, come su un item vero).
I mod per cui il trade non ha un filtro sono segnati come "non cercabile".
Il sito non calcola margini o metodi di craft: il come craftare resta a chi crafta.

### Currency e Unique

- Currency e frammenti **in crescita e in calo** (24 ore o 7 giorni).
- Unique in crescita e in calo, con link al trade.
- Grafico dello **storico a 30 giorni** salvato in locale (si riempie col tempo mentre il sito è acceso).
- Vengono esclusi gli oggetti con pochi scambi o di valore molto basso, per evitare variazioni casuali.
- Vengono esclusi anche i **prezzi inaffidabili**: se il volume scambiato (in chaos) è meno del doppio
  del prezzo, cioè sono passati di mano al massimo un paio di pezzi, il prezzo viene probabilmente da
  uno scambio isolato. Esempio reale: Fragment of Terror a 25 div con solo ~2.071 c di volume totale.
  Nella ricerca questi oggetti compaiono con l'etichetta "pochi scambi, prezzo inaffidabile".
- Per le leghe recenti i prezzi di currency e frammenti vengono dal Currency Exchange, quindi al posto
  del numero di offerte viene mostrato il **volume scambiato**.

### Ricerca e wiki

- La barra in alto cerca qualsiasi oggetto con prezzo, variazioni e storico.
- Ogni oggetto, gemma, base o skill ha un piccolo link **wiki** alla [PoE Wiki](https://www.poewiki.net).
  Per gli item gialli il link porta alla pagina della base.

### Altro

- Selettore della **lega** in alto (la scelta viene ricordata).
- **Tema scuro / chiaro** con il pulsante ◐.
- Prezzo della Divine Orb sempre visibile in alto.

---

## Come funzionano gli archetipi

Per ogni personaggio vengono considerati:

| Elemento | Peso predefinito |
|---|---|
| Gemme di supporto collegate alla skill principale | 35% |
| Nodi dell'albero passivo | 35% |
| Unique, keystone, mastery e bloodline | 30% |

La **somiglianza** tra due personaggi è la media pesata dell'indice di Jaccard di ogni categoria
(elementi in comune diviso elementi totali): 0 = niente in comune, 1 = identici.

Il raggruppamento è **gerarchico ("average linkage")**: si parte con ogni personaggio da solo
e si uniscono via via i due gruppi più simili, finché la loro somiglianza media supera la soglia
(0,55 di default). I gruppi con meno di 3 personaggi finiscono in "Varianti sperimentali";
gli archetipi sotto il 5% della coppia sono segnalati come "variante minore" (dati poco affidabili).

Il nome di ogni archetipo è dato dai due elementi più caratteristici rispetto al resto della coppia.

---

## Configurazione

Tutto si regola in [`src/main/resources/application.yml`](src/main/resources/application.yml), sezione `poemeta`:

| Chiave | Default | Significato |
|---|---|---|
| `request-delay-ms` | 1000 | Pausa tra due richieste a poe.ninja. **Non scendere sotto 500.** |
| `builds.character-delay-ms` | 5000 | Pausa tra due download di personaggi (vedi [Limiti di poe.ninja](#limiti-di-poeninja)) |
| `default-league` | auto | Lega di default (`auto` = challenge league più recente) |
| `builds.sample-size` | 200 | Personaggi per coppia skill + ascendancy |
| `builds.extend-size` | 200 | Personaggi aggiunti da "Estendi analisi" |
| `builds.min-characters` | 30 | Coppie con meno personaggi non vengono mostrate |
| `builds.favorites-refresh-hours` | 6 | Ogni quanto si aggiornano le preferite |
| `builds.character-cache-hours` | 24 | Per quanto un personaggio scaricato viene riutilizzato |
| `archetypes.similarity-threshold` | 0.55 | Soglia di somiglianza per lo stesso archetipo (più alta = più archetipi) |
| `archetypes.weight-*` | 0.35 / 0.35 / 0.30 | Pesi di gemme, albero, oggetti |
| `archetypes.core-threshold` | 0.70 | Quota oltre la quale un elemento è "core" |
| `economy.refresh-minutes` | 30 | Ogni quanto aggiornare i prezzi |
| `economy.history-every-minutes` | 60 | Ogni quanto salvare un punto dello storico |
| `economy.min-listings` | 10 | Offerte minime (o volume minimo in chaos per il Currency Exchange) per entrare nelle classifiche |
| `economy.min-chaos-currency` / `min-chaos-unique` | 0.5 / 10 | Valore minimo per entrare nelle classifiche |

---

## Da dove arrivano i dati

poe.ninja non ha un'API pubblica documentata: il sito usa gli stessi endpoint del frontend di poe.ninja.

| Dato | Endpoint | Formato |
|---|---|---|
| Leghe e snapshot | `/poe1/api/data/index-state`, `/poe1/api/data/build-index-state` | JSON |
| Currency e frammenti | `/poe1/api/economy/exchange/current/overview` (leghe recenti) oppure `/poe1/api/economy/stash/current/currency/overview` | JSON |
| Unique e gemme | `/poe1/api/economy/stash/current/item/overview` | JSON |
| Ricerca personaggi | `/poe1/api/builds/{versione}/search` | protobuf (max 100 righe) |
| Dizionari della ricerca | `/poe1/api/builds/dictionary/{hash}` | binario "NDIC" |
| Dettaglio personaggio | `/poe1/api/builds/{versione}/character` | JSON |
| Id dei mod per il trade | `https://www.pathofexile.com/api/trade/data/stats` | JSON |

Note:

- La ricerca restituisce al massimo 100 personaggi: per arrivare a 200 si uniscono più
  ordinamenti (livello, vita, DPS, energy shield, EHP).
- **Questi endpoint possono cambiare senza preavviso.** Se qualcosa smette di funzionare,
  il codice da aggiornare è nel package `it.poemeta.ninja`.
- Tutte le richieste passano da un unico client con pausa minima tra una e l'altra
  (`RateLimitedHttp`), e i personaggi già scaricati vengono riutilizzati. Per rispetto
  verso poe.ninja, non abbassare troppo `request-delay-ms`.
- Gli item gialli non hanno un prezzo su poe.ninja: per questo il costo stimato include
  solo unique e gemme, e per i gialli c'è il link al trade.
- Nei primi giorni di una lega (o negli eventi) poe.ninja può non avere ancora i prezzi
  di unique e gemme: in quel caso le relative sezioni restano vuote.

---

## Limiti di poe.ninja

Il **dettaglio dei singoli personaggi** su poe.ninja è protetto da Cloudflare: scaricando
circa 60 personaggi in un paio di minuti, l'IP viene bloccato per **un'ora** (risposta 429).
Il blocco vale anche per le pagine personaggio di poe.ninja aperte dal browser dallo stesso PC.

Per questo:

- tra due download di personaggi c'è una pausa dedicata (`builds.character-delay-ms`, 5 secondi di default):
  200 personaggi richiedono circa 17 minuti;
- se poe.ninja chiede comunque una pausa, l'analisi si ferma, mostra l'orario di ripresa e riparte
  da sola, senza perdere i personaggi già scaricati;
- i personaggi scaricati restano in cache per 24 ore (`builds.character-cache-hours`), quindi
  rianalizzare la stessa coppia è molto più veloce.

La ricerca e i prezzi non hanno questo problema e restano veloci.

---

## Struttura del progetto

```
src/main/java/it/poemeta
├── PoeMetaApplication.java      avvio Spring Boot
├── config/AppProperties         impostazioni da application.yml
├── ninja/                       client poe.ninja (HTTP con rate limit, protobuf, dizionari)
├── league/                      elenco leghe
├── economy/                     prezzi, variazioni, storico
├── analysis/                    coppie skill+ascendancy, analisi, archetipi, item richiesti
├── links/                       link a PoE Wiki e trade ufficiale
├── persistence/                 entità JPA e repository (H2 su file)
└── web/                         controller e preparazione dati per le pagine
src/main/resources
├── application.yml
├── templates/                   pagine Thymeleaf
└── static/                      CSS e JavaScript
```

Tecnologie: Java 21, Spring Boot 3.5, Thymeleaf, Spring Data JPA, H2.

Test:

```bash
./mvnw test
```

Il database si può ispezionare da http://localhost:8080/h2-console
(JDBC URL `jdbc:h2:file:./data/poemeta`, utente `sa`, password vuota).

---

## Idee per le prossime versioni

- Confronto tra il proprio personaggio e l'archetipo (cosa manca: gemme, nodi, oggetti).
- Ordine di progressione (gemme e oggetti per fascia di livello).
- Avvisi di prezzo (desktop o Discord).
- Diario delle sessioni di farm con profitto/ora reale.
- Fonte dati alternativa tramite le API ufficiali di GGG (richiede la registrazione di un'app OAuth).
