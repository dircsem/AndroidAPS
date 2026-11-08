# MedLink — registro de alterações (bolus / stop / fila de comandos)

Alterações feitas em 2026-11-07 a partir de `BUGs.md` e `BUGs_TASKS.md` (tarefas T0–T13), mais o bug do `M` → "invalid command".
Regra principal (DEV.md): **nunca entregar insulina a mais**. Tudo foi feito com TDD (teste antes do código).

> Estado: **testado só com testes unitários**. As respostas `M`/`A` usadas nos testes são sintéticas. Falta o teste de campo com a bomba.

---

## 1. Fatos sobre a bomba/MedLink em que o código se baseia

- **`M` (BolusStatus)** devolve o último bolus (valor + hora da bomba) e o bolus quadrado (`square bolus: Xu delivered: Yu`).
- **`A` (StopStartPump)** responde o estado da bomba: normal, suspend ou bolusing.
- **Stop enviado durante um bolus aborta o bolus** (entrega parcial).
- As respostas chegam em **pedaços BLE de até 20 bytes**, que quebram ou juntam linhas. Por isso o parse procura os rótulos (`last bolus:`, `square bolus:`) no texto já juntado, e não linha a linha.
- `square bolus: 0.0u delivered: 0.000u` quer dizer só "nada em andamento", e **não** "entregue". Para saber se o nosso bolus entrou, compara-se a hora do último bolus (relógio da bomba) com a de uma leitura de **baseline** feita antes do envio.
- **Sem baseline, nunca reenviar**: o offset do relógio da bomba é desconhecido, então há risco de bolus duplo.
- O banco deduplica bolus por `temporaryId = hora do bolus na bomba`. Por isso todo registro usa a hora da bomba, e registrar duas vezes não duplica.

---

## 2. Arquitetura nova

As decisões de segurança ficam em classes Kotlin puras (testáveis), no pacote
`pump/rileylink/src/main/kotlin/app/aaps/pump/common/hw/medlink/bolus/`.
O `MedLinkBLE` só executa o que essas classes decidem.

| Classe | Função |
|---|---|
| `BolusStatusSnapshot` | Resposta `M` já parseada. `inProgress` = bomba em bolus, ou bolus quadrado > 0 e entregue < quadrado. |
| `BolusStatusInterpreter` | Compara o snapshot com o pedido e o baseline → `NOT_STARTED / DELIVERING / DELIVERED / PARTIAL / UNKNOWN`. |
| `BolusTracker` | Máquina de estados de cada bolus (QUEUED → SENT → CONFIRMED/DELIVERING/UNRESOLVED/UNKNOWN → DELIVERED/PARTIAL/FAILED). Devolve ações: Resend, ReadStatusAt, Record, Fail, Alarm, AskUser. |
| `StopGate` | Decide se um **stop automático** pode ir agora, se precisa ler `M` antes ou se deve esperar o bolus terminar. Stop do usuário vai sempre. |
| `StartStopSkipRule` | Só pula start/stop "já feito" se o estado veio de uma resposta da bomba **depois** do último start/stop enviado e tem menos de 60 s. |
| `BolusProgressRule` | Regra usada pelo `BolusProgressCallback` para decidir se registra o bolus. |
| `StartRetryRule` | (2026-11-08) Quando reenviar um START recusado: 30/60/120/300 s, alarme a partir da 2ª falha, desiste com alarme na 5ª. Cancela o reenvio se outro start/stop foi enviado depois ou se a bomba já respondeu Running. |

Regras importantes do `BolusTracker`:
- Primeiro `M` mostrando NOT_STARTED → UNRESOLVED e uma nova leitura de `M`. Só no **segundo** NOT_STARTED pode haver reenvio:
  - sem baseline → FAILED + alarme;
  - bolus do loop com mais de 5 min → falha;
  - já reenviado (`maxResend = 1`) → FAILED + alarme;
  - bolus manual → pergunta ao usuário (alarme);
  - senão → reenvia uma vez.
- Resposta desconhecida: relê após 30 s; alarme uma vez após 3 leituras desconhecidas ou 10 min.
- O total registrado nunca passa do valor pedido.
- Um novo bolus é recusado enquanto o anterior não estiver resolvido. Há também um intervalo mínimo de 1 min entre envios.
- Log por bolus: `BOLUS[id] state X->Y ...`.

---

## 3. Alterações por tarefa

- **T0 — Suspender pelo usuário**
  - `StopOrigin { USER, AUTOMATIC }` (core/interfaces).
  - Nova ação "Suspend pump" no MedLink (`MedtronicCustomActionType.MedLinkSuspendPump`).
  - `MedLinkPumpPluginBase.userSuspend()`; `CommandStopPump` chama esse método para MedLink.
  - O stop do usuário vai para o início da fila e descarta os bolus ainda não enviados (com alarme).
- **T1 — Stop automático não interrompe bolus**: `StopGate` lê `M` antes e adia o stop enquanto há bolus em andamento, sem `sleep`.
- **T2 — "Bolusing" ≠ "Suspended"**: novo estado `MedLinkPumpRunningState.Bolusing`; `BleStopCommand` e `BleStartCommand` tratam a resposta corretamente.
- **T3 — Stop durante bolus**: lê `M` e registra o valor parcial; o restante **nunca** é reenviado.
- **T4 — Reconexão**: se há bolus não resolvido, o primeiro comando após conectar é um `M`.
- **T5 — Interpretação estrita do `M`**: bolus novo = hora mais nova que o baseline, e não apenas o mesmo valor.
- **T6 — Bloqueio de bolus novo** enquanto o anterior não está resolvido (`deliverBolus` em `MedLinkMedtronicPumpPlugin`).
- **T7 — Fim dos reenvios às cegas**: removidos `resetBolusCommand`, `reprocessOnHold` e as novas tentativas de bolus em `close()` e `retryCommand()`. Bolus já escrito fica "on hold" e o `M` decide o que fazer.
- **T8 (parcial)** — alarme quando o resultado continua desconhecido (`uiInteraction.runAlarm`).
- **T11 (parcial)** — removidos os sleeps de 15 s (stop), 5 s (entre boluses), 180 s e 500 ms (GATT 133). O GATT 133 agora usa um backoff com `postDelayed` (5/15/60/180 s).
- **T12** — log com uma linha por passo do bolus.
- **T13 (parcial)** — o guard do `startPump` usa só o estado confirmado depois do último start/stop; `removeFirstCommand` não apaga mais todos os `StartPump`.

### Root cause 7 — `delivered == 0` não é mais "bolus entregue"
`BolusProgressCallback` captura o baseline na criação e só registra se o `M` mostrar um bolus mais novo que esse baseline. O registro usa o valor entregue e a hora da bomba.

### Extra — bolus "on hold" agora é registrado
Antes, um bolus que o `M` mostrava como entregue depois de uma desconexão nunca era registrado (IOB baixo → o loop entregava a mais). Agora é registrado com a hora da bomba, o que é idempotente.

---

## 4. Bug: `M` seguido de "invalid command"

**Causa**
- O `M` é um executor com um passo só. Depois de `m command confirmed`, a posição passa do fim da lista, mas o executor fica no início da fila até chegar o "ready".
- Qualquer `nextCommand()` nesse intervalo postava o mesmo executor de novo. Exemplos: um "time to powerdown" que zera `commandQueueBusy`, ou um `requestBolusStatus` agendado.
- O `run()` escrevia `nextCommandData()` (**0 bytes**) antes de checar se estava vazio. O MedLink respondia "invalid command", e o `run()` fazia `clearExecutedCommand` + `close(true)`.
- O marcador `RemoveStopCommand` (código vazio) também entrava na fila e escrevia 0 bytes.

**Correção**
- `CommandExecutor`:
  - `dataToWrite()`: bytes do próximo passo, ou `null`.
  - `isAwaitingAnswer()`: todos os passos enviados e a resposta ainda não tratada.
  - `postDecision(current)`: `POST`, `WAIT_FOR_ANSWER` ou `DROP_COMPLETED`.
- `MedLinkBLE`:
  - o `run()` do executor normal nunca escreve payload vazio;
  - a escolha do próximo comando não reposta um executor que espera a resposta e remove os que não têm nada a enviar;
  - um bolus rastreado removido assim vai para "on hold", e quem decide continua sendo o tracker.
- `BleBolusStatusCommand`: "time to powerdown 5" sem resposta do `M` → lê o `M` de novo desde o início. É seguro porque o `M` só lê.

---

## 4b. Bug 4 (BUGs.md): bomba não reativa depois de basal temporária 0% (2026-11-08, T13)

**Causa**
- O START tem dois passos: `A` (lê o estado) e `START`. O `BleStartCommand` tratava "pump suspend state" sempre do mesmo jeito, chamando `completedCommand()`.
- Na resposta ao `A` isso está certo (a bomba está suspensa, então envia o START).
- Mas se o START já tinha sido escrito e a bomba continuava suspensa (por exemplo, mensagem na tela da bomba, que bloqueia comandos), o executor ficava sem nada para enviar e era removido na escolha seguinte (`DROP_COMPLETED`). Sem callback, sem nova tentativa, sem alarme.
- A operação REACTIVATE do agendador de basal temporária já estava marcada como emitida e era removida no refresh seguinte, então a bomba ficava suspensa.
- O mesmo acontecia quando o START esgotava o `MAX_TRIES` (quedas de BLE): era removido em silêncio.

**Correção**
- `BleStartCommand`: "pump suspend state" com o próximo passo = `StartPump` → resposta ao `A`, segue normal. Senão → `bleComm.onStartFailed(...)`.
- `MedLinkBLE.onStartFailed` / `scheduleStartRetry` / `retryStart`: tira o executor das filas, decide pelo `StartRetryRule`, reenfileira com `postDelayed` (sem sleep), com `clearExecutedCommand()` e `resetRetries()`. Não duplica se já há outro START na fila.
- Os dois caminhos de `MAX_TRIES` (`completedCommand` e `retryCommand`) também chamam `scheduleStartRetry` quando o executor é um START.
- `setLastPumpStatus(Running)` zera o contador de falhas.
- Reenviar START é seguro: nunca entrega insulina, e se a bomba já estiver rodando o passo `A` termina logo.
- O alarme usa o `bolusAlarmHandler` existente (título "bolus alarm").

**Testes**: `bolus/StartRetryRuleTest` (novo) e 2 testes novos em `ble/command/BleStopStartCommandTest`.

### 4c. Causa real do bug 4, achada nos logs de campo (2026-11-08)

Logs analisados: `~/Downloads/AndroidAPS_LOG_*.zip` (2024-05, 2024-07/08, 2026-04-13, 2026-05-01), `~/Documents/bomba/logs analisar/AndroidAPS.log`, `~/projects/bomba/logs/AndroidAPS.log`.

**Respostas reais da MedLink (start/stop)**
- START com sucesso: `start command confirmed` → `switching pump to normal state` → `send command for pump wakeup` → `confirmed pump wakeup` → `check pump status` + `pump normal state` → `ready`.
- `A` com a bomba suspensa: `a command confirmed` → `pump suspend state`. Os pedaços BLE têm até 20 bytes; `a command confirmed\n` ocupa um pedaço inteiro, então o estado chega sempre num pedaço separado.
- Bolus em andamento: `pump is bolusing state` (às vezes cortado como `pump is bolusing sta`). **`BleStopCommand` procura `pump bolusing state`, que não aparece em nenhum log** — ver pendências.
- Nenhum log mostra um START recusado com `pump suspend state` (a seção 4b continua só como proteção). As falhas de START vistas foram:
  - `invalid command: START` + `+lost ok+conn` (START escrito logo depois de uma reconexão). O executor recomeça pelo `A`, e isso já funciona.
  - `switching pump to normal state` sem o status final, seguido de `time to powerdown`. O `BleStartStopCommand` reenvia `A`, que lê `pump normal state`.
- Nos logs, a linha `onCharacteristicChanged():N` é o pedaço novo e `N+1` é o anterior (`lastCharacteristic`). Não confundir os dois ao ler.

**Causa**: remoção dupla na fila de operações da basal temporária (`TempBasalMicrobolusOperations`).
- O refresh remove a operação da cabeça assim que ela está marcada como emitida (`isCommandIssued`).
- Os callbacks de SUSPEND, REACTIVATE e BOLUS também chamavam `operations.poll()`, que remove **a cabeça atual**.
- Quando o STOP demora mais que um refresh, o callback remove a operação seguinte: o REACTIVATE.
- Exemplo (2026-05-01): plano SUSPEND 11:18 / REACTIVATE 11:28 / SUSPEND 11:33 / REACTIVATE 11:43.
  - 11:18:20: o refresh já tirou o SUSPEND (o STOP só é escrito às 11:18:33).
  - 11:18:50: o callback tirou o REACTIVATE@11:28.
  - O próximo START foi às 11:42:39: 24 min suspensa em vez de 10.
- O mesmo às 13:30: REACTIVATE@14:00 perdido, START só às 14:23 (52 min em vez de 30).
- Também podia remover a primeira operação de um **plano novo**, quando o callback chegava depois de um `updateOperations`.

**Correção**
- `TempBasalMicrobolusOperations.complete(op)` remove só aquela operação (por identidade). Todos os callbacks usam `complete(oper)` em vez de `poll()`.
- O `poll()` do refresh (que remove a cabeça já emitida) foi mantido para a fila não travar.
- O callback do REACTIVATE tinha `&& !oper.isCommandIssued`, que era sempre falso. Foi removido.

**Testes**: `pump/medtronic/src/test/.../data/dto/TempBasalMicrobolusOperationsTest` (4 testes). Medtronic 143/143, rileylink 448/448, app compila.

## 5. Correções de infraestrutura (necessárias para rodar os testes)

- `shared/tests/.../TestPumpPlugin.kt`: removido o parâmetro `ConcentrationHelper`, que não existe neste fork. Sem isso nenhum teste compilava.
- `BLECommOperation.java` estava em `src/main/kotlin` e nunca era compilado (a classe faltava em runtime). Foi convertido para `BLECommOperation.kt`.

---

## 6. Testes

Comando: `./gradlew :pump:rileylink:testFullDebugUnitTest --offline` (≈ 3 min).
Resultados em `pump/rileylink/build/test-results/testFullDebugUnitTest/*.xml`.

| Arquivo (`pump/rileylink/src/test/kotlin/.../medlink/`) | Cobre |
|---|---|
| `bolus/MedLinkStatusParserSnapshotTest` | parse do `M` em pedaços BLE |
| `bolus/BolusStatusInterpreterTest` | regras de novo/velho/ambíguo, parcial, desconhecido |
| `bolus/BolusTrackerTest` | estados, reenvio, alarmes, registro |
| `bolus/StopGateTest`, `bolus/StartStopSkipRuleTest`, `bolus/BolusProgressRuleTest` | stop, skip, progresso |
| `ble/command/BleStopStartCommandTest`, `ble/command/BleBolusCommandsTest` | tratamento das respostas `A`/`M` e do bolus |
| `activities/BolusProgressCallbackTest` | root cause 7 |
| `ble/NoSleepOnBolusPathTest` | sem sleep nos caminhos de bolus/stop |
| `ble/CommandExecutorPostTest` | bug do `M` → "invalid command" |

Fixtures sintéticas: `pump/rileylink/src/test/resources/medlink/` (veja o `README.md` de lá).
Última execução (2026-11-08): rileylink 448/448, `:app:compileFullDebugKotlin` OK. Anterior: rileylink 439/439, medtronic sem falhas, `:app:compileFullDebugKotlin` OK.
`HardLimitsImplTest` (minDia/maxDia) em `implementation` já falhava antes e não tem relação com estas mudanças.

---

## 7. Pendências

- Capturas reais de `M`/`A` para substituir as fixtures. A mais importante: o que o `M` mostra **durante** um bolus, e se o "time to powerdown" pode chegar antes do "ready".
- T8: tela para o usuário resolver manualmente um bolus (informando o que o histórico da bomba mostra).
- T9: remover as flags antigas de status que ainda convivem com o tracker.
- T10: um fake no nível de transporte BLE para testar o `MedLinkBLE` de ponta a ponta.
- T11: os sleeps restantes (`BleCommand` 700 ms, `BleConnectCommand` 500 ms, `BleCalCommand` 60 s, o espaçamento de 2,2 s do executor, `medLinkConnect`).
- T13: ~~nova tentativa ou alarme quando a bomba continua suspensa depois do START~~ feito (seção 4b). Falta: o callback do START (e o REACTIVATE do agendador) não recebe o resultado quando o START é recusado; o reenvio é só no nível BLE. O alarme ainda usa o título de bolus.
- ~~Confirmar a resposta de um START recusado~~: os logs não têm nenhum caso (seção 4c); a causa real era outra.
- ~~`BleStopCommand` procurava `pump bolusing state`~~ corrigido em 2026-11-08: novo `bolus/PumpStateAnswer.isBolusing()` (texto real `pump is bolusing st`, cobre o pedaço BLE `pump is bolusing sta`), usado por `BleStopCommand` e `BleStartCommand`. Antes, a resposta "bolusing" do `A` não era reconhecida (caía no tratamento genérico) e a proteção do stop automático dependia só da leitura do `M` feita antes (StopGate). Agora o `A` também retém o stop (T1). Testes e fixture `a_bolusing.txt` usam o texto real.
- Um BOLUS de microbolus que falha volta a `isCommandIssued = false`, mas o refresh pode já tê-lo removido (microbolus perdido: entrega a menos, não a mais).
- Teste de campo com a bomba antes de usar.

## 8. O que conferir no log durante o teste de campo

- `BOLUS[id] state X->Y` — todo bolus deve terminar em DELIVERED, PARTIAL ou FAILED (com alarme).
- `automatic stop: reading M first` / `automatic stop deferred until ...` — stop automático esperando o bolus.
- `waiting for the answer of BolusStatus, not posting again` — o bug do `M` sendo evitado. Não deve mais aparecer "invalid command" logo depois de um `M`.
- `M answer lost, reading M again` — sessão encerrada antes da resposta do `M`.
- Depois do fim de cada SUSPEND de basal temporária, deve haver um `writing <START` perto do horário do REACTIVATE (bug 4c).
- `START refused: pump still suspended` / `START failed (...), failure N` / `START retry N` — bug 4. Depois do fim de uma basal 0% deve aparecer "pump normal state" (ou o reenvio).
