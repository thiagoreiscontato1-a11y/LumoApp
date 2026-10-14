# Lumo iOS 0.4.0

Versão para iPhone/iPad do fluxo do Lumo Android:
**Evento → Captura → Edição automática → Curadoria → Galeria/Revisão → Entrega Fotto.**

**Downloads da main:** release [`lumo-latest`](https://github.com/thiagoreiscontato1-a11y/LumoApp/releases/tag/lumo-latest)
(`Lumo-iOS-unsigned.ipa` e `Lumo.apk`), atualizado a cada merge.

## Interface (0.3.0+)

Tela única **ao vivo**, sempre escura: última foto em destaque com estado e nota, tira das recentes, três números
(Fotos, Revisão, Fotto) e um botão principal. Eventos, câmera, revisão, galeria, entrega e ajustes abrem como painéis.
Funciona deitado (iPhone no suporte da câmera), vibra quando chega foto e mostra faixa vermelha se a câmera cair.

## O que já funciona no código

| Área | iOS | Origem no Android |
| --- | --- | --- |
| Eventos locais | Pastas `Eventos/<nome>_<id>/Originais`, `Editadas`, `Sob Revisao`, `RAW` | 0.15.28 |
| Câmera USB | ImageCaptureCore + comandos PTP Canon (modo remoto, leitura a cada 0,8 s, retroativo, `DeviceBusy`, tempo limite) | `Ptp.java`, `CaptureService.java` |
| Câmera USB (alternativa) | Catálogo do ImageCaptureCore quando o iOS recusa PTP direto | — |
| Câmera Wi-Fi | PTP/IP (porta 15740) | `WifiPtp.java`, `PtpIpWire.java` |
| Importação | App Fotos e Arquivos/leitor de cartão (subpastas); RAW revelado pelo iOS quando não há JPEG par | 0.15.16, `RawPreview.java` |
| Edição | Automático, preset `.xmp` (do evento ou por foto) e editor com **prévia ao vivo** e antes/depois; copiar/colar ajustes | `PhotoEditor.java`, `ManualEditorActivity.java` |
| Curadoria | Técnica + duplicatas + **pessoas (Vision)**: rosto cortado/encoberto, olhos fechados, rosto virado | `Curator.java`, `SmartCurator.java`, `SemanticCurator.java` |
| Galeria | Filtros (todas, favoritas, aprovadas, revisão, não enviadas, enviadas), **favoritos**, seleção múltipla, visualizador com **deslizar e zoom** | (novo, também no Android 0.16.0) |
| Modo evento | Tela sempre ligada, alarme de desconexão que toca no silencioso, perfis de trabalho | `EventAlert`, `DisconnectAlert`, `WorkProfiles` |
| Saúde | Câmera, edição, falhas, revisão, Fotto, última foto, bateria, armazenamento | painel Saúde |
| Entrega Fotto | Login oficial, lista completa com busca, ID manual, envio direto, confirmação em lote | `FottoApi.java`, `FottoSync.java` |
| Outros | Manifesto da galeria online, tour inicial, link do painel Fotto | — |

Não portado: licença/modo demonstração de distribuição do Android e o Fotto Web embutido (o envio direto substitui).

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

## Se a câmera não conectar (ex.: SL2)

- Desligue Wi-Fi e Bluetooth da câmera: a Canon bloqueia o USB enquanto o Wi-Fi está ativo.
- Desative o desligamento automático da câmera.
- Use um cabo de dados (não só de carga) e, se possível, um cartão com menos fotos no primeiro teste.
- Na aba Captura, toque em **Copiar registro** e envie o texto: ele mostra o ID USB, os recursos que o iOS
  expõe e o tempo de cada comando PTP.

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
