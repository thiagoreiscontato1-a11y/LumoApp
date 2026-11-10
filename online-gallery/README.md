# Galeria online do Lumo

Os convidados do evento (as pessoas fotografadas) recebem um link ou QR code e, pelo navegador, sem instalar nada:

- veem as fotos numa grade bonita, abrem em tela cheia e deslizam entre elas;
- **favoritam** (o coração fica guardado no aparelho e o fotógrafo vê quem favoritou o quê);
- **baixam** uma foto, ou todas as favoritas em `.zip`, ou salvam direto na galeria do celular;
- recebem aviso de **fotos novas** enquanto o evento ainda acontece.

A galeria **complementa o Fotto**: o Lumo continua entregando ao Fotto do mesmo jeito e, em paralelo, publica as mesmas
fotos aprovadas (JPEG editado) na galeria online.

Tudo roda na sua conta gratuita da Cloudflare e fica num endereço `https://lumo-galeria.<sua-conta>.workers.dev`.
Não precisa de domínio. Se um dia comprar um, dá para apontá-lo para o mesmo servidor.

## Como funciona

| Parte | Onde | O que faz |
|---|---|---|
| Servidor | `worker/src/index.js` (Cloudflare Workers) | API, senha dos convidados, favoritos |
| Fotos | Cloudflare R2 (`lumo-galeria-fotos`) | 3 versões de cada foto: miniatura 640 px, tela cheia 2048 px e o JPEG completo |
| Dados | Cloudflare D1 (`lumo-galeria`) | galerias, fotos, visitantes, favoritos |
| Página do convidado | `worker/public/guest.html` → `/g/<código>` | grade, visualizador, favoritas, downloads |
| Painel do fotógrafo | `worker/public/admin.html` → `/admin` | criar galerias, QR code, senha, enviar pelo computador, ver favoritas |
| App Android | Entrega → **Galeria online** | cria a galeria do evento e publica cada foto aprovada sozinho |

Cada evento do Lumo tem a sua galeria. Quando a foto é reeditada no app, a versão online é trocada e os favoritos continuam.
As fotos só aparecem para os convidados depois que as 3 versões chegam inteiras: o servidor confere tamanho e SHA-256 de cada arquivo.

## Publicar pela primeira vez (cerca de 15 minutos, uma vez só)

### 1. Preparar a conta Cloudflare

1. Entre em [dash.cloudflare.com](https://dash.cloudflare.com).
2. Abra **Workers & Pages** uma vez. Se pedir, escolha o seu subdomínio `workers.dev`, que será parte do endereço da galeria.
3. Abra **R2 Object Storage** e ative. A Cloudflare costuma pedir um cartão para ativar o R2, mesmo no plano gratuito.
   Nada é cobrado dentro dos limites gratuitos (veja abaixo).
4. Copie o **Account ID**: fica na lateral direita da página inicial de Workers & Pages, ou na URL `dash.cloudflare.com/<account-id>/...`.
5. Crie um token de API em **My Profile → API Tokens → Create Token**, usando o modelo **Edit Cloudflare Workers**.
   Antes de salvar, acrescente as permissões **Account → D1 → Edit** e **Account → Workers R2 Storage → Edit**.
   Copie o token, porque ele só aparece uma vez.

### 2. Guardar os segredos no GitHub

No repositório: **Settings → Secrets and variables → Actions → New repository secret**:

| Nome | Valor |
|---|---|
| `CLOUDFLARE_API_TOKEN` | o token do passo 1.5 |
| `CLOUDFLARE_ACCOUNT_ID` | o Account ID do passo 1.4 |
| `GALLERY_ADMIN_TOKEN` | uma senha longa, só sua, com **24 caracteres ou mais**. É a "chave de acesso" do painel e do app. Guarde num gerenciador de senhas. |

Opcional: em **Variables**, crie `STUDIO_NAME` com o nome do seu estúdio. Ele aparece no topo da galeria; sem ela, aparece "Lumo".

### 3. Publicar

**Actions → Galeria online → Run workflow**. O workflow testa o servidor, cria o R2 e o D1 se não existirem e publica.
No fim, o resumo da execução mostra o endereço, por exemplo `https://lumo-galeria.suaconta.workers.dev`.

Para atualizar depois, rode o mesmo workflow. As galerias e as fotos não são apagadas.

### 4. Ligar o app

No Lumo: **Entrega → ⋯ → Galeria online → Conectar servidor da galeria**. Informe o endereço (sem `https://`, tanto faz)
e a chave `GALLERY_ADMIN_TOKEN`. Depois toque em **Criar galeria deste evento**, escolha uma senha opcional e pronto:
as fotos aprovadas passam a ser publicadas. Use **Compartilhar link** para mandar pelo WhatsApp, ou abra o
**Painel no navegador** para baixar o QR code e imprimir na mesa do evento.

No iPhone (Lumo iOS 0.5.0+): **Entrega → Galeria online → Conectar servidor da galeria**, com o mesmo endereço e a
mesma chave. A galeria fica ligada ao evento local de cada aparelho.

## No dia do evento

1. Crie o evento no Lumo e a galeria dele (passo 4).
2. Imprima o QR code (painel → **Baixar QR (PNG)**) com a senha, se tiver.
3. Fotografe normalmente. Cada foto aprovada vai para o Fotto e para a galeria. Quem está com a galeria aberta vê o aviso "fotos novas".
4. Depois do evento, veja no painel a aba **Favoritas dos convidados**: dá para saber quais fotos fizeram mais sucesso e quem pediu cada uma.

## Limites do plano gratuito da Cloudflare

Os valores abaixo são os que eu conheço; a Cloudflare pode mudá-los. Confira em developers.cloudflare.com antes de um evento grande.

| Recurso | Grátis | Na prática |
|---|---|---|
| R2: armazenamento | 10 GB/mês | cerca de 1.500 fotos de 6 MB (as três versões somadas) |
| R2: download | sem custo de saída | convidados baixando não geram cobrança de banda |
| Workers: requisições | 100 mil/dia | cada miniatura vista conta 1; uma galeria de 500 fotos aberta por inteiro por uns 150 convidados/dia |
| D1 | 5 milhões de leituras/dia | sobra |

Se passar disso, o plano **Workers Paid (US$ 5/mês)** sobe as requisições para 10 milhões por mês. O R2 cobra cerca de
US$ 0,015 por GB por mês acima de 10 GB. Apague galerias antigas pelo painel (o botão apaga as fotos online; as do celular continuam).

## Segurança e privacidade

- O link tem um código aleatório de 10 caracteres e não aparece em buscadores (`noindex`).
- Com senha: até 8 tentativas erradas a cada 15 minutos por rede. Trocar a senha desconecta quem já entrou.
- O convidado entra com um cookie assinado (HMAC), válido por 30 dias e restrito àquela galeria.
- **Ocultar galeria** ou definir **expira em** corta o acesso sem apagar nada.
- O painel e o app usam a chave `GALLERY_ADMIN_TOKEN`. Quem tiver essa chave controla todas as galerias.

## Desenvolvimento local

```bash
cd online-gallery/worker
npm install
echo "ADMIN_TOKEN=uma-chave-local" > .dev.vars
npx wrangler d1 migrations apply lumo-galeria --local
npm run dev            # http://localhost:8787/admin
npm test               # 67 verificações de ponta a ponta (sobe um servidor local próprio)
```

## API (para o app e integrações)

Fotógrafo (`Authorization: Bearer <ADMIN_TOKEN>`):

- `GET /v1/me` → confere a chave.
- `GET|POST /v1/galleries`, `GET|PATCH|DELETE /v1/galleries/{id}`. O `POST` aceita `clientKey` (ID do evento local) e é idempotente.
- `POST /v1/photos` `{galleryId, clientPhotoId, revision, filename, takenAt, width, height, variants:{thumb|preview|full:{size, sha256}}}`
  → `{photoId, ready, uploads:[{variant, url, done}], completeUrl}`.
- `PUT /v1/photos/{id}/r/{revision}/{variant}` com o JPEG. O servidor recusa tamanho ou SHA-256 diferentes.
- `POST /v1/photos/{id}/complete` `{revision}` → publica. Uma revisão nova substitui a anterior, que é apagada.
- `GET /v1/galleries/{id}/photos?cursor=`, `GET /v1/galleries/{id}/favorites`, `DELETE /v1/photos/{id}`.

Repetir qualquer passo é seguro: a chave de idempotência é `clientPhotoId + revision`, e variantes já recebidas são puladas.
No app, `clientPhotoId` é o ID da foto no Lumo e `revision` é o horário da última edição.

Convidado (cookie de sessão): `GET /api/g/{código}`, `POST /api/g/{código}/session` `{pin, name, visitorId}`,
`GET .../photos?order=asc|desc&cursor=`, `GET .../updates?since=`, `GET .../favorites`, `PUT|DELETE .../fav/{foto}`,
`GET .../p/{foto}/{revisão}/{thumb|preview|full}[?dl=1&n=nome]`.
