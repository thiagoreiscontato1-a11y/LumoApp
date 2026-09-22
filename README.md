# LUMO 0.15.1 — R8 + Encerrar envio

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

- `versionCode`: 37
- `versionName`: `0.15.1-r8-stop-envio`
- minSdk: 29
- targetSdk: 35

Veja `IMPLEMENTACAO-0.15.0-FOTTO-DIRETO.txt` para o fluxo e o roteiro do primeiro teste.


## 0.15.1

- botão **Encerrar envio** na aba Entrega;
- encerramento seguro: termina somente a foto que já estiver em upload e não inicia a próxima;
- conexão Canon alterada para ativar a captura antes da recuperação retroativa;
- retroativo passa a ser recuperado gradualmente, uma foto por ciclo, sem bloquear fotos novas;
- tolerância ampliada a respostas Canon `DeviceBusy` durante a ativação/event polling, visando a EOS R8.
