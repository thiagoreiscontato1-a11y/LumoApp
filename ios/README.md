# Lumo iOS 0.2.0

Versão para iPhone/iPad do fluxo do Lumo Android:
**Evento → Captura → Edição automática → Curadoria → Galeria/Revisão → Entrega Fotto.**

## O que já funciona no código

| Área | iOS 0.2.0 | Origem no Android |
| --- | --- | --- |
| Eventos locais | Criar/escolher evento; pastas `Eventos/<nome>_<id>/Originais`, `Editadas`, `Sob Revisao` | 0.15.28 |
| Câmera USB | ImageCaptureCore + comandos PTP Canon (modo remoto, eventos, leitura do cartão a cada 0,8 s, retroativo em segundo plano, retry de `DeviceBusy`) | `Ptp.java`, `CaptureService.java` |
| Câmera USB (alternativa) | Se o iOS recusar PTP direto, usa o catálogo do ImageCaptureCore e baixa as fotos novas | — |
| Câmera Wi-Fi | PTP/IP (porta 15740) com handshake e canal de eventos | `WifiPtp.java`, `PtpIpWire.java` |
| Importação | App Fotos (vários) e app Arquivos / leitor de cartão USB (percorre subpastas, só JPEG) | 0.15.16 |
| Edição | Automático (com intensidade), preset Lightroom `.xmp` e editor manual — mesmos algoritmos do Android, pixel a pixel | `PhotoEditor.java` |
| Curadoria | Nitidez, exposição, contraste, clipping, enquadramento, duplicatas exatas (SHA-256) e visuais (dHash); nota 1–10 | `Curator.java`, `SmartCurator.java` (sem a parte semântica) |
| Entrega Fotto | Login pela página oficial, lista completa de eventos com busca, ID manual, envio direto (criar mídia → PUT assinado), confirmação em lote, encerrar envio | `FottoApi.java`, `FottoSync.java` |
| Arquivos | As pastas aparecem no app Arquivos em **No meu iPhone › Lumo** | — |

## Compilar e instalar

### Com um Mac (Xcode 16 ou mais novo)
1. Abra `ios/Lumo.xcodeproj`.
2. Target **Lumo** › *Signing & Capabilities* › escolha seu *Team* (o Personal Team gratuito funciona).
   Se o identificador `com.lumo.app.ios` estiver indisponível, troque por outro (ex.: `com.seunome.lumo`).
3. Conecte o iPhone, selecione-o no topo do Xcode e pressione **Run**.

A configuração Debug já é compilada com otimização (`-O`), porque a edição de pixels fica muito lenta sem ela.

### Sem Mac
O GitHub Actions (`.github/workflows/ios.yml`) compila o app a cada alteração em `ios/` e publica o artefato
**Lumo-iOS-unsigned** (`Lumo-iOS-unsigned.ipa`). Esse IPA não tem assinatura: instale com uma ferramenta que
assine com o seu Apple ID, como Sideloadly ou AltStore. Com Apple ID gratuito a instalação vale por 7 dias.

### Testes
```bash
swift test --package-path ios/LumoCore
```
O núcleo (`ios/LumoCore`) não depende de UIKit e tem testes para edição, XMP, curadoria, hashes, PTP, PTP/IP,
API Fotto (incluindo paginação) e armazenamento por evento. O mesmo código é compilado dentro do app.

## Usar com a câmera

1. Crie o evento no topo.
2. **USB:** ligue a Canon ao iPhone (USB-C, ou adaptador Lightning para câmera USB 3) e toque em
   **Conectar câmera USB**. Na primeira vez o iOS pede permissão de acesso.
3. **Wi-Fi:** na câmera, ative o Wi-Fi em modo de conexão com computador (EOS Utility), conecte o iPhone à rede
   da câmera, informe o IP e toque em **Conectar**. Autorize LUMO no visor da câmera.
4. Fotografe. As fotos chegam, são editadas e vão para **Galeria** (aprovadas) ou **Revisão**.
5. Em **Entrega**, conecte o Fotto, escolha o evento de destino e ative o envio automático.

## Limitações conhecidas (precisam de teste no aparelho)

- **Nada foi testado em iPhone físico ainda.** O CI comprova que compila e que o núcleo passa nos testes; conexão
  com a câmera, permissões e interface precisam ser validadas com a Canon R8/SL2.
- O envio de comandos PTP Canon pelo ImageCaptureCore (`requestSendPTPCommand`) é suportado pela Apple, mas o
  comportamento varia por modelo. Se falhar, o app entra no modo catálogo, que depende de a câmera publicar as
  novas fotos no USB.
- O iOS suspende apps em segundo plano: mantenha o Lumo aberto durante o evento (a tela não apaga enquanto a câmera
  estiver conectada). O envio ao Fotto pede alguns minutos extras ao ir para segundo plano.
- RAW (CR2/CR3) ainda não é editado; apenas JPEG. Use JPEG ou RAW+JPEG na câmera.
- Ainda não portados: curadoria semântica (rostos/olhos), modo demonstração/licença, tour guiado e Fotto Web
  (fallback).
- A API do Fotto não é documentada; o login e a paginação seguem o que foi observado no app Android.
