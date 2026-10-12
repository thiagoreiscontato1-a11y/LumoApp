# Preparação da galeria online — versão 1

O app exporta lumo-gallery-manifest.json (todos os registros, todos os álbuns). O manifesto contém IDs estáveis, álbum, nome original, datas em milissegundos UTC, estado e presença de RAW/editada. Não contém imagens, URIs locais, credenciais ou links públicos.

Esta entrega não publica uma galeria, não cria contas e não faz sincronização automática. O botão Compartilhar originais selecionados abre o compartilhamento Android com RAW completos quando disponíveis. Fotto continua recebendo JPEGs editados pelo fluxo existente.

## Contrato proposto

- POST /v1/albums: criar álbum, retornar id remoto; guardar mapeamento entre chave local e id remoto.
- POST /v1/photos: metadados + clientPhotoId, albumId, revisão, tamanho, SHA-256 e variante (raw/original/edited/thumbnail); retornar uploadId e URL de envio assinada.
- PUT na URL assinada: enviar bytes em segundo plano; original RAW sem conversão.
- POST /v1/uploads/{id}/complete: confirmar tamanho e checksum; só marcar enviado após confirmação.
- GET /v1/albums/{id}/photos?cursor=...: listar todas as fotos por paginação.
- DELETE /v1/photos/{id}: exclusão remota explícita, separada da exclusão local.

Repetições devem usar chave idempotente por clientPhotoId + variante + revisão. Preset alterado incrementa a revisão da editada. A futura fila persistente deve registrar tentativas, progresso, checksum e confirmação. Exclusões locais não devem apagar mídia remota automaticamente.

Backend necessário: autenticação, banco de metadados, armazenamento privado de objetos, URLs temporárias de acesso e trabalhadores para miniaturas/revelação RAW (ex.: LibRaw). RAW sem JPEG embutido será revelado por esse serviço ou por um decodificador nativo futuro; nesta versão é preservado e aparece com indicação de prévia indisponível.
