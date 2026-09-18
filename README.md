# LUMO 0.12.0 — UX Pro

Aplicativo Android para captura vinculada Canon, edição automática/manual, curadoria técnica e entrega ao Fotto.

## Fluxo principal

**Capture → Gallery → Review → Deliver**

- **Capture**: última foto em destaque, filmstrip de recentes, estado da câmera/fila e configurações da sessão.
- **Gallery**: grid de alto volume, score técnico, estado da foto e seleção múltipla.
- **Review**: fotos bloqueadas pela curadoria, com motivo, score, Aprovar e Editar.
- **Deliver**: conta/evento Fotto, fila, enviados e problemas.
- **Editor contextual**: aberto pela Gallery/Review, com Original / Auto / Preset / Final, ajuste em tempo real e lote.

## Score técnico 1–10

O score é uma triagem técnica, não uma nota estética. Ele considera:

- nitidez/desfoque;
- exposição;
- contraste;
- clipping;
- enquadramento técnico.

O score é calculado mesmo quando o bloqueio automático da Curadoria estiver desligado. Fotos antigas sem score são analisadas gradualmente quando aparecem na Gallery.

## Curadoria

Sensibilidade Baixa, Média ou Alta. Quando uma foto é reprovada, ela é enviada para **Sob Revisao**, permanece fora da fila de upload e só volta a ficar apta para entrega depois de Aprovar ou corrigir no editor.

## Editor

O editor parte do original preservado e reconstrói:

**Original → Auto → Preset → ajustes manuais**

A interface mostra um controle por vez e os valores de Base / Auto / Preset. Segurar a foto exibe o Original; soltar retorna ao Final. Em lote, é possível copiar somente parâmetros escolhidos e aplicar o refinamento em várias fotos.

## Modo escuro

O botão de tema no cabeçalho alterna entre claro e escuro. A escolha é persistida e também vale para o editor.

## Recuperação retroativa

Ao reconectar a câmera, o LUMO pode verificar o cartão, ignorar itens já conhecidos e recuperar JPEGs que ainda não passaram pelo app. As fotos recuperadas seguem edição, score, curadoria e entrega normalmente.

## Compilação

O projeto usa Java + Android SDK 35, sem Gradle. O workflow em `.github/workflows/build-apk.yml` instala API 35 / Build Tools 35.0.0, verifica que as mudanças UX 0.12.0 estão presentes e executa `python3 build.py`.

Saída: **Lumo.apk**

- `versionCode`: 24
- `versionName`: `0.12.0-ux-pro`
- minSdk: 29
- targetSdk: 35

## Validação incluída

Os testes locais de recuperação de fila e fila Fotto incluídos no projeto foram executados com sucesso. A compilação Android completa deve ser confirmada pelo GitHub Actions, pois ela depende do SDK Android 35.

Veja `IMPLEMENTACAO-UX-0.12.0.txt` para a lista item a item das alterações solicitadas.
