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
| `MedLinkBolusStatusSnapshot` | Resposta `M` já parseada. `inProgress` = bomba em bolus, ou bolus quadrado > 0 e entregue < quadrado. |
| `MedLinkBolusStatusInterpreter` | Compara o snapshot com o pedido e o baseline → `NOT_STARTED / DELIVERING / DELIVERED / PARTIAL / UNKNOWN`. |
| `MedLinkBolusTracker` | Máquina de estados de cada bolus (QUEUED → SENT → CONFIRMED/DELIVERING/UNRESOLVED/UNKNOWN → DELIVERED/PARTIAL/FAILED). Devolve ações: Resend, ReadStatusAt, Record, Fail, Alarm, AskUser. |
| `MedLinkStopGate` | Decide se um **stop automático** pode ir agora, se precisa ler `M` antes ou se deve esperar o bolus terminar. Stop do usuário vai sempre. |
| `MedLinkStartStopSkipRule` | Só pula start/stop "já feito" se o estado veio de uma resposta da bomba **depois** do último start/stop enviado e tem menos de 60 s. |
| ~~`BolusProgressRule`~~ | Removida no 4m com o `BolusProgressCallback` (o tracker segue o bolus e atualiza o diálogo). |
| `MedLinkBolusCommandAnswer` | (4m) Resposta do próprio comando BOLUS: valor e hora do último bolus, bomba em bolus ou não. |
| `MedLinkPumpClock` | (4m) Ignora o ano da bomba quando está claramente errado (`00`). |
| `MedLinkStartRetryRule` | (2026-11-08) Quando reenviar um START recusado: 30/60/120/300 s, alarme a partir da 2ª falha, desiste com alarme na 5ª. Cancela o reenvio se outro start/stop foi enviado depois ou se a bomba já respondeu Running. |

Regras importantes do `MedLinkBolusTracker`:
- Primeiro `M` mostrando NOT_STARTED → UNRESOLVED e uma nova leitura de `M`. Só no **segundo** NOT_STARTED pode haver reenvio:
  - sem baseline → FAILED + alarme;
  - ~~bolus do loop com mais de 5 min → falha~~ (removido em 4k);
  - 5 tentativas usadas (`MAX_ATTEMPTS`, 4k; antes `maxResend = 1`) → FAILED + alarme;
  - bolus manual → pergunta ao usuário (alarme);
  - senão → reenvia uma vez.
- Resposta desconhecida: relê após 30 s. Desde o 4k: 5 rodadas de `M` seguidas sem resolver o bolus → FAILED + alarme (antes: alarme após 3 leituras ou 10 min, e o bolus continuava bloqueando).
- O total registrado nunca passa do valor pedido.
- Um novo bolus é recusado enquanto o anterior não estiver resolvido. Há também um intervalo mínimo de 1 min entre envios.
- Log por bolus: `BOLUS[id] state X->Y ...`.

---

## 3. Alterações por tarefa

- **T0 — Suspender pelo usuário**
  - `MedLinkStopOrigin { USER, AUTOMATIC }` (core/interfaces).
  - Nova ação "Suspend pump" no MedLink (`MedtronicCustomActionType.MedLinkSuspendPump`).
  - `MedLinkPumpPluginBase.userSuspend()`; `CommandStopPump` chama esse método para MedLink.
  - O stop do usuário vai para o início da fila e descarta os bolus ainda não enviados (com alarme).
- **T1 — Stop automático não interrompe bolus**: `MedLinkStopGate` lê `M` antes e adia o stop enquanto há bolus em andamento, sem `sleep`.
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
- `MedLinkBLE.onStartFailed` / `scheduleStartRetry` / `retryStart`: tira o executor das filas, decide pelo `MedLinkStartRetryRule`, reenfileira com `postDelayed` (sem sleep), com `clearExecutedCommand()` e `resetRetries()`. Não duplica se já há outro START na fila.
- Os dois caminhos de `MAX_TRIES` (`completedCommand` e `retryCommand`) também chamam `scheduleStartRetry` quando o executor é um START.
- `setLastPumpStatus(Running)` zera o contador de falhas.
- Reenviar START é seguro: nunca entrega insulina, e se a bomba já estiver rodando o passo `A` termina logo.
- O alarme usa o `bolusAlarmHandler` existente (título "bolus alarm").

**Testes**: `bolus/MedLinkStartRetryRuleTest` (novo) e 2 testes novos em `ble/command/BleStopStartCommandTest`.

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

## 4d. T14: TBR e SMB próximos viram um só bolus na bomba (2026-11-08)

**Por quê**: o bolus de microbolus (TBR) e o SMB costumam chegar quase juntos. Cada bolus demora na bomba e o tracker exige 1 min entre envios (o `M` tem resolução de minuto), então o segundo esperava.

**O que mudou**
- `bolus/MedLinkLoopBolusMerge` (regra pura): só TBRBolus/SMBBolus se juntam, no máximo um TBR e um SMB, nunca bolus manual. Divide o entregue entre as partes na ordem da fila (a última fica com o resto) e dá a cada parte o horário `pumpTime + i * 1 s`.
- `MedLinkBLE.addCommands` → `mergeLoopBolus`: se chega um TBR/SMB e há na `bolusExecutionQueue` um bolus de loop do outro tipo **nunca postado** (não rastreado, não é o `currentCommand`, nada escrito), o argumento `BOLUS` desse executor é reescrito com a soma (0,1 + 0,4 → 0,5) e a mensagem nova não entra na fila (nem o `M` suplementar dela).
- O momento em que o bolus passa a ser rastreado (`beforePost`) e a junção usam o mesmo lock (`bolusMergeLock`): depois de rastreado, o valor não muda mais. O tracker recebe a soma como `requested`.
- Registro (`MedLinkBolusAction.Record` → `recordParts`): cada parte é gravada com a própria `DetailedBolusInfo` (tipo TBR → `isBasalInsulin`, tipo SMB). O id temporário do banco vem do horário; por isso as partes têm horários diferentes. A parte 0 fica no horário da bomba, então o bolus único de 0,5 U lido depois do histórico cai no mesmo id e não é somado de novo. O callback antigo do executor (parte 0) continua igual.
- A parte juntada recebe o resultado por `BleBolusCommand.onQueueResult` (antes `onMergedResult`) (ligado no `deliverBolus` do plugin): sucesso com o valor gravado para ela, ou falha quando o bolus falha, é descartado por stop do usuário ou é recusado antes da escrita (bomba suspensa). Na falha, o dono (operação de TBR / loop) pede de novo.

**Testes**: `bolus/MedLinkLoopBolusMergeTest` (8). rileylink 458/458, `:pump:medtronic:compileFullDebugKotlin` OK.

**Limites**
- Só junta enquanto o primeiro bolus ainda espera na fila. Depois de postado, o plugin recusa bolus novo até o atual ser resolvido (T6), como antes.
- Um terceiro bolus do mesmo tipo (ex.: outro SMB) não entra na junção e segue pela fila normal (com o intervalo de 1 min).
- Entrega parcial: o TBR (primeiro da fila) é preenchido antes do SMB.

## 4e. Intervalo do SMB usa a configuração do loop e ignora bolus de TBR (2026-11-08)

`MedLinkCommandSMBBolus.execute()`:
- O intervalo era fixo em 3 min e contado a partir de `getNewestBolus()`, que inclui os microbolus de TBR. Qualquer bolus de TBR bloqueava o SMB, inclusive o SMB juntado com ele (T14).
- Agora usa `IntKey.ApsMaxSmbFrequency` (configuração "smbinterval" do loop) e `getLastNonTBRBolusTime()` (último SMB ou bolus manual, sem TBR e sem priming). A restrição vale entre SMB e SMB e entre bolus manual e SMB.
- Quando o SMB é recusado pelo intervalo, o `callback` agora recebe o resultado (antes não recebia, diferente do `CommandSMBBolus` upstream).

**Teste**: `:implementation:compileFullDebugKotlin` OK. Sem teste unitário.

## 4f. T15: bolus manual não é bloqueado por bolus de TBR (2026-11-08)

**Antes**: o `deliverBolus` do plugin recusava qualquer bolus ("previous bolus unresolved") enquanto o tracker tinha um bolus sem confirmação, inclusive um microbolus de TBR. Além disso, a fila de bolus do `MedLinkBLE` era FIFO: um manual que chegava depois de um TBR ainda não enviado ficava atrás dele.

**Agora** (`bolus/MedLinkManualBolusRule`):
- Plugin: se o bolus pendente é um TBR (`MedLinkBLE.unresolvedBolusType()`), o manual não é recusado; vai para a fila e o `MedLinkBLE` envia quando o TBR for resolvido pelo `M` (continua um bolus por vez). Se o pendente é SMB ou manual, continua recusado.
- `MedLinkBLE.prioritizeManualBolus`: o manual passa na frente dos bolus de loop ainda não postados (junto com o `M` de progresso de cada um). Comparações por identidade (`CommandExecutor.equals` compara a lista de comandos).
- Um manual esperando mais de 5 min (`MedLinkManualBolusRule.MAX_WAIT_MS`) é descartado com alarme, e o resultado vai como falha via `onQueueResult`. Ele não pode ser entregue muito depois do pedido.
- Continua valendo a espera de até 1 min depois de um bolus (o `M` tem resolução de minuto) e a espera enquanto a bomba está entregando outro bolus. São esperas, não recusas.

**Testes**: `bolus/MedLinkManualBolusRuleTest` (3). rileylink 461/461, `:pump:medtronic:compileFullDebugKotlin` OK.

## 4g. T16: bolus manual junta o TBR; SMB pendente pede confirmação ao usuário (2026-11-08)

**Manual + TBR**
- Quando um manual entra na fila, `MedLinkBLE.absorbIntoManual` traz para dentro dele os bolus de loop ainda não postados (substitui o `prioritizeManualBolus` do T15). O executor do manual recebe a soma no `BOLUS` e os executores absorvidos saem da fila, junto com o `M` de progresso de cada um.
- Um TBR que chega com um manual na fila (não postado) também se junta ao manual (`MedLinkLoopBolusMerge.canMerge`).
- O manual é sempre a parte 0: é preenchido primeiro numa entrega parcial e fica com o horário da bomba. A origem no tracker continua MANUAL, então ele nunca é reenviado sem o usuário.

**SMB antes do manual**
- No `deliverBolus` do plugin, se há SMB pendente (`MedLinkBLE.pendingSmbAmount()`: na fila ou enviado e não resolvido), abre `dialog/MedLinkBolusConfirmActivity` ("Há um SMB de X U em andamento...").
  - **OK**: o manual segue com `smbConsent`. O SMB na fila é juntado (manual + SMB + TBR). Se o SMB já foi enviado, o manual espera por ele (`MedLinkManualBolusRule.mayQueueBehind(SMB, consent)`).
  - **Cancelar**, botão voltar ou 2 min sem resposta: o manual é cancelado e o callback recebe falha ("Bolus manual cancelado: SMB em andamento").
- A activity é translúcida e iniciada com `FLAG_ACTIVITY_NEW_TASK`, igual ao `ErrorHelperActivity`.

**Proteções**
- Um SMB que chega com um manual pendente (na fila, em andamento ou com a confirmação aberta) é recusado ("manual bolus pending"). O loop recalcula já com o manual no IOB.
- Um SMB na fila sem consentimento, ou um segundo TBR, não é juntado ao manual: ele falha via `onQueueResult` e o dono pede de novo.
- Se o manual expira (5 min, T15), as partes juntadas também recebem falha.

**Testes**: `MedLinkLoopBolusMergeTest` e `MedLinkManualBolusRuleTest` atualizados (rileylink 465/465). `:app:assembleFullDebug` OK. A activity e o fluxo do diálogo não têm teste automático.

## 4h. Bolus descartado da fila libera o tracker (2026-11-08)

**Sintoma** (log real, build anterior ao T15): `deliverBolus refused: previous bolus MedLinkBolusRequest(id=TBRBolus-…, state=QUEUED, attempts=0) unresolved` por mais de 10 min; todo SMB era recusado.

**Causa**: o tracker põe o pedido em `QUEUED` quando o primeiro comando do executor é escrito. Ele só saía de `QUEUED` por `onWritten` (dose escrita) ou `onRejectedBeforeWrite`. Se o executor saía da fila antes de a dose ser escrita (máximo de tentativas, erro com `removeFirstCommand(true)`, "nothing left to write", stop do usuário), ninguém avisava o tracker e o pedido ficava `QUEUED` para sempre.

**Agora**:
- `MedLinkBolusTracker.onDropped(reason)`: um pedido `QUEUED` (dose nunca escrita) vira `FAILED` e novos bolus são liberados. Um pedido já escrito não muda: o `M` decide.
- `MedLinkBLE.onBolusExecutorDropped` é chamado em `removeFirstCommand`, no `DROP_COMPLETED` do seletor de comandos e no `addUserStop`. As partes juntadas (T14) recebem falha.
- Stop do usuário: os bolus na fila são removidos e o tracker é avisado; um bolus em andamento continua sendo interrompido pelo STOP (`MedLinkStopOrigin.USER`).
- Conexão perdida: nenhum bolus é descartado. O caminho "GATT is null" limpava a fila de prioridade inteira, inclusive o `M` que verifica um bolus escrito; agora ele mantém os executores de bolus e de `BolusStatus` (`keepOnConnectionLoss`). Um bolus não escrito continua na fila (`holdCurrentBolus`), um escrito vai para o hold e é verificado pelo `M`.

**Testes**: `MedLinkBolusTrackerTest` (+3). rileylink 468/468.

## 4i. Só bolus do mesmo tipo é recusado; os outros juntam ou esperam o M (2026-11-08)

**Antes**: com um bolus sem resolução no tracker, o plugin recusava qualquer SMB/TBR ("previous bolus unresolved"); só o manual entrava na fila (T15/T16). Nada se juntava a um bolus já rastreado.

**Agora** (`MedLinkManualBolusRule.refuses`, `MedLinkLoopBolusMerge.canJoinTracked`, `MedLinkBLE.pendingBolusTypes/joinTracked/joinWaitingInto`):
- **Recusa**: só quando um bolus do mesmo tipo está pendente (na fila, juntado em outro, ou enviado sem resolução). Continua valendo do T16: SMB recusado com manual pendente (foi calculado sem o manual no IOB).
- **Dose ainda não escrita** (rastreado em `QUEUED`): um bolus de outro tipo se junta a ele (o valor do `BOLUS` é reescrito e `MedLinkBolusTracker.onMerged` atualiza o pedido). O `onWritten` do `beforePost` roda sob o `bolusMergeLock`: depois do valor gravado nada mais entra.
- **Escrito e sem resolução** (`UNRESOLVED`/`UNKNOWN`, ex.: conexão perdida): o novo bolus entra na fila e um `M` é lido na hora.
  - `M` prova que não foi entregue (dois `M`, regra do T7) e o tracker decide reenviar: os bolus que esperam se juntam ao reenvio (`joinWaitingInto`), a bomba recebe um bolus com a soma.
  - `M` mostra entregue/parcial/em andamento: o bolus que espera roda logo depois, sozinho.
  - Manual não entregue continua sem reenvio automático (T7): alarme, e o que espera roda sozinho.
- Um manual juntado a um bolus de loop muda a origem do pedido para `MANUAL` (nunca reenviado sem o usuário). SMB e manual juntos só com o consentimento do diálogo (T16).
- Como agora os bolus esperam em vez de serem recusados, todo bolus na fila (não só o manual) expira após 5 min (`MedLinkManualBolusRule.MAX_WAIT_MS`). Loop: falha sem alarme, o loop pede de novo; manual: falha com alarme.

**Diálogo do T16**: continua avisando o valor do SMB pendente; agora tem um campo com o valor do bolus manual, que o usuário pode **diminuir** (`MedLinkManualBolusRule.editedAmount`: arredonda para baixo no passo da bomba, maior que zero e no máximo o pedido, que já passou pelos limites do AAPS). Valor inválido mantém o diálogo aberto com erro.

**Testes**: `MedLinkManualBolusRuleTest` (refuses, editedAmount), `MedLinkLoopBolusMergeTest` (canJoinTracked), `MedLinkBolusTrackerTest` (onMerged). rileylink 475/475, `:app:assembleFullDebug` OK. O fluxo no `MedLinkBLE` e o diálogo não têm teste automático (T10).

## 4j. Bomba bloqueada, bolus de fora e tempo de vida da fila (2026-11-08)

> Bloqueio (`UNREACHABLE`/`NOT_ACCEPTING_COMMANDS`) e tempo de vida substituídos no 4k (PUMP_LOCKED e tentativas). O alarme de bolus de fora continua.

**Bug do 4i**: o tempo de vida (5 min) começava na primeira vez que o seletor de comandos olhava o bolus, e o seletor só roda conectado. Um bolus pedido durante 30 min sem conexão era enviado na hora ao reconectar. Agora o tempo conta de quando o bolus entra na fila, e um watchdog (`MedLinkBLE.runBolusWatchdog`, a cada 30 s, conectado ou não) expira os que passaram do tempo.

**Bloqueio da bomba** (`bolus/PumpBlockRule`, `MedLinkBLE.pumpBlock`):
- `UNREACHABLE`: um bolus enviado continua `UNRESOLVED`/`UNKNOWN` por mais de 5 min, ou o tracker já alarmou (M ilegível). O driver continua lendo o `M` (a cada 60 s, reconectando se preciso) e o bloqueio sai quando o bolus é resolvido. Alarme + notificação urgente "Bomba inacessível".
- `NOT_ACCEPTING_COMMANDS`: o START falhou e a nova tentativa alarmou (2ª falha) ou desistiu (T13). Notificação urgente pedindo para ver a tela da bomba. Os bolus na fila ficam guardados: não são enviados e não expiram. Quando a bomba responde Running (um START funcionou, ex.: o usuário limpou a mensagem), o bloqueio sai e o tempo de vida de todos os bolus na fila recomeça.
- `NOT_ACCEPTING_COMMANDS` tem prioridade sobre `UNREACHABLE`.
- Com a bomba bloqueada o plugin recusa novos bolus, TBR (exceto 100 %) e perfil. Leitura de status, cancelar TBR e o `M`/START do próprio driver continuam.

**Bolus que o driver não enviou** (`MedLinkBolusTracker.isOtherBolusInProgress`): o `M` mostra um bolus em andamento que não é o rastreado (dado nos botões da bomba, onda quadrada/dupla, ou começado antes do nosso). Se há bolus esperando, o usuário recebe um alarme com o valor programado e o já entregue, uma vez por bolus da bomba.

**Testes**: `PumpBlockRuleTest` (4), `MedLinkBolusTrackerTest` (+3). rileylink 482/482, `:app:assembleFullDebug` OK. Watchdog, notificação e recusas no plugin sem teste automático (T10).

## 4k. Bomba travada (PUMP_LOCKED), tentativas no lugar do tempo de vida, falhas do `M` e SMB de 0 U (2026-11-09)

Log analisado: `logs/invalid_smb.log` (dois SMB recusados).

**SMB 1 — "deliverTreatment: Invalid input" com `insulin=0.0`**
- Não era arredondamento: o AIMI bloqueou o SMB (`REFRACTORY_BLOCK`, `Limits: 1,42 → 0,00 U`), então `units=0.0` estava certo.
- O `LoopPlugin` chama `applySMBRequest` antes do TBR para `PumpPluginBase` **sem** checar `isBolusRequested`: todo resultado só de TBR enfileirava um SMB de 0 U.
- Correção: o caminho só roda com `isBolusRequested`, e `applySMBRequest` volta sem fazer nada com `smb <= 0`.
- SMB duplicado (corrigido só para MedLink; as outras bombas não mudam):
  - O `LoopPlugin` envia o SMB antes do TBR para toda `PumpPluginBase` e o callback do TBR chamava `applySMBRequest` de novo.
  - No MedLink o primeiro SMB só é registrado depois do `M`, então o segundo passava pelo intervalo do SMB e era recusado pelo driver ("mesmo tipo pendente", 4i). A recusa caía no `invoke("tempBasalFallback")`: uma rodada extra do loop a cada TBR+SMB.
  - Agora, com `pump is MedLinkPumpPluginBase` (`smbBeforeTbrOnly`), o callback do TBR não reenvia o SMB.
  - A falha do SMB enviado antes do TBR vai para `lastRun.smbSetByPump`, não mais para `tbrSetByPump` (só MedLink).
  - Testes do `:plugins:aps` não compilam por erros anteriores (`LoopPluginTest`, testes AIMI); verificado só com `:plugins:aps:compileFullDebugKotlin`.

**SMB 2 — "command refused: pump blocked (UNREACHABLE)"**
- A bomba respondia: o `M` era lido normalmente. O bloqueio vinha de um microbolus de TBR (0,1 U) em `UNKNOWN` ("cannot tell last bolus from the sent one"): o tracker alarmou e o 4j tratava isso como bomba inacessível, recusando SMB e TBR.
- A causa do `UNKNOWN` não aparece no log (o baseline foi lido antes do trecho). **Pendente**: log de antes do envio do bolus.

**O que mudou**
- `UNREACHABLE` e `NOT_ACCEPTING_COMMANDS` foram substituídos por `MedLinkPumpBlock.PUMP_LOCKED` (`bolus/MedLinkPumpLockRule`):
  - um comando confirmado pela MedLink sem efeito na bomba conta como evidência: START e a bomba continua suspensa (`onStartFailed`), STOP e a bomba continua rodando (`onStopFailed`, novo), bolus confirmado que dois `M` provam não entregue (`MedLinkBolusTracker.onConfirmedWithoutEffect`);
  - 2 seguidos (`LOCK_AFTER`) → travada: notificação urgente + alarme "Bomba bloqueada";
  - qualquer efeito (bolus entregue ou em entrega, bomba muda entre suspensa e rodando) zera a contagem e libera;
  - **nada é recusado** com a bomba travada: os comandos continuam indo (cada um com as próprias tentativas) e servem de teste; o primeiro que funcionar libera. O plugin não recusa mais bolus, TBR e perfil (`pumpBlockedResult` removido).
- STOP confirmado com a bomba ainda rodando: antes era removido em silêncio. Agora é reenviado como o START (`MedLinkStartRetryRule`: 30/60/120/300 s, alarme a partir da 2ª falha, desiste com alarme na 5ª; cancelado se outro start/stop foi enviado ou se a bomba já está suspensa).
- **Sem tempo de vida na fila** (removidos `MedLinkManualBolusRule.MAX_WAIT_MS/expired`, `bolusQueuedAt`, `dropExpiredBolus` e o "stale loop bolus" de 5 min do tracker). No lugar:
  - `MedLinkBolusRequest.attempts` conta **toda vez que o executor do bolus é postado** (`MedLinkBolusTracker.onTry`), inclusive quando a conexão cai antes de a dose ser escrita. Um bolus já escrito só conta outra tentativa quando o `M` autoriza o reenvio;
  - até 5 tentativas (`MAX_ATTEMPTS`; antes, 1 reenvio). Um bolus `QUEUED` sem tentativas sobrando sai da fila e falha (`dropBolusOutOfTries`; manual → alarme);
  - um bolus esperando atrás de outro espera até esse ser resolvido, o que agora é limitado pelas tentativas e pelas falhas do `M`.
- **Falhas do `M`** (`MedLinkBolusRequest.statusFailures`, `MedLinkBolusTracker.onStatusFailed`): "invalid command", `M` pedido há 60 s sem resposta (conexão caiu ou MedLink fora), resposta ilegível ou ambígua (`UNKNOWN`).
  - Cada falha relê o `M`; uma resposta que decide (entregando / não começou / entregue) zera a contagem.
  - 5 seguidas → o bolus vira FAILED com alarme ("pode ter sido entregue e não foi registrado: confira o histórico da bomba") e o tracker é liberado. **Risco aceito**: se o bolus tiver entrado, o IOB fica mais baixo que o real.
- `M` → "invalid command" (`BleBolusStatusCommand` → `MedLinkBLE.onBolusStatusInvalid`): conta como falha e relê. **2 seguidos** (`M_INVALID_RESET`) → a conexão com a MedLink é reiniciada (`close(true)`, reconecta porque há comandos na fila). Antes, o segundo "invalid command" em 20 s removia o `M` em silêncio.
- O watchdog (30 s) não expira mais bolus: com bolus escrito e sem resolução, pede `M` a cada 60 s (reconectando se preciso) e conta o `M` não respondido como falha.
- `MedLinkStopGate`: `MAX_UNKNOWN` virou `MAX_UNREADABLE_FOR_STOP` (mesmo valor, 3).

**Testes**: `MedLinkPumpLockRuleTest` (novo, substitui `PumpBlockRuleTest`), `MedLinkBolusTrackerTest` (5 tentativas, tentativa com queda de conexão, sem tempo de vida, falhas do `M`, sinais de trava). rileylink 484/484, `:app:assembleFullDebug` OK. Sem teste automático (T10): `onBolusStatusInvalid`/reset da conexão, watchdog, `onStopFailed`/reenvio do STOP, `dropBolusOutOfTries`.

**No log, durante o teste de campo**: `BOLUS[id] try N/5`, `BOLUS[id] M failure N/5: ...`, `M answered invalid command (N in a row)`, `resetting the MedLink connection`, `command without effect (...), N in a row`, `pump block none -> PUMP_LOCKED`, `pump accepts commands again`, `STOP refused: pump still running`, `STOP retry N`, `BOLUS ... dropped: not written after N tries`.

## 4l. Bomba suspensa de novo depois de um TBR > 100 % (2026-11-09)

Log: `logs/basal_problem.log`.

**O que aconteceu**
- 11:28:45: plano de TBR 0 % (SUSPEND/REACTIVATE), bomba suspensa.
- 11:28:56: chega TBR 1,35 U/h (basal 1,2). START às 11:29:10; a bomba volta ao normal às 11:29:25. O plano novo tem um só microbolus de 0,1 U (11:43).
- 11:29:39: SMB 0,4 U, confirmado pelo `M` às 11:30:36.
- 11:30:38: depois das leituras de status, `stopPump - AUTOMATIC`; STOP escrito às 11:30:55, bomba suspensa às 11:31:09 ("Stopping unstopped pump"). O mesmo às 11:32:39 com o TBR seguinte.

**Causa**: o STOP não vinha da fila de operações, e sim de `handlePumpStatus()` (roda a cada leitura de dados da bomba): ele suspende quando `temporaryBasal.rate == 0.0` e a bomba está rodando. O `temporaryBasal` do plugin vem do plano de microbolus, com `rate = 0.0` quando `absoluteRate == 0.0`. O plano de **um só bolus** (`TempBasalMicrobolusOperations(1, ...)`, TBR pouco acima da basal) não definia `absoluteRate`: ficava 0,0, então o TBR de 1,35 U/h aparecia como 0 % e qualquer leitura de status depois do SMB suspendia a bomba.

**Correção** (`MedLinkMedtronicPumpPlugin`):
- `buildFirstLevelTempBasalMicroBolusOperations`: `absoluteRate` = valor pedido em todo plano absoluto (antes só no plano com vários bolus).
- `handlePumpStatus`: não suspende se o plano tem operações BOLUS (TBR acima da basal nunca é 0 %).
- Os STOP/START das minhas mudanças (4k) não tinham relação com isso.

**Testes**: medtronic 143/143, `:pump:medtronic:compileFullDebugKotlin` OK. Sem teste automático para `handlePumpStatus`.

## 4m. A resposta do BOLUS decide; `M` só acompanha a entrega e o diálogo de progresso (2026-11-09)

Logs: `logs/basal_problem.log` (SMB 0,4 U às 11:29) e `logs/failed_bolus_sample`.

**A MedLink já responde o resultado do bolus** (depois de "bolus verification"):
- Entregando: `check pump status / pump is bolusing state / last bolus: 0.4u / ready` — sem hora enquanto entrega.
- Não entregue: `Pump normal state / Last bolus: 2.0u 09-11-26 13:22 / Ready` — o bolus anterior (enviado às 13:30).
- Bolus pequeno já entregue: `pump normal state / last bolus: <valor> <hora do nosso bolus>`.

**O que estava errado**
- `BleBolusCommand` mandava ao tracker só o pedaço BLE com `last bolus` (sem a hora) → "no M answer" → `UNKNOWN`, uma falha de `M` e mais um `M`.
- `BolusCallback` (plugin) tratava qualquer `last bolus` com hora como entregue: no caso de falha registrava de novo o 2,0 U antigo e respondia **sucesso** para o bolus de 0,5 U que não entrou. No caso de sucesso (`DeliveringBolus`) não chamava o callback da fila.
- O diálogo de progresso só fechava se houvesse um `M` extra (bolus > 0,2 U, `BolusProgressCallback`) e não mostrava progresso: o `M` em andamento era relido em loop (`clearExecutedCommand`) sem repassar o valor.
- A MedLink manda às vezes o ano `00` (`09-11-00 11:03`) e às vezes `26` para o mesmo bolus. Com isso o baseline parecia 26 anos mais velho ou mais novo que a resposta: provável causa do "cannot tell last bolus from the sent one" do `logs/invalid_smb.log` (4k).

**O que mudou**
- `bolus/MedLinkBolusCommandAnswer.parse`: lê a resposta inteira do BOLUS (pedaços juntados, no "ready"). `BleBolusCommand` → `MedLinkBLE.onBolusAnswer` → `MedLinkBolusTracker.onBolusAnswer`:
  - bomba em bolus e mesmo valor → `DELIVERING` (conta como efeito para a trava);
  - estado normal com bolus mais novo que o baseline → `DELIVERED`, registrado com a hora da bomba, sem `M`;
  - estado normal com o bolus anterior → não entregue, **sem segundo `M`**: a própria bomba verificou logo depois do comando. Conta como tentativa e como evidência de trava (confirmado sem efeito); reenvia se houver tentativas;
  - sem `last bolus` ou outro valor em bolus → o `M` decide (como antes).
- `M` agora só acompanha um bolus em entrega: bolus manual a cada 15 s (`PROGRESS_READ_MS`) para o diálogo; SMB/TBR uma vez no fim previsto. `BleBolusStatusCommand` não relê mais em loop. Nenhum `M` é enfileirado junto com o bolus (`bolusStatusMessage` = null); removidos `BolusProgressCallback`, `BolusProgressRule` e o executor contínuo do `M`.
- Diálogo de progresso: `MedLinkBolusTracker.onProgress` → `BleBolusCommand.onProgress` → `EventOverviewBolusProgress` (só bolus manual; com TBR/SMB juntados mostra a parte do manual). Sem valor no `M`, estima pelo tempo (40 s/U, no máximo 95 %). No fim: 100 % + `EventDismissBolusProgressIfRunning`.
- Um só resultado por bolus: `onQueueResult` agora também é chamado para a parte do próprio executor (registro, falha, alarme final, descarte); o plugin guarda a primeira chamada (`AtomicBoolean`). O `andThen` do `BolusCallback` só registra no log.
- `bolus/MedLinkPumpClock.normalizeYear`: uma hora da bomba a mais de 180 dias da leitura vai para o ano mais próximo da hora do telefone. Usado no `M` e na resposta do BOLUS.

**Testes**: `MedLinkBolusCommandAnswerTest` (4, textos reais), `MedLinkPumpClockTest` (3), `MedLinkBolusTrackerTest` (+5: em entrega, progresso do manual, não entregue sem 2º `M`, entregue pela resposta, `M` decide), `BleBolusCommandsTest` (resposta inteira no "ready"; `M` em andamento não relê em loop). rileylink 485/485, medtronic 143/143, `:app:assembleFullDebug` OK. Diálogo e fluxo no `MedLinkBLE` sem teste automático (T10).

**No log, durante o teste de campo**: `BOLUS[id] bolus answer: last xU@t bolusing=...`, `not delivered: the bolus answer shows the previous bolus`, `bolus answer ...: decided by the tracker`, `bolus result success=... delivered=...` (uma vez por bolus).

**Bolus com menos insulina (4m, complemento — `logs/bolus_success.log`)**
- Com comunicação ruim a bomba pode receber menos do que foi enviado. Depois do "BOLUS command confirmed":
  - **sem hora** (`Pump is bolusing state / Last bolus: 0.4u`): é o nosso bolus em entrega; o valor é o que a bomba recebeu. Se for menor que o pedido, ele é seguido como `DELIVERING` com esse valor (`MedLinkBolusRequest.programmed`), o `M` é lido no fim desse valor e o registro é `PARTIAL`. O resto **nunca** é reenviado. Valor maior que o pedido → o `M` decide;
  - **com hora**: normalmente o bolus anterior (não entregue). Bolus pequeno (< 0,3 U, `SMALL_BOLUS`) pode já ter terminado e mostrar a própria hora; o que decide é a hora comparada com o baseline. Hora nova e valor menor com a bomba em estado normal → `PARTIAL` na hora (antes esperava o fim previsto). Hora nova em bolus ≥ 0,3 U só gera um aviso no log.
- A procura por "ready"/"last bolus" na resposta não diferencia maiúsculas.
- Testes: `MedLinkBolusTrackerTest` (+2: bomba recebeu menos; bolus pequeno parcial), `MedLinkBolusCommandAnswerTest` (+1: `bolus_success.log`). rileylink 488/488, `:app:assembleFullDebug` OK.

**Pendente**: confirmar no campo se o `M` mostra o valor entregue durante um bolus normal (`square bolus ... delivered`); senão o diálogo usa a estimativa pelo tempo.

## 4n. Despertares do seletor de comandos multiplicavam (2026-11-09)

**Sintoma:** o loop deixou de rodar a cada 5 min e o LeakCanary passou a coletar heap dumps com frequência.

**Causa:** `mayRun` (bolus esperando o minuto seguinte, bomba entregando outro bolus, STOP segurado pelo `MedLinkStopGate`)
chamava `scheduleNextCommand`, que postava um `nextCommand()` novo a cada chamada. O seletor (`nextCommand` getter) é
avaliado várias vezes por `nextCommand()` (até 8 só em `needToAddConnectCommand` quando desconectado), então cada
despertar gerava vários outros: o número crescia exponencialmente a cada segundo enquanto o comando esperava,
inundando a thread do BLE e a memória.

**Correção:** `MedLinkBLE.scheduleNextCommand` mantém um único `Runnable` pendente; um pedido para mais tarde que o já
agendado é ignorado, um pedido mais cedo reagenda.

## 4o. Bolus normal em andamento aparece na linha "square bolus" do `M` (2026-11-09)

**Sintoma (`logs/bolus_error_message.log`):** bolus manual de 0,7 U; o diálogo de progresso funcionava até o primeiro
`M`, que gerava o alarme "pump is delivering a bolus ... square/dual wave that was not sent by AAPS" e o bolus virava
NOT_STARTED, mesmo sendo entregue.

**Causa:** durante um bolus normal o `M` mostra o bolus anterior em "last bolus" e o bolus em andamento na linha square:
`square bolus: 0.7u delivered: 0.200u` com `square bolus time: 0h:00m / 0h:00m`. O interpretador só olhava a idade
do "last bolus" (igual ao baseline → NOT_STARTED) e o tracker chamava aquilo de "outro bolus".

**Correção:**
- `MedLinkStatusParser` lê a duração da linha "square bolus time" (`MedLinkBolusStatusSnapshot.squareMinutes`); duração 0
  (ou sem a linha) é um bolus normal na linha square (`normalBolusOnSquareLine`).
- `MedLinkBolusStatusInterpreter.isSentBolusOnSquareLine`: bolus normal na linha square, lido depois do envio, não maior que
  o pedido e que o baseline não mostrava = o bolus enviado. Em andamento → DELIVERING com o `delivered` da linha
  (progresso real); terminado na linha square mas "last bolus" ainda antigo → UNKNOWN (lê de novo, nunca reenvia).
- `MedLinkBolusTracker.isOtherBolusInProgress` não acusa mais o bolus enviado (sem o alarme).
- Square maior que o pedido ou com duração > 0 continua sendo outro bolus (espera ele terminar).

**Testes:** `MedLinkBolusStatusInterpreterTest` (respostas do log), `MedLinkBolusTrackerTest` (resposta do BOLUS → `M` na linha
square → "last bolus" novo = registrado).

## 4p. Diálogo de progresso: etapas antes da entrega com barra em movimento (2026-11-09)

Antes de a bomba começar a entregar, o diálogo de um bolus manual mostra o que o bolus está esperando, com a barra
horizontal em modo indeterminado; quando a bomba entrega, volta ao percentual (do `M`, ver 4o).

- `MedLinkBolusPhase`: CONNECTING, READING_STATUS (baseline `M`), SENDING (com "tentativa n de 5" a partir da 2ª),
  CONFIRMED (MedLink confirmou, bomba ainda não começou), WAITING_OTHER_BOLUS.
- `MedLinkBLE.reportBolusPhase` chama `BleBolusCommand.onPhase` em `connectGatt`, no `mayRun` (baseline / outro bolus),
  no `beforePost` (cada tentativa) e na confirmação; não repete a mesma etapa e se cala quando o bolus está DELIVERING.
- O plugin só liga `onPhase` para bolus manual e envia "Conectando à bomba…" logo que o bolus começa.
- Core (isolado, outras bombas sem mudança): `EventOverviewBolusProgress(..., waiting = false)` e
  `BolusProgressData.waiting`; o `BolusProgressDialog` só fica indeterminado quando `waiting` é true.

## 4q. Regra do SMB: intervalo desde o último SMB ou bolus manual (2026-11-09, corrigida em 2026-11-10)

**Regra do usuário:** o `MedLinkCommandSMBBolus` só entrega o SMB quando o intervalo do SMB (`ApsMaxSmbFrequency`) já passou desde o **último SMB ou bolus manual** (`getLastNonTBRBolusTime`). TBR e microbolus de TBR não contam.

**Erros anteriores:**
- 2026-11-09: tratei o `deliverAt` como a hora do pedido e mudei a segunda checagem para `deliverAt + intervalo > agora`. Resultado (`logs/smbbolus_fail.log` 14:45:39): SMB com `deliverAt` 14:41:41 cancelado como "too old".
- A checagem original da segunda condição (`deliverAt + intervalo < agora`) também não era a regra: o loop preenche o `deliverAt` com a última TBR enviada (`lastRun.lastTBREnact`), então ela fazia a TBR contar no intervalo.

**Agora** (`MedLinkCommandSMBBolus.decide`):
- último SMB/manual há menos que o intervalo → recusa (`SMB requested but still in N min interval (last SMB/manual bolus ...)`);
- pedido sem `deliverAt` (0) → cancela (`SMB bolus canceled: request without deliverAt`), como antes;
- senão → entrega. Uma TBR recente não bloqueia o SMB.
- Se a bomba ativa não é MedLink, o callback recebe falha (antes ficava sem resposta).

**Testes:** `implementation/.../queue/commands/MedLinkCommandSMBBolusTest` (5, com os dois logs: TBR recente não bloqueia).

## 4r. Comandos postados velhos escreviam depois de trocados; `M` repetido (2026-11-09)

**Sintoma (`logs/inginiyr_m_command.log`):** `M` enviado sem parar por ~40 min. O trecho do log não chega ao fim da sequência, mas mostra a causa:
- 21:42:27.513 o `M` de baseline (M#1) vira o comando atual e é postado na thread BLE, atrás de um post do `A` do START ainda esperando o espaçamento de 2,2 s.
- 21:42:29.349 o post velho escreve `A`; a resposta é atribuída ao M#1 ("M answered invalid command"), que é removido, e um M#2 é postado.
- 21:42:31.548 o post do M#1 (removido) escreve `M`; a resposta vai para o M#2, o baseline é lido e o bolus TBR de 0,1 U é postado.
- 21:42:33.748 o post do M#2 escreve outro `M`; a resposta (`ready`) é tratada como resposta do BOLUS e o bolus é descartado (`removed (forced)`), e às 21:42:35.94 o post do bolus ainda escreve `X`.
- Cada resposta atribuída ao comando errado gera outro `M` (baseline, "invalid", leitura de novo), então a sequência se mantém sozinha.
- 21:46:55: dois `M` em 42 ms; o timer de leitura atrasada não era cancelado quando uma leitura mais cedo era pedida.

**Causa:** `nextCommand` postava o executor direto no `bleHandler`; o `run()` esperava o espaçamento e escrevia mesmo que o executor já tivesse sido removido ou trocado (por `onBolusStatusInvalid`, `completedCommand`, etc.). `requestBolusStatus` postava um timer novo a cada pedido mais cedo sem tirar o anterior.

**Correção:**
- `MedLinkBLE.runPost`: cada post tem um número (`postSeq`). O espaçamento de 2,2 s roda antes; depois o executor só escreve se o post ainda é o último e o executor ainda é o `currentCommand` (`MedLinkPostGuard.isStale`). Um post velho só registra `stale post of X ...: not writing` e não mexe em `commandQueueBusy`.
- `requestBolusStatus`: um só `Runnable` atrasado (`pendingStatusRead`); um pedido mais cedo substitui o anterior.

**Testes:** `ble/MedLinkPostGuardTest` (4, sequência do log). rileylink 498/498.

**No log do teste de campo:** `stale post of ...: not writing` deve aparecer de vez em quando; não deve mais haver `M answered invalid command` logo depois de um `writing <A`, nem bolus `removed (forced)` logo depois de um `writing <M`.

## 4s. `M` depois de um bolus que a resposta do BOLUS já resolveu (2026-11-10)

**Sintoma (`logs/inginiyr_m_command.log`):** TBR de 0,1 U: 21:41:46.017 `BOLUS M queued`; 21:41:47.424 a resposta do BOLUS (`last bolus: 0.1u ... 21:41`, com hora, bomba parada) registra o bolus como DELIVERED; 21:41:47.452 o `M` é escrito mesmo assim. Num bolus pequeno a resposta do BOLUS já traz o bolus com a hora (ver `logs/bolus_success.log` e 4m), então esse `M` não serve para nada e ocupa o MedLink.

**Causa:** ao escrever/confirmar o bolus o tracker agenda um `M` no fim previsto (`ReadStatusAt(expectedEndAt)`) como reserva. O timer e o `M` na fila não olhavam se o bolus ainda precisava ser verificado.

**Correção (`MedLinkBLE`):**
- Os `M` pedidos pelo tracker (`ReadStatusAt`, timer, watchdog) ficam marcados (`trackerStatusReads`).
- O timer não enfileira o `M` se não há bolus a verificar (`bolusTracker.hasUnresolved()` falso).
- Um `M` do tracker que já estava na fila é removido no seletor (`mayRun`) quando o bolus foi resolvido: `BOLUS M not needed: no bolus left to verify`.
- Os `M` de baseline, do stop automático e de "outro bolus em andamento" não são marcados e continuam iguais.
- Continua havendo `M` quando a resposta do BOLUS diz `Pump is bolusing state` sem hora (bolus maior, ainda entregando): é o `M` do fim que dá a hora do registro.

**Testes:** `MedLinkBolusTrackerTest` (bolus entregue pela resposta: nada a verificar). O fluxo no `MedLinkBLE` não tem teste automático (T10).

## 4t. Bolus juntados registrados como um só (2026-11-10)

**Sintoma (`logs/tbr_manual_bolus.llog`):** 08:22:34 `BOLUS manual merged: NORMAL 4.0U, TBR 0.1U -> 4.1U`. Às 08:26:35 o `M` confirma 4,1 U e o registro é **um** bolus NORMAL de 4,1 U (sem `BOLUS part ...`). Só o manual recebe resultado; a parte TBR nunca recebe, o plano de TBR acha que os 0,1 U não foram entregues e envia outro TBR de 0,1 U logo depois (08:26:35, entregue às 08:26:57): 0,1 U a mais.

**Causa:** as partes juntadas ficavam num `HashMap` com o executor como chave. `CommandExecutor.hashCode()` usa `commandPosition`, `functionPosition` e `nrRetries`, que mudam enquanto o bolus é escrito; na hora do registro a busca não achava as partes e `recordParts` registrava tudo como o bolus do próprio executor. Vale para todas as junções: TBR + SMB (T14), junção ao bolus rastreado (4i) e manual + TBR/SMB (T16).

**Correção:** `mergedBolusParts` (e o `trackerStatusReads` da 4s) usam identidade (`IdentityHashMap`). Cada parte volta a ser registrada com o seu tipo e a sua hora (manual na hora da bomba, TBR/SMB 1 s depois) e recebe o seu resultado.

**Histórico da bomba:** a bomba mostra um bolus só (4,1 U às 08:23:00). O `temporaryId` é a hora com segundos, então a parte manual fica com 08:23:00 e a TBR com 08:23:01; o 4,1 U do histórico encontra o registro de 08:23:00 e não é inserido nem altera nada (`InsertBolusWithTempIdTransaction` só insere). O IOB fica 4,0 + 0,1.

**Testes:** `ble/CommandExecutorIdentityKeyTest` (o hash muda durante a escrita; o mapa por identidade mantém as partes). rileylink 500/500.

**No log do teste de campo:** depois de `BOLUS manual merged` / `BOLUS merged`, deve haver um `BOLUS part ...` por parte, e nenhum TBR reenviado logo depois.

**Vazamento de memória (`logs/memmory_leak.log`, 08:26:58, mesma sessão):** o LeakCanary acusou o `InsulinDialog` retido por `MedLinkBLE.mergedBolusParts` (HashMap) → `BleBolusCommand.onQueueResult` → `MedLinkCommandBolus.callback` → `InsulinDialog`, 23 s depois do registro do bolus (08:26:35). Como a busca no HashMap falhava, a entrada nunca era removida (`recordParts`/`failMergedParts` usam `remove`) e cada bolus juntado deixava o callback e o diálogo presos para sempre. Com o `IdentityHashMap` a entrada sai quando o bolus é registrado ou falha. Durante a entrega de um bolus longo o LeakCanary ainda pode acusar o diálogo retido: o callback fica vivo até o resultado, e é liberado depois.

**Teste de campo (2026-11-10):** TBR + bolus manual e TBR + SMB confirmados pelo usuário: registrados separados e sem TBR reenviado. Manual + SMB ainda sem teste de campo (mesma correção).

## 4u. "Falha ao atualizar o perfil" com a bomba conectada (2026-11-10)

**Sintoma:** às vezes aparece o alarme de falha ao atualizar o perfil basal, mas o app está conectado e muda o estado da bomba. Nos logs (`tbr_manual_bolus.llog` 08:22:35, `samplelog.log` 15:37:03, `inginiyr_m_command.log` 21:44:07): `QueueWorker: timed out` / `no connection possible` enquanto o MedLink reconecta (`DISCOVERING`, `OK+CONN`).

**Causa:** o `QueueWorker` marca o início da conexão uma vez, quando o trabalho começa. Com o MedLink o trabalho fica rodando muito tempo (`waiting for disconnect`, leituras de status a cada 5 s) e o MedLink reconecta entre comandos. No instante em que `isConnected()` fica falso durante a reconexão, o tempo desde o início já passou de 119 s: o worker trata como falha de conexão, chama `queue.clear()` e todo comando na fila recebe `success=false` "connection timed out". Um `SET PROFILE` na fila nesse momento gera o alarme `failed_update_basal_profile` (`CommandQueueImplementation`, ao trocar o perfil). TBR e SMB na fila também são cancelados.

**Correção:** `QueueWorker.connectionStartFor`: para MedLink, o tempo limite conta a partir do último momento em que a bomba estava conectada. Outras bombas sem mudança.

**Testes:** `implementation/.../queue/QueueWorkerConnectionTimeTest` (4). `QueueWorkerTest` continua passando.

**No log do teste de campo:** `QueueWorker ... timed out` só deve aparecer depois de 2 min seguidos sem conexão.

**Relacionado:** a troca de perfil sem resposta (perfil da bomba diferente) foi tratada na 4v.

## 4v. Troca de perfil aceita no AAPS; aviso quando o perfil da bomba é diferente (2026-11-10)

**Contexto:** o MedLink não consegue gravar o perfil basal na Veo (o `setNewBasalProfile` continua desligado; investigação futura). O perfil tem que ser trocado nos dois lugares: no AAPS e na bomba.

**Antes:** com o perfil da bomba diferente, o `MedLinkCommandSetProfile` não respondia nada. O `EffectiveProfileSwitch` nunca era criado (a troca ficava sem efeito no AAPS, sem aviso; `tbr_manual_bolus.llog` 08:24:38) e o `KeepAliveWorker` pedia a troca de novo a cada 5 min. O único aviso era um toast ao ler o perfil da bomba.

**Agora:**
- `MedLinkCommandSetProfile`: responde `success=true`, `enacted=false`; o AAPS cria o perfil efetivo e passa a usá-lo nos cálculos.
- `MedLinkMedtronicPumpPlugin.isThisProfileSet`: sempre "definido" (a bomba não pode ser gravada), então a fila e o KeepAlive não repetem a troca. Com o perfil da bomba lido (`ProfileOK`) compara a basal da bomba com o perfil do AAPS e mostra a notificação `medlink_profile_differs_from_pump` (`Notification.FAILED_UPDATE_PROFILE`, normal) quando são diferentes; tira a notificação quando ficam iguais. Só muda a notificação quando o estado muda.
- A notificação lista o que está diferente, por faixa de horário (`data/MedLinkProfileDiff`, horas seguidas com os mesmos valores viram uma faixa): `08:00–10:00: bomba 1,30 U/h, AAPS 1,35 U/h`; no máximo 8 linhas. Compara as 24 horas com a basal do AAPS arredondada ao passo da bomba. Muda a notificação quando as diferenças mudam.
- Textos em en, pt-BR, pl, ru.
- O `baseBasalRate` continua vindo da basal da bomba: o plano de TBR/microbolus usa a basal que a bomba entrega de fato.

**Testes:** `MedLinkProfileDiffTest` (4: igual, faixa, faixas separadas, rótulos). Fila + plugin sem teste automático (injeção). medtronic 147/147, implementation sem falhas novas.

**Diálogo de bolus:** com o MedLink o comando de bolus só entrega o bolus ao driver; a fila do AAPS fica vazia e o `QueueWorker` manda "aguardando desconexão" a cada segundo, que apagava as etapas do bolus (4p) no diálogo. O `BolusProgressDialog` ignora os status de fila parada (`WAITING_FOR_DISCONNECTION`, `DISCONNECTING`, `DISCONNECTED`). Outras bombas rodam o bolus dentro do comando, então esses status não chegam durante o bolus delas.

**No teste de campo:** trocar o perfil no AAPS para um diferente da bomba → sem alarme de falha, perfil novo ativo no AAPS, notificação "O perfil basal da bomba é diferente..." ; ajustar a bomba → a notificação some (até 5 min, pelo KeepAlive). No log: `MedLink: basal profile not written to the pump, accepted in AAPS only` uma vez por troca, e não a cada 5 min.

## 4w. Bateria do MedLink na aba Ações; bateria da bomba por tempo na aba Medtronic (2026-11-10)

- **Aba Ações:** a bateria do MedLink era escrita no rótulo da bateria do sensor, e só quando a fonte de glicose informava bateria do sensor (vazio com xDrip/Libre). Agora usa a linha própria do layout (`medlink_db_level`, `medlink_battery_level_label`, `medlink_battery_level`): `Nível: 38%`, amarelo a 25%, vermelho a 10% (mesmos limites da aba Medtronic), "n/d" sem leitura; a linha só aparece com bomba MedLink. O rótulo do sensor voltou ao original.
- **Aba Medtronic, bateria por idade:** o ícone é calculado pelo tempo desde a última troca de bateria (`TE PUMP_BATTERY_CHANGE`): 100% na troca, 0% na idade crítica da bateria das luzes de status (`OverviewBageCritical`, padrão 240 h). Amarelo a 25%, vermelho a 0%; o ícone mostra pelo menos uma barra enquanto houver carga (`data/MedLinkBatteryAge`). Sem evento de troca, continua o ícone antigo.

**Testes:** `MedLinkBatteryAgeTest` (5). medtronic 152/152. As telas não têm teste automático.

## 4x. Basal mínima pós-bolus; planos abaixo de 100% alternam início parado/ativo (2026-11-10) — NÃO TESTADO EM CAMPO

**Basal mínima pós-bolus** (`data/MedLinkPostBolusMinBasal`, aplicada no início de `setTempBasalAbsolute` e `setTempBasalPercent`):
- Configurações novas no MedLink: `Basal mínima pós-bolus (%)` (0 = desligado, padrão), `duração (min)` (padrão 180), `delta mínimo de 5 min (mg/dL)` (padrão 0).
- Vale só depois de um **bolus manual** (`BS.Type.NORMAL`; SMB e microbolus de TBR não contam), dentro da duração, com glicemia **no alvo ou acima** (`profile.getTargetMgdl()`) e delta dos últimos 5 min **maior** que o configurado (`GlucoseStatusProvider`).
- Uma TBR abaixo de `% × basal da bomba` (inclusive 0%) vira esse mínimo. A taxa ajustada é a que vai para o plano e para o registro da TBR (IOB = o que a bomba entrega).
- Log: `MedLink post-bolus minimum basal: x -> y U/h (motivo)`; quando não aplica, o motivo vai em debug.

**Início parado/ativo** (`data/MedLinkSuspendStartRule`, `NextStartStop(firstOffsetMinutes)`):
- Antes, todo plano abaixo de 100% começava com SUSPEND; como o loop refaz o plano a cada ~5 min, a bomba ficava parada mais do que a porcentagem pedida.
- Agora o início segue o **relógio**, não quantas vezes o plano foi calculado: o tempo é dividido em ciclos de uma suspensão + um intervalo ativo do próprio plano, alinhados ao relógio. Calculado na parte ativa do ciclo, o plano começa ativo pelos minutos que faltam dela; na parte parada, começa suspenso. Recalcular a qualquer momento dá a mesma resposta, e ao longo do tempo a parte ativa é a proporção da TBR (50% → metade do tempo, 75% → três quartos).
- Se a bomba estava parada e o plano começa ativo, ela é ligada na hora.
- Log: `MedLink temp basal plan: suspend X min / active Y min, starts active for N min | starts suspended`.

**Testes:** `MedLinkPostBolusMinBasalTest` (6), `MedLinkSuspendStartRuleTest` (5: metade, mesmo minuto = mesma resposta, 75%, 25%, limites). medtronic 163/163. O plugin (fila, BLE) não tem teste automático.

**No teste de campo:** ligar a basal mínima, dar um bolus manual com a glicemia subindo e conferir no log a TBR do loop sendo elevada; com uma TBR de 50%, o início deve seguir o horário (metade do ciclo `starts suspended`, metade `starts active for N min`), não a contagem de planos.

## 4y. Suspensão bloqueada pela bateria do MedLink ou do telefone, aviso vermelho na barra de status (2026-11-10)

**Antes:** o `stopPump` recusava a suspensão com a bateria do telefone ou da **bomba** a 5% ou menos (fixo) e abria um alarme ("bomba não pode ser suspensa"). A bateria do MedLink não contava. A "previsão" do telefone (`batteryDelta`) tinha o sinal invertido (positiva carregando) e o `lastBatteryLevel` nunca era atualizado.

**Agora** (`data/MedLinkSuspendBatteryRule`):
- Conta a bateria do **MedLink** e a do **telefone**; qualquer uma no mínimo configurado ou abaixo impede a suspensão (bateria da bomba não conta). Nível do telefone 0 = ainda não lido, não bloqueia.
- Configuração nova: `Bateria mínima para suspensão (%)`, padrão 5 (1 a 50).
- Em vez do alarme, notificação **vermelha** na barra de status (`Notification.MEDLINK_SUSPEND_LOW_BATTERY`, nível URGENT, botão DISPENSAR), no mesmo mecanismo da mensagem de identificação não definida (que é verde, INFO): `Suspensão não enviada: bateria do MedLink em 4% (mínimo 5%)...`. Depois de dispensada, só volta quando o nível ou a bateria mudam; some sozinha quando as baterias ficam acima do mínimo.
- `handleBatteryData`: com a bomba parada e uma das baterias baixa, a bomba é religada (`clearTempBasal`), com a mesma regra.
- A suspensão pedida pelo usuário continua bloqueada, como antes.
- A previsão com `batteryDelta` saiu da regra (estava invertida).

**Testes:** `MedLinkSuspendBatteryRuleTest` (5). medtronic 168/168. A notificação não tem teste automático.

## 4z. SMB "não iniciado" que nunca era reenviado nem falhava (2026-11-10) — NÃO TESTADO EM CAMPO

**Sintoma (`logs/smb_bolus_not_executed.log`):**
- 15:44:32 `BOLUS  0.4` escrito e confirmado pelo MedLink (`set bolus`, `bolus verification`), mas a bomba não aplicou: a resposta do BOLUS (15:44:55) mostra `pump normal state` e o bolus anterior (`last bolus: 0.1u 15:31`, igual ao baseline) → NOT_STARTED comprovado → `resend (attempt 2)` → `BOLUS resend requested but no held command, failing`.
- Depois, a cada `M` do watchdog (15:45:09, 15:46:45, 15:46:47...) o mesmo: NOT_STARTED → resend → "no held command". O bolus não é reenviado nem falha.

**Causa:**
- `resendHeldBolus` só reenviava um comando que estivesse em `onHoldCommandQueue`, e ele só fica lá quando a conexão cai antes da resposta. Na primeira vez (15:44:55) o comando do bolus ainda era o comando atual (a resposta dele estava sendo tratada); nas seguintes ele já tinha terminado e saído das filas. Nos dois casos o reenvio "falhava".
- E esse "falhar" chamava `onRejectedBeforeWrite`, que mantém um bolus já escrito em UNRESOLVED: o SMB ficava pendente para sempre, cada `M` do watchdog (60 s) pedia o reenvio de novo, e novos SMBs eram recusados ("mesmo tipo pendente").

**Correção:**
- `MedLinkBLE.resendHeldBolus`: reenvia o comando rastreado (`trackedBolusExecutor`) mesmo que ele não esteja em espera: `clearExecutedCommand` + `resetRetries` + início da fila de bolus. Se ele ainda é o comando atual, espera ele terminar (checa a cada 1 s, até 30 s; senão falha o bolus). Se ainda está numa fila, é zerado para ser escrito de novo e não é adicionado duas vezes. O limite de 5 tentativas do tracker continua valendo.
- Sem comando rastreado: `MedLinkBolusTracker.onResendImpossible` falha o bolus (o resultado vai para o loop, novos bolus liberados).
- Sem envio duplo: o reenvio só acontece enquanto `MedLinkBolusTracker.resendPending()` (o `M` permitiu e o bolus ainda não foi escrito de novo; escrever zera a permissão), e só uma espera por vez (um `M` do watchdog durante a espera não inicia outra).
- Log: `BOLUS resend: waiting for the bolus command to finish`, depois `BOLUS resend after M proved it was not started (held|finished command)` e um novo `writing <BOLUS`.

**Por que a primeira tentativa não foi aplicada:** o MedLink confirmou o comando, mas a bomba continuou em estado normal com o bolus anterior. É uma falha de comunicação MedLink → bomba; o reenvio (até 5 tentativas) é a resposta prevista, e era ele que não acontecia.

**Testes:** `MedLinkBolusTrackerTest` (+2: reenvio impossível falha e libera; reenvio pendente só até o bolus ser escrito de novo). rileylink 510/510. O reenvio no `MedLinkBLE` não tem teste automático (T10).

## 4aa. Tentativas de comando configuráveis; aviso de alarme na tela da bomba (2026-11-10) — NÃO TESTADO EM CAMPO

**Contexto:** o MedLink às vezes confirma um BOLUS, START ou STOP que a bomba não aplica (4z: `BOLUS  0.4` confirmado, bomba em estado normal com o bolus anterior). Normalmente é uma mensagem/alarme na tela da bomba.

**Antes:** as tentativas eram fixas em 5 (`MedLinkBolusTracker.MAX_ATTEMPTS` para bolus, `MedLinkStartRetryRule.MAX_FAILURES` para START e STOP) e o aviso de bomba bloqueada aparecia depois de 2 comandos confirmados sem efeito seguidos (`MedLinkPumpLockRule.LOCK_AFTER`).

**Agora:**
- Configuração nova `Tentativas de comando` (`MedLinkMedtronicIntPreferenceKey.CommandAttempts`, padrão 5, de 1 a 10), passada ao `MedLinkBLE` (`commandAttemptsProvider`). O mesmo número vale para:
  - tentativas de um bolus (tracker; também o "tentativa n de N" do diálogo de progresso);
  - novas tentativas de START e de STOP (e o texto do alarme quando desistem);
  - comandos confirmados sem efeito seguidos antes do aviso.
- O aviso (`medlink_pump_block_locked`, notificação urgente + alarme, como antes) agora diz quantos comandos falharam e que a bomba provavelmente mostra um alarme na tela: `Bomba não responde: 5 comandos seguidos de start, stop ou bolus foram confirmados pela MedLink, mas a bomba não os aplicou. A bomba provavelmente mostra um alarme na tela...`. Some quando um comando funciona.
- **Mudança de comportamento:** com o padrão 5, o aviso aparece depois de 5 comandos sem efeito (antes 2).
- O valor é lido a cada uso (mudar a configuração vale para o próximo comando).

**Testes:** `MedLinkPumpLockRuleTest` (+1), `MedLinkStartRetryRuleTest` (+1), `MedLinkBolusTrackerTest` (+1: tentativas seguem a configuração e ela é lida a cada bolus). rileylink 513/513, medtronic 168/168.

## 4ab. Explicações das configurações novas do MedLink (2026-11-10) — NÃO TESTADO EM CAMPO

As 5 configurações adicionadas (4x, 4y, 4aa) têm um resumo curto embaixo do título e uma explicação completa, com exemplo e padrão, no diálogo de edição (en, pt-BR, pl, ru): `Tentativas de comando`, `Bateria mínima para suspensão (%)`, `Basal mínima pós-bolus (%)`, `Basal mínima pós-bolus: duração (min)` e `Basal mínima pós-bolus: delta mínimo de 5 min (mg/dL)`. Textos sem formatação que contêm `%` usam `formatted="false"` (antes, `%%` apareceria literalmente).

## 4ac. Tentativas de comando também limitam as repetições no MedLinkBLE (2026-11-10) — NÃO TESTADO EM CAMPO

- A configuração `Tentativas de comando` (4aa) também é o limite de repetições de um comando no `MedLinkBLE` (antes `MAX_TRIES = 5` fixo): os quatro pontos que comparam `nrRetries` (`completedCommand`, a nova tentativa em `retryCommand`, `nextCommand` e a desconexão por repetições) usam `commandAttempts()`. Um comando que chega ao limite é abandonado como antes (bolus: `dropBolusOutOfTries`; START: nova tentativa com alarme, T13).
- As tentativas de **conexão** ao MedLink (`connectTries` em `medLinkConnect`) também usam `commandAttempts()` (antes 5 fixo): cada falha ao abrir a conexão (`connectGatt` sem GATT) tenta de novo até passar do limite, ou seja, a primeira tentativa + N repetições, como antes.
- Textos da configuração atualizados (resumo e explicação) em en, pt-BR, pl, ru.
- Com o valor 1, um comando não é repetido: uma falha de escrita já abandona o comando, e a conexão é repetida só uma vez.

**Testes:** sem teste novo (código do `MedLinkBLE`, T10). rileylink 513/513, medtronic 168/168.

## 4ad. Aba Medtronic: basal da bomba, do plano e da hora, em percentual (2026-11-10) — NÃO TESTADO EM CAMPO

- A linha "Basal temporária" mostrava `tempBasalAmount` (número sem unidade, com significados diferentes conforme quem o gravou). Agora mostra três percentuais em relação à basal base da hora (`baseBasalRate`): `Bomba 100% · plano 60% · nesta hora 150%`.
  - **Bomba:** o que a bomba faz agora: 0% parada, 100% rodando, `--` com estado desconhecido.
  - **Plano:** o que as suspensões/microbolus que faltam do plano entregam de agora até o fim do plano (`TempBasalMicrobolusOperations.endsAt`, gravado junto com `durationInMinutes`). Sem plano: `--`.
  - **Nesta hora:** o que foi entregue desde o início da hora do relógio: tempo com a bomba rodando x basal base + microbolus de TBR (`BS.Type.TBR` do banco), dividido pela basal base no mesmo tempo. Exemplo: 18:30, basal 1 U/h, entregue 0,75 U → 150%.
- O tempo rodando/parada vem de uma linha do tempo nova, em memória (`MedLinkBasalPercent.Timeline`, últimas 2 h), gravada a cada mudança de `pumpRunningState` (`setPumpRunning/Suspended/Unknow` do status, `setPumpRunningState` e `setSuspended` do plugin). Depois de reiniciar o app ela começa vazia: só o tempo com estado conhecido entra no cálculo (`--` até a primeira leitura). O estado desconhecido não entra.
- Cálculo em `pump/medtronic/.../data/MedLinkBasalPercent.kt` (puro); o plugin junta os dados em `basalPercents()`, a aba busca os microbolus de TBR da hora.
- Limitações: o estado é o lido pelo status/comando, não o horário exato da mudança na bomba; no início da hora (ex.: 18:01) um microbolus pesa muito no percentual.

**Testes:** `MedLinkBasalPercentTest` (12: exemplo do usuário 150%, metade parada 50%, microbolus antes da hora, depois de reiniciar, estado desconhecido, sem basal, plano de suspensões, plano começando parado, plano acima de 100%, sem plano, linha do tempo). medtronic 180/180, app compila.

## 4ae. Loop travado em "A bomba está ocupada" depois de uma TBR pequena (2026-11-11) — NÃO TESTADO EM CAMPO

- Log `logs/looplock.log` (23:16): plano anterior de suspensões ativo (`desiredRate=0.364`), o loop pediu 1,16 U/h com basal base 1,3. Isso dá ~3 min de suspensão, abaixo do intervalo mínimo: nenhum plano novo, `clearTempBasal()` esvaziou as operações e ligou a bomba. Em seguida `reactivateTime()` fazia `operations.first` na fila vazia → `NoSuchElementException`.
- A exceção saía de `MedLinkCommandBasalAbsolute.execute()`; o `QueueWorker` não chamava `queue.resetPerforming()` e terminava ("work end"). O comando ficava "performing" para sempre: todo loop (e o loop manual) esperava 2 min em `isEmptyQueue()` e parava em "A bomba está ocupada".
- Correções:
  - `TempBasalMicrobolusOperations.reactivateTime()` devolve `null` sem operações (`peekFirst`).
  - `setTempBasalAbsolute`: só estende a basal quando os dois horários de reativação existem; o `!!` do resultado de `scheduleSuspension` (nulo antes do primeiro start) virou um resultado `success=true, enacted=false`.
  - `QueueWorker`: `execute()` dentro de `try/catch/finally`; a exceção é registrada (`command X failed`) e `resetPerforming()` sempre roda. Vale para todas as bombas: uma exceção num comando não trava mais a fila.
- Limitação: o callback do comando que deu exceção não é chamado (o loop segue no próximo ciclo).

**Testes:** `TempBasalMicrobolusOperationsTest` (plano vazio / limpo devolve `null`). Sem teste do `QueueWorker` (worker Android). medtronic 182/182, implementation 201/203 (só `HardLimitsImplTest`), app compila.

## 4af. Cores da aba Medtronic: bateria por tempo, voltagem e ISIG (2026-11-11) — NÃO TESTADO EM CAMPO

- Bateria por tempo: a cor usava a idade do banco (`PUMP_BATTERY_CHANGE`) e o texto usava `medLinkPumpStatus.batteryAge`; sem evento no banco não havia cor. Agora texto, ícone e cor usam a mesma idade (a do plugin, ou a do banco quando o plugin não tem). Vermelho (0%) mostra a bateria vazia, amarelo (até 25%) a de um quarto; sem idade conhecida a cor volta ao branco.
- Bateria por voltagem: o texto ficava vazio (`buildString { String.format(...) }` descartava o resultado). Agora mostra `x.xx V`.
- ISIG: a cor de ISIG baixo (≤ 8 amarelo, ≤ 7 vermelho) era sobrescrita logo depois pela de ISIG alto (≥ 26 / ≥ 31). Agora é uma chamada só.

**Testes:** `MedLinkBatteryAgeTest` (ícone acompanha a cor de 0 a 100%). medtronic 182/182.

## 4ag. Merge com origin/master (OpenApsAIMI, AAPS 3.4.2.2) na branch `medlink-v3.4-dev` (2026-11-11) — NÃO TESTADO EM CAMPO

- Antes do merge: commit `225d4d3275` e tag `medlink-v3.3-2026.11.11` em `medlink-v3.3-dev`. Merge de `origin/master` (`2fd4d5f218`, 398 commits, versão `3.4.2.2-Master.AIMI.160426`) em `medlink-v3.4-dev`.
- Conflitos resolvidos mantendo os dois lados: tipos de bomba / capacidades / passos de dose (MedLink + Apex), `InterfaceIDs.PumpType` (gravado por nome, ordem não importa), `PumpTypeExtension`, strings (sem nomes duplicados).
- `Notification.MEDLINK_SUSPEND_LOW_BATTERY` passou de 96 para **98** (96 = `APEX_SERVICE_MSG`, 97 = `PUMP_IS_LOCKED` no master).
- `EventOverviewBolusProgress`: o master adicionou `wearStatus` como 4º parâmetro; o `waiting` do MedLink virou o 5º (as chamadas do MedLink já usam o nome `waiting =`).
- `LoopPlugin`: versão do master + as mudanças do MedLink reaplicadas:
  - SMB do MedLink enviado uma vez, antes da TBR (`smbBeforeTbrOnly`), nunca de novo no callback da TBR; nunca SMB de 0 U.
  - `applySMBRequest`: intervalo do SMB conta só do último SMB/bolus manual para MedLink (`getLastNonTBRBolusTime`); bomba MedLink suspensa (TBR emulada) ainda recebe o SMB; `smb <= 0` não enfileira nada.
  - **Mudança:** antes o SMB antes da TBR valia para `pump is PumpPluginBase` (todas as bombas: as outras bombas recebiam o SMB duas vezes, antes e depois da TBR). Agora vale só para MedLink; as outras bombas seguem o master.
- `PumpPluginAbstract` do master pede `PumpDriverConfiguration`, `decimalFormatter`, `dateUtil`: novo `pump/common/.../driver/MedLinkPumpDriverConfiguration.kt`. No plugin MedLink, `firstRun`, `isRefresh`, `hasTimeDateOrTimeZoneChanged`, `logPrefix` e `getTimeInFutureFromMinutes/getTimeInMs` agora vêm do `PumpPluginAbstract`; o mapa de refresh do MedLink virou `medLinkStatusRefreshMap` (tipo diferente do `statusRefreshMap` do master). `tempBasalLength` → `tempBasalDuration`.
- Medtronic sem MedLink (`MedtronicUIPostprocessor`, `MedtronicPumpStatus`): versão do master.
- `shared/tests/TestBaseWithProfile.kt`: o do master usa `ConcentrationHelper` (AAPS 3.5), que não existe no código do master; mantido o mock de `SharedPreferences` do master, sem `ConcentrationHelper`.
- Conferido que continuam no merge: `getLastNonTBRBolusTime` no `DetermineBasalAIMI2`, `pump !is MedLinkPumpPluginBase` nos hard limits do AIMI/AutoISF, `Constants.INTERVAL_BETWEEN_OPERATIONS`.

**Testes:** app compila. rileylink 513/513, medtronic 182/182, implementation 201/203 (só `HardLimitsImplTest`, como antes). `plugins:aps` não compila os testes **do próprio master** (`LoopPluginTest`, `BasalPlannerTest`, `CosineTrajectoryGateTest`, iguais ao master; usam APIs do AAPS 3.5): não corrigido.

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
| `bolus/MedLinkBolusStatusInterpreterTest` | regras de novo/velho/ambíguo, parcial, desconhecido |
| `bolus/MedLinkBolusTrackerTest` | estados, reenvio, alarmes, registro, executor descartado (4h), junção (4i), bolus de fora (4j), tentativas, falhas do `M` e sinais de trava (4k) |
| `bolus/MedLinkPumpLockRuleTest` | bomba travada (PUMP_LOCKED) depois de 2 comandos confirmados sem efeito (4k) |
| `bolus/MedLinkStopGateTest`, `bolus/MedLinkStartStopSkipRuleTest` | stop, skip |
| `bolus/MedLinkLoopBolusMergeTest` | junção TBR + SMB (T14), junção ao bolus rastreado (4i) |
| `bolus/MedLinkManualBolusRuleTest` | recusa só do mesmo tipo (4i), valor editado no diálogo (T16) |
| `ble/command/BleStopStartCommandTest`, `ble/command/BleBolusCommandsTest` | tratamento das respostas `A`/`M` e do bolus |
| `bolus/MedLinkBolusCommandAnswerTest`, `bolus/MedLinkPumpClockTest` | resposta do BOLUS (falha, em entrega, ano 00), normalização do ano (4m) |
| `ble/NoSleepOnBolusPathTest` | sem sleep nos caminhos de bolus/stop |
| `ble/CommandExecutorPostTest` | bug do `M` → "invalid command" |
| `ble/MedLinkPostGuardTest` | post velho na thread BLE não escreve (4r) |

Fixtures sintéticas: `pump/rileylink/src/test/resources/medlink/` (veja o `README.md` de lá).
Cenários adicionados em 2026-11-10 (lacunas das seções 4q, 4s, 4t):
- `MedLinkLoopBolusMergeTest` (+5): manual + TBR inteiro, manual + TBR + SMB, manual juntado parcial (o manual é preenchido primeiro, a parte TBR recebe o resto), nada entregue, `temporaryId` distinto por parte com a primeira na hora do bolus da bomba.
- `MedLinkBolusTrackerTest` (+3): `M` atrasado depois que a resposta do BOLUS registrou o bolus não registra de novo; resposta "bolusing" mantém o bolus a verificar (o `M` do fim fica); bolus só fica "a verificar" depois de escrito.
- `MedLinkCommandSMBBolusTest` (+3): limite exato da janela, pedido com hora no futuro, janela segue o intervalo configurado.

Última execução (2026-11-10): rileylink 508/508, implementation 198/200 (só o `HardLimitsImplTest` conhecido).
Antes (2026-11-09, 4m): rileylink 488/488, medtronic 143/143, `:app:assembleFullDebug` OK. Antes (4k): rileylink 484/484, `:app:assembleFullDebug` OK. Antes (4j): rileylink 482/482, `:app:assembleFullDebug` OK. Antes (4i): rileylink 475/475, `:app:assembleFullDebug` OK. Antes (4h): rileylink 468/468. Antes (T16): rileylink 465/465, `:app:assembleFullDebug` OK. Antes (T15): rileylink 461/461. Antes: rileylink 448/448, `:app:compileFullDebugKotlin` OK. Anterior: rileylink 439/439, medtronic sem falhas, `:app:compileFullDebugKotlin` OK.
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
- ~~`BleStopCommand` procurava `pump bolusing state`~~ corrigido em 2026-11-08: novo `bolus/MedLinkPumpStateAnswer.isBolusing()` (texto real `pump is bolusing st`, cobre o pedaço BLE `pump is bolusing sta`), usado por `BleStopCommand` e `BleStartCommand`. Antes, a resposta "bolusing" do `A` não era reconhecida (caía no tratamento genérico) e a proteção do stop automático dependia só da leitura do `M` feita antes (MedLinkStopGate). Agora o `A` também retém o stop (T1). Testes e fixture `a_bolusing.txt` usam o texto real.
- Um BOLUS de microbolus que falha volta a `isCommandIssued = false`, mas o refresh pode já tê-lo removido (microbolus perdido: entrega a menos, não a mais).
- `MedLinkCommandSMBBolus`: regra do intervalo do SMB revista em 2026-11-10 (seção 4q): conta só o último SMB ou bolus manual.
- T16: teste de campo do diálogo com o app em segundo plano (bolus pelo relógio/NS). A abertura da activity depende da mesma permissão usada pelos alarmes.
- T14: teste de campo da junção TBR + SMB (conferir no log `BOLUS merged` e as duas entradas no banco/NS).
- 4k: descobrir por que o `M` deu `UNKNOWN` para o microbolus de 0,1 U em `logs/invalid_smb.log` (precisa do log de antes do envio, com o baseline). A bomba mostra o ano como `00` (`09-11-00`).
- 4k: confirmar no campo que "pump normal state" depois do STOP escrito é mesmo STOP recusado (nenhum log analisado tem esse caso).
- 4k: o callback do STOP/START (operações de TBR) ainda não recebe a falha; o reenvio é só no nível BLE.
- Teste de campo com a bomba antes de usar.

## 8. O que conferir no log durante o teste de campo

- `BOLUS merged SMBBolus 0.4U into queued TBRBolus: ... -> 0.5U` e depois `BOLUS part TBR ...` / `BOLUS part SMB ...` — junção T14; deve haver um TBR e um SMB no banco, com 1 s de diferença.
- `BOLUS manual merged: NORMAL xU, TBR yU, SMB zU -> tU (smbConsent=...)` — T16. `BOLUS manual xU dropped: waited more than 5 min` — T15.
- `manual bolus xU: SMB yU pending, asking the user` / `user answered ok=...` / `deliverBolus refused: SMB ... while a manual bolus is pending` — T16.
- `BOLUS[id] state X->Y` — todo bolus deve terminar em DELIVERED, PARTIAL ou FAILED (com alarme).
- `automatic stop: reading M first` / `automatic stop deferred until ...` — stop automático esperando o bolus.
- `waiting for the answer of BolusStatus, not posting again` — o bug do `M` sendo evitado. Não deve mais aparecer "invalid command" logo depois de um `M`.
- `M answer lost, reading M again` — sessão encerrada antes da resposta do `M`.
- Depois do fim de cada SUSPEND de basal temporária, deve haver um `writing <START` perto do horário do REACTIVATE (bug 4c).
- `START refused: pump still suspended` / `START failed (...), failure N` / `START retry N` — bug 4. Depois do fim de uma basal 0% deve aparecer "pump normal state" (ou o reenvio).
