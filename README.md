# LUMO 0.15.21 — Busca de eventos antigos do Fotto

Corrige o carregamento de eventos na aba Entrega, mantendo os recursos da 0.15.20.
O Lumo agora tenta buscar todas as páginas da API Fotto (em vez de somente a primeira), oferece busca por nome/ID, conserva o evento salvo e permite verificar o ID de um evento diretamente no painel do fotógrafo.

**Para usar:** Entrega → ⋯ → **Atualizar lista completa** → buscar e selecionar o evento. Se não aparecer: **Informar ID do evento** (ID ou link do painel contendo `/galleries/ID`). Verifique o nome e confirme antes do envio.

**Limitação:** não foi possível autenticar uma conta Fotto para comprovar a paginação real nem a consulta individual. A interface avisa quando não consegue confirmar que a lista terminou.

**Compilar:** publique a pasta inteira em um repositório GitHub, abra Actions → `Compilar Lumo 0.15.21 Fotto todos os eventos` → Run workflow e baixe o artefato com `Lumo.apk`. Também é possível rodar `python3 build.py` com Android SDK 35 / Build Tools 35.0.0 e Java 17.

- `versionCode`: 47
- `versionName`: `0.15.21-fotto-all-events`

---

# LUMO 0.15.16 — Importação de cartão USB

Baseado no projeto 0.15.6, anterior às alterações experimentais para Sony. Mantém o fluxo Canon R8/SL2, edição, curadoria e entrega Fotto.

## Importar fotos de um cartão

Conecte um leitor de cartão USB/OTG ao Android e toque em **Importar fotos do cartão USB**. Escolha a pasta do cartão no seletor do Android; o LUMO percorre as subpastas e coloca os JPEGs na mesma fila da câmera, preservando originais e usando as configurações atuais de edição, predefinição, curadoria e Fotto. A importação identifica duplicatas por origem e conteúdo.

O importador processa `.jpg` e `.jpeg`. Arquivos RAW, como CR2/CR3, são detectados e ignorados porque esta versão do editor não os decodifica. Se o Android não permitir selecionar a raiz do volume, selecione `DCIM`.

- `versionCode`: 42
- `versionName`: `0.15.16-usb-card-import`

---

# LUMO 0.15.6 — Watchdog Fotto + topo em chips

Atualização sobre a 0.15.5 com duas melhorias principais:

- **Watchdog do Fotto**: se o envio automático parar com fotos pendentes/erro e não houver progresso, o Lumo detecta a fila parada e tenta retomá-la sozinho. Erros fatais de sessão expirada ou arquivo inexistente não entram em loop. O intervalo aumenta progressivamente quando uma falha persiste.
- **Topo em mini-cards/chips**: bateria, espaço, pendentes de processamento, processadas e enviadas ficam em cartões compactos e responsivos; no celular em pé aparecem em duas linhas e em telas largas em uma linha.

`ENVIADAS` representa arquivos já aceitos pelo armazenamento do Fotto; o painel Entrega continua mostrando também quantas foram confirmadas/processadas no evento.

- `versionCode`: 41
- `versionName`: `0.15.6-fotto-watchdog-chips`

---

# LUMO 0.15.5 — Fotto fila longa + correção de build

Aplicativo Android para captura vinculada Canon, edição automática/manual, curadoria técnica e entrega ao Fotto.

## Novidade desta versão

O caminho principal de entrega não depende mais do monitoramento de pasta do Fotto.

**Captura → edição → Editadas → criar mídia no Fotto → PUT direto no S3 → confirmar `processed=true`.**

O Fotto Web continua disponível como fallback e para conferência do evento.

## Entrega Fotto

Na aba **Entrega**:

- conecte/reconecte a conta Fotto pelo menu `⋯`;
- escolha o evento de destino;
- ative **Envio direto automático**;
- use **Enviar pendentes agora** para processar fotos já aprovadas;
- use **Abrir Fotto Web · fallback** apenas quando precisar conferir a página ou usar o método antigo.

A fila é persistente e evita reenviar fotos já confirmadas para o mesmo evento.

## Fluxo principal

**Captura → Galeria → Revisão → Entrega**

Fotos reprovadas pela curadoria permanecem em **Sob Revisão** e não entram na entrega até serem aprovadas ou corrigidas.

## Compilação

O projeto usa Java + Android SDK 35, sem Gradle.

O GitHub Actions em `.github/workflows/build-apk.yml` instala API 35 / Build Tools 35.0.0, executa os testes e roda:

```bash
python3 build.py
```

Saída: **Lumo.apk**

- `versionCode`: 40
- `versionName`: `0.15.5-fotto-long-queue-build-fix`
- minSdk: 29
- targetSdk: 35

Veja `IMPLEMENTACAO-0.15.0-FOTTO-DIRETO.txt` para o fluxo e o roteiro do primeiro teste.


## 0.15.1

- botão **Encerrar envio** na aba Entrega;
- encerramento seguro: termina somente a foto que já estiver em upload e não inicia a próxima;
- conexão Canon alterada para ativar a captura antes da recuperação retroativa;
- retroativo passa a ser recuperado gradualmente, uma foto por ciclo, sem bloquear fotos novas;
- tolerância ampliada a respostas Canon `DeviceBusy` durante a ativação/event polling, visando a EOS R8.


## 0.15.2

- handshake USB/PTP da Canon R8 mais resiliente;
- `DeviceBusy (0x2019)` recebe retry progressivo durante SetRemoteMode / SetEventMode / GetEvent;
- a interface passa para **CONECTADA** assim que a sessão PTP abre, antes da varredura do cartão;
- fases de diagnóstico mais claras (`Abrindo sessão PTP`, `ativando captura Canon`, `lendo índice do cartão`);
- tolerância a timeouts USB transitórios antes de considerar a conexão quebrada;
- mantém o botão **Encerrar envio** e o upload direto validado no Fotto.



## 0.15.5 — Correção de build

- Corrigidas quebras de linha literais que deixavam strings Java abertas no cabeçalho do modo retrato.
- Mantida integralmente a fila longa do Fotto da 0.15.4.
- Adicionado teste `java_source_contract.py` ao GitHub Actions para detectar string Java atravessando linha antes da compilação.

## 0.15.4 — Fila longa do Fotto

- remove o limite interno de 40 uploads por execução;
- a fila continua drenando enquanto houver fotos novas;
- elimina a corrida entre o fim da fila e a chegada de uma nova foto;
- uma foto cujo PUT ao S3 retornou sucesso nunca é reenviada só porque a confirmação demorou;
- confirmação passou a ser em lote, reduzindo centenas de GETs para uma única leitura periódica;
- aplica revalidação da galeria antes da confirmação, seguindo o fluxo observado no HAR do Fotto;
- retries suaves para HTTP 429/502/503/504;
- retomada por JobScheduler também reativa a fila Fotto;
- painel separa enviadas/aceitas de confirmadas.


## Demonstração 0.15.27 e tour

- Build completo: `ANDROID_SDK_ROOT=/caminho/sdk python3 build.py`.
- Build de avaliação: `ANDROID_SDK_ROOT=/caminho/sdk LUMO_DEMO=1 python3 build.py`, saída `Lumo-Demo.apk`.
- O APK demo usa pacote `com.hisho.tether.demo` e nome Lumo Demo, podendo coexistir com o Lumo completo.
- O código de distribuição é entregue ao proprietário separadamente; somente seu SHA-256 fica no código. O mesmo código libera cada instalação por 15 dias a partir da primeira ativação. Reintroduzir o código não renova uma instalação expirada.
- Validação offline: prazo persistido, relógio monotônico na mesma inicialização e maior horário observado. NÃO impede reinício do teste apagando dados/reinstalando, alteração do APK ou manipulação sofisticada do relógio. Não é licença por pessoa nem código de uso único. Para isso, integrar um servidor de licenças.
- Ao expirar, impede novo trabalho de captura/edição/envio. Uma operação já iniciada pode terminar. Não apaga fotos.
- Tour inicial de 17 etapas, repetível pelo botão Tour do Lumo, e ajuda contextual de 5 etapas no editor. O tour não executa importações, exclusões nem uploads.
- Teste da licença: `python3 tests/demo_license_test.py` (Java 17).
- Compilação e assinatura verificadas; conexão física e UI precisam de teste no Android.
