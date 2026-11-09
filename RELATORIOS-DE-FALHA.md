# Relatórios de falha do Lumo Android

A partir da 0.19.0, quando o Lumo fecha por erro ou trava (ANR), ele guarda um relatório técnico. Na próxima abertura,
envia o relatório ao [Sentry](https://sentry.io), onde você vê a lista de erros, quantos aparelhos cada um atingiu e o
ponto exato do código, e recebe aviso por e-mail.

O app não usa o SDK do Sentry, porque o projeto compila sem Gradle. A classe `CrashReporter.java` monta o evento e o envia
pelo endpoint HTTP de envelopes do Sentry. O comportamento está coberto por `tests/crash_report_test.py`.

## O que é capturado

| Tipo | Como | Quando chega |
|---|---|---|
| Fechamento por erro em Java | `Thread.setDefaultUncaughtExceptionHandler`, gravado em disco antes de o processo morrer | na próxima abertura |
| Travamento (ANR) | `ApplicationExitInfo`, com a pilha da thread principal | na próxima abertura (Android 11+) |
| Falha nativa | `ApplicationExitInfo` | na próxima abertura (Android 11+) |

Cada relatório leva:
- versão e build do Lumo, e se o APK é de teste, produção ou demo;
- modelo do aparelho, versão do Android, memória e espaço livre;
- a pilha de chamadas;
- os últimos 30 passos do app (telas abertas e o registro da captura, como "Abrindo sessão PTP" ou "Editada salva").

Há também um ID aleatório por instalação, para contar quantos aparelhos foram afetados.

**Nunca entram** fotos, a conta ou a senha do Fotto, nem tokens. Nomes de eventos nos caminhos aparecem como
`Eventos/<evento>`, e e-mails como `<email>`.

Sem internet, os relatórios esperam na fila: até 20, por até 30 dias.

## Ligar o envio (cerca de 5 minutos)

1. Crie uma conta em [sentry.io](https://sentry.io). O plano gratuito (Developer) recebe alguns milhares de erros por mês,
   o que basta para a fase de testes. Confira os limites atuais no site.
2. **Create Project** → plataforma **Android** → nome `lumo-android`.
3. Copie o **DSN**, que fica em *Settings → Projects → lumo-android → Client Keys (DSN)* e tem a forma
   `https://abc123@o12345.ingest.us.sentry.io/678`.
4. No GitHub: **Settings → Secrets and variables → Actions → New repository secret** → nome `SENTRY_DSN`, valor: o DSN.
5. Rode **Actions → Lumo Android → Run workflow**, ou faça um merge na `main`, e instale o APK novo.
6. No app: **Menu → Ajuda → Relatórios de falha → Enviar relatório de teste**. O evento "Teste do relatório de falhas do
   Lumo" aparece em *Issues* no Sentry, com nível *info*.

No Sentry, use o filtro **Environment**:
- `teste`: APK com a chave de teste;
- `producao`: chave de produção (veja `ASSINATURA.md`);
- `demo`: APK Demo.

## Sem Sentry

Sem `SENTRY_DSN`, nada sai do aparelho. Em **Menu → Ajuda → Relatórios de falha → Compartilhar último relatório**, o
testador manda o texto do último erro por WhatsApp ou e-mail.

## Privacidade

O testador ou cliente pode desligar o envio em **Menu → Ajuda → Relatórios de falha → Desligar envio automático**. Com o
envio desligado, os relatórios ficam só no aparelho. Mencione o envio de relatórios técnicos na política de privacidade
e no formulário *Data safety* da Play Store: dados de diagnóstico, crash logs, sem vínculo com a identidade.

## iPhone

Ainda não há relatório de falhas no iOS. Quando o app for distribuído pelo TestFlight, os fechamentos aparecem no Xcode
Organizer e no App Store Connect, sem precisar de código.
