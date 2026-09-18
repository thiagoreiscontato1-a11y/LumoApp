# LUMO 0.13.0 — Produção

Aplicativo Android para captura vinculada Canon, edição, curadoria, recuperação retroativa e entrega confirmada ao Fotto. A interface visível está em português.

## Recursos desta versão

1. **Confirmação real no Fotto** — após o envio binário, o LUMO consulta novamente o evento e só conta a foto como confirmada quando encontra o ID/nome da mídia. Estados: enviando, processando, confirmada, não confirmada e erro.
2. **Saúde do fluxo** — painel com Câmera, Recebimento, Edição, Curadoria, Fotto, fila, última foto recebida, confirmadas e pendentes.
3. **Fila persistente** — SQLite, retomada após reabrir o app, troca do APK e reinicialização do aparelho, com JobScheduler.
4. **Reconexão inteligente** — ao conectar a câmera, verifica o cartão, recupera o que ainda não foi baixado e reconcilia a fila Fotto.
5. **Curadoria inteligente local** — combina análise técnica com heurísticas locais de rosto para sinalizar possível olho fechado, rosto muito virado, corte de pessoa/rosto, obstrução e expressão desfavorável. É uma triagem experimental, não substitui revisão humana.
6. **Rajadas** — fotos capturadas em sequência próxima são agrupadas e ordenadas pela nota; o detalhe mostra a melhor e alternativas.
7. **Duplicatas** — SHA-256 para duplicata exata e hash perceptual para imagens visualmente muito semelhantes fora da mesma rajada.
8. **Histórico por foto** — captura, download, edição/predefinição, curadoria/nota, aprovação, início/fim do envio e confirmação no Fotto.
9. **Perfis de trabalho** — Corrida, Aniversário, Ensaio, Futebol e Personalizado. Cada perfil pode guardar curadoria, intensidade automática, predefinição, recuperação retroativa, pasta de exportação, evento Fotto e comportamento de envio automático.
10. **Modo Evento** — mantém a tela ativa, habilita recuperação/curadoria, retoma filas, monitora Fotto, bateria e espaço e usa alerta sonoro para falhas críticas. A tela fica mais enxuta ocultando configurações gerais enquanto o modo está ativo.

## Predefinição durante o evento

Na tela Captura existe um controle de **Predefinição**. É possível trocar entre predefinições já salvas, desligar a predefinição ou importar um novo XMP sem encerrar a captura. A mudança vale para as próximas fotos recebidas; fotos já processadas não são reprocessadas automaticamente.

## Idioma

A interface, navegação, estados de entrega, configurações e mensagens operacionais estão em português. Termos técnicos internos de API/SQLite permanecem apenas no código e em diagnósticos técnicos.

## Compilação

- Android SDK 35 / Build Tools 35.0.0
- `versionCode`: 25
- `versionName`: `0.13.0-producao`
- saída: `Lumo.apk`

O GitHub Actions executa as verificações de recursos, os testes de fila e então `python3 build.py`.
