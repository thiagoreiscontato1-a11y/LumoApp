## Lumo 0.9.6 — Fotto Bearer fix

A API do Fotto agora usa o mesmo cabeçalho do app de referência: `Authorization: Bearer <token>` + `App-Code: fotto`.

# LUMO Tether Android 0.9 — identidade visual LUMO

Esta versão preserva o fluxo técnico da 0.8.1 (captura, edição, galeria, pasta de exportação e Fotto) e reorganiza a interface na identidade LUMO: Capture, Gallery, Edit e Deliver.


APK nativo Java, Android 10+, para testar com Canon R8 e SL2. O site anterior foi validado nessas câmeras; esta implementação Android ainda precisa de teste físico. Inclui Wi-Fi experimental e, na versão 0.8, integração experimental com o Fotto.

## Uso
1. Instale o APK e autorize a notificação do serviço.
2. Feche o LUMO no Chrome e outros aplicativos que usem a câmera.
3. Escolha o XMP, ou use Vibrant, e a resolução. Os ajustes ficam fixos durante a sessão; para trocar, pare e aguarde a fila terminar.
4. Conecte um cabo USB de dados, deixe a câmera em JPEG ou RAW+JPEG e toque em Conectar câmera e iniciar. Autorize o acesso USB.
5. Aguarde “Pronto. Fotografe na câmera.”. Fotografe normalmente. Apenas novos JPEGs são recebidos.
6. Veja as fotos em Pictures/LUMOTether/Originais e Pictures/LUMOTether/Editadas, pelo gerenciador de arquivos ou galeria.
7. “Parar captura e concluir fila” encerra a captura após a foto em andamento e termina a edição. “Retomar fila de edição” reprocessa tarefas interrompidas ou com erro, sem precisar da câmera.

## Funcionamento
Uma thread serializa as operações PTP/USB e salva o JPEG original. Outra, de prioridade baixa, lê os originais da fila SQLite, aplica a correção automática e o preset e salva a cópia editada. Nenhuma operação USB é disparada pela thread de edição. São processadas somente uma transferência e uma edição de cada vez, reduzindo uso de memória.

O intervalo de consulta é alvo de 800 ms, incluindo o tempo do ciclo; não significa uma foto pronta a cada 800 ms. A duração depende do tamanho da imagem, cartão, USB, armazenamento e CPU. Os originais são confirmados via MediaStore antes da edição. Os registros e URIs pendentes são persistidos para recuperação. O original interno é removido só após a cópia editada ser publicada; o original público é preservado.

O serviço visível mantém uma notificação e wake lock. Continua sem depender da Activity estar aberta. O Android/fabricante pode interromper o processo. Android 15+ limita o tipo mediaProcessing a seis horas em segundo plano por janela de 24 h; a fila pode ser retomada abrindo o app. Forçar parada ou desinstalar não é uma forma de pausa: a desinstalação remove a fila privada. A integração Fotto 0.8 usa INTERNET somente quando ativada. Captura e edição continuam locais; o upload roda em fila separada.

## Edição
Editor nativo baseado nos algoritmos do site. XMP aproximado: temperatura/tinta incrementais, exposição, contraste, sombras, realces, brancos, pretos, clareza, nitidez, saturação, vibrância, HSL e vinheta. Não reproduz perfis Adobe, curvas, máscaras, redução de ruído nem toda a renderização Lightroom. XMPs com apenas ajustes incompatíveis são rejeitados; outros parâmetros incompatíveis são ignorados com aviso geral na interface. Default: Vibrant + correção automática, lado maior 2560 px. Original mantém resolução e metadados; cópia editada tem orientação aplicada e JPEG qualidade 95, sem EXIF original. Arquivos CR2/CR3 não são editados.

## Validação realizada
- Compilação Java e DEX com Android SDK 35.
- Empacotamento e verificação criptográfica da assinatura APK.
- Testes JVM: listas/strings PTP, rejeição de lista truncada, conservação de preto/branco/cinza, correção moderada de foto escura.
- Teste SQLite: retomada de tarefas de edição e gravação original interrompidas; tarefas concluídas permanecem concluídas.
- Não testado em emulador nem câmera física: pareamento USB, renderização da interface, gravação MediaStore e continuidade sob políticas de energia do aparelho precisam de teste no dispositivo.

## Compilar
Instale JDK 17 e Android SDK (platforms;android-35, build-tools;35.0.0). Execute:

    ANDROID_SDK_ROOT=/caminho/sdk python3 build.py

A chave incluída é uma chave de teste (senha android), para permitir atualizar esta instalação experimental. Para distribuição de produção, use uma chave privada própria fora do código. O script não instala nada no celular.


## Interface 0.2
- Navegação Captura, Edição e Arquivos; adaptação para tablets.
- Prévia da última cópia editada, decodificada em executor independente e resolução reduzida. Toque para abrir na galeria.
- Contadores da sessão, edições pendentes, lista das últimas 12 cópias e diagnóstico copiável.
- Preset com nome do arquivo; preferências de edição persistentes; ajustes bloqueados durante a sessão.
- Ícone próprio e versão 2 para atualização sobre a 0.1 com a mesma chave de teste.
- Protocolo USB e filas de processamento preservados. Testes de núcleo e recuperação, compilação e assinatura verificados; interface ainda requer validação no aparelho.

## Galeria 0.3
- Prévia grande e faixa horizontal de miniaturas das últimas 60 edições concluídas.
- Navegação anterior/próxima; seleção manual fixa a revisão enquanto novas fotos são editadas.
- Botão Acompanhar novas volta à última edição automaticamente.
- Seleção e acompanhamento preservados na rotação da tela.
- Miniaturas reduzidas, cache de 8 MB e executor separado da prévia; filas USB/edição inalteradas.
- Teste GalleryTest cobre novas chegadas, revisão fixa, limites da navegação e janela móvel. Compilação e assinatura verificadas; teste visual no aparelho pendente.

## Conexão 0.4
- Estado confirmado somente após sessão PTP, inventário e comandos Canon responderem.
- Até três tentativas de abertura com liberação USB entre tentativas; nenhuma repetição automática de download.
- Remoção USB detectada no serviço; erro persiste após concluir a fila.
- Estados de câmera ocupada e resposta atrasada separados; encerramento USB com timeout reduzido e liberação garantida.
- ConnectionTest, CoreTest e recuperação SQLite passaram. Hardware precisa de validação.
- Referência Android USB: https://developer.android.com/develop/connectivity/usb/host

## Wi-Fi experimental e alerta 0.5
- Opção USB ou Wi-Fi na conexão. PTP/IP na porta 15740 com sockets vinculados explicitamente à rede Wi-Fi selecionada, inclusive sem internet.
- SL2: Wi-Fi / Controle remoto (EOS Utility) / Registrar dispositivo. Conectar Android à rede da câmera, manter sem internet, fechar Camera Connect, autorizar LUMO se solicitado.
- O IP inicia pelo gateway da rede, pode ser corrigido no diálogo. GUID persistente.
- Implementados canais de comandos e eventos, pareamento PTP/IP, ping/pong, download em blocos e validação de transação/tamanho. A câmera pode exigir descoberta/pareamento Canon adicional não implementado; compatibilidade SL2 real ainda não validada.
- Notificação com som/vibração uma vez por queda após conexão confirmada; botão Testar alerta, preferência liga/desliga. Depende da permissão, canal de notificações, volume e Não Perturbe. Parada voluntária não dispara alarme.
- Testes WifiWireTest: TCP fragmentado, JPEG em streaming, ping/pong, tamanho e sequência inválidos, resposta busy. WifiTransportTest: servidor local simulado testa handshake comando/evento, sessão, download e fechamento. Stubs Android somente nos testes, não no APK.
- O defeito USB reportado na SL2 não foi reproduzido nem declarado corrigido. Adicionado registro do nome/tamanho antes do download para diagnóstico.
- Referências: https://ph.canon/en/support/8203565900 ; https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip.c ; https://developer.android.com/reference/android/net/Network

## Alerta direto 0.6
- Três bipes via ToneGenerator no volume de mídia, independentes da permissão/canal de notificações. Aviso visual separado e silencioso.
- Botão Testar alerta toca mesmo se preferência de alertas automáticos estiver desligada.
- Wake lock limitado a quatro segundos mantém o agendamento do som durante encerramento do serviço; liberado em 1,8 s.
- Disparos sobrepostos substituem a sequência anterior; botão Encerrar captura não dispara alerta.
- Compilação/assinatura verificadas; volume, saída de áudio e reprodução real precisam de teste no aparelho. DND e volume zero continuam sendo respeitados.


## Pasta de exportação 0.7
- Nova seleção nativa de pasta pelo Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE).
- A permissão da pasta é persistida pelo Android e reaproveitada após reiniciar o app.
- A pasta escolhida vira a raiz da exportação; o LUMO cria `Originais` e `Editadas` dentro dela.
- Sem seleção personalizada, mantém compatibilidade com `Pictures/LUMOTether`.
- Troca de pasta é bloqueada enquanto uma sessão está ativa, evitando destinos diferentes no mesmo processamento.


## Fotto experimental 0.8
- Nova aba `Fotto`: abre o login oficial dentro do app, captura a sessão após autenticação e consulta os eventos da conta.
- Após escolher um evento, `Monitorar Editadas` passa a enviar somente novas cópias concluídas depois da ativação.
- `Enviar Editadas já existentes` permite varrer e enviar as fotos concluídas anteriormente para o evento selecionado.
- O Android não fornece ao site um caminho tradicional para uma pasta escolhida pelo Storage Access Framework. Por isso o LUMO usa a URI persistente da exportação e envia diretamente o JPEG salvo em `Editadas`.
- O upload é independente da câmera e da edição. Falhas ficam registradas na fila SQLite e são limitadas a 3 tentativas automáticas por foto/evento. Uploads já marcados como concluídos não são reenviados; uma interrupção exatamente entre o PUT e a confirmação local ainda pode exigir conferência manual nesta build de teste.
- A integração usa os endpoints observados no app de referência fornecido para teste: listagem de galerias, criação de mídia e envio do JPEG para URL assinada. Como é uma integração experimental não documentada publicamente, mudanças no Fotto podem exigir ajuste.
- A sessão Fotto fica em preferências privadas do aplicativo nesta build de teste (`allowBackup=false`). Para distribuição de produção, migrar o token para armazenamento criptografado/Keystore.
- Saída esperada do build: `LUMO-Tether-0.8-fotto.apk`.


## 0.9.1 Fotto auth fix
- Fotto API auth now mirrors the reference client: `Authorization: Bearer <apiKey>` + `App-Code: fotto` + JSON content type.
- Login capture prioritizes the Fotto API key and no longer accepts arbitrary high-entropy localStorage values.
- Uses a new saved token key (`fottoTokenV2`) so the first launch requires reconnecting Fotto once and cannot reuse the stale 0.9 credential.


## 0.9.2 - Fotto session fix
A captura de sessao agora aceita apenas chaves explicitas apiKey/api_key/apikey/access_token e valida a sessao em /me antes de consultar /me/galleries. Isso evita usar tokens irrelevantes do localStorage, uma causa comum de HTTP 500.
