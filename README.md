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
