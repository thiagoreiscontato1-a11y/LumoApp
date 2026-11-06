# Prompt: Galeria online do Lumo, etapa multi-fotógrafo

> Cole este texto no início de uma nova conversa. Ele resume o que já foi definido e construído para a galeria online,
> para não precisar carregar a conversa inteira. Responda em português.

## Contexto

Repositório: `thiagoreiscontato1-a11y/LumoApp`. O **Lumo** é um app Android (Java, sem Gradle, compilado por `build.py`)
que captura fotos de câmeras Canon por cabo ou Wi-Fi, edita automaticamente, faz a curadoria e entrega ao **Fotto**
(plataforma de eventos). Também existe uma versão iOS em `ios/`, que ainda não tem a galeria.

### Decisões já tomadas
- Quem vê a galeria é o **convidado do evento** (a pessoa fotografada), pelo navegador, sem instalar nada.
- A galeria **complementa o Fotto**, que continua igual. As mesmas fotos aprovadas vão para os dois.
- Só **JPEGs editados**, nada de RAW.
- Hospedagem na **Cloudflare** (conta gratuita por enquanto), no endereço `*.workers.dev`. Ainda **não há domínio**.

## O que já existe: versão 1 (PR #19, branch `ccr-e402bb6c-w1n4lj`, Android 0.18.0)

Tudo está em `online-gallery/worker` (Cloudflare Worker + R2 + D1). O guia de publicação fica em `online-gallery/README.md`.

- **Servidor** (`src/index.js`):
  - Rotas do fotógrafo em `/v1/*`, com `Authorization: Bearer <ADMIN_TOKEN>`. Hoje existe **uma única chave**, ou seja, **um fotógrafo só**.
  - Rotas do convidado em `/api/g/<slug>/*`, com cookie HMAC de 30 dias por galeria. A senha é opcional e tem limite de 8 erros a cada 15 minutos por IP.
  - Cada foto vai em **3 versões**: `thumb` (640 px), `preview` (2048 px) e `full` (JPEG editado completo). O R2 confere tamanho e SHA-256.
  - O envio é idempotente por `clientPhotoId + revision`. Uma revisão nova substitui a antiga e apaga a anterior do R2.
  - Fotos no R2 em `p/<galeria>/<foto>/<revisão>/<variante>.jpg`.
- **Banco** (`migrations/0001_init.sql`): tabelas `galleries`, `photos`, `photo_variants`, `visitors`, `favorites` e `pin_failures`.
- **Página do convidado** (`public/guest.html`, em `/g/<slug>`):
  - grade de fotos que se ajusta à tela e tela cheia com deslizar;
  - favoritas;
  - download de uma foto, `.zip` das favoritas (`public/zip.js`, sem compressão) ou "Salvar no celular" pelo menu de compartilhar do sistema;
  - aviso de fotos novas e modo claro/escuro.
- **Painel** (`public/admin.html`, em `/admin`): galerias, QR code (`public/vendor/qrcode.js`), configurações (senha, download, ocultar, expiração, ordem), envio pelo navegador e ranking de favoritas.
- **App Android:**
  - `GalleryApi.java`: cliente HTTP. A parte de rede fica em `publish(...)`, que não depende do Android.
  - `GallerySync.java`: fila em segundo plano, acionada por `FottoSync.kick`.
  - `Jobs.java`: tabela `online_uploads`, com a revisão igual a `edited_at`.
  - `MainActivity.java`: o menu `galleryMenu()` em Entrega → Galeria online.
  - Cada evento local tem a sua galeria, salva em `lgGallery_<eventId>` nas preferências.
- **Testes e CI:**
  - `npm test` (67 verificações de ponta a ponta com `wrangler dev`);
  - `tests/online_gallery_contract.py`;
  - o workflow `.github/workflows/gallery.yml` testa a cada mudança e publica sob demanda. Segredos: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `GALLERY_ADMIN_TOKEN`.

## Custos da Cloudflare (preços de novembro de 2026; confira antes)

- **Workers Paid:** US$ 5/mês com 10 milhões de requisições e 30 milhões de ms de CPU. Acima disso, US$ 0,30 por milhão de requisições e US$ 0,02 por milhão de ms de CPU.
- **R2:**
  - armazenamento: US$ 0,015/GB/mês (10 GB grátis);
  - leituras: US$ 0,36 por milhão (10 milhões grátis);
  - escritas: US$ 4,50 por milhão (1 milhão grátis);
  - **download para o convidado é grátis.**
- **Estimativa:** cerca de 450 requisições por visita de convidado.
  - O plano de US$ 5 atende ~20 mil visitas/mês.
  - Acima disso, cada mil visitas custa ~US$ 0,30 a 0,40 (metade servidor, metade leituras no R2).
  - Com domínio próprio e cache das imagens na Cloudflare, esse custo pode cair ~90%.

## Próxima etapa pedida: vários fotógrafos, cada um com suas galerias e um plano de espaço

**Requisitos:**
- Muitos fotógrafos, cada um com galerias separadas, de **2.000 a 3.000 fotos por galeria**.
- **Controle de espaço por fotógrafo** com planos de **5 GB, 10 GB, 50 GB** e maiores (100 GB…). O fotógrafo cria galerias dentro do limite do plano.

**Espaço por galeria** (estimativa para uma câmera de 24 MP):

| Download | Por foto (3 versões) | 2.000 fotos | 3.000 fotos |
|---|---|---|---|
| Original (~6 MB) | ~7 MB | ~14 GB | ~21 GB |
| Alta resolução 3600 px (~2 MB) | ~3 MB | ~6 GB | ~9 GB |

| Plano | Galerias de 3.000 fotos (original) | Galerias de 3.000 fotos (alta resolução) |
|---|---|---|
| 5 GB | nenhuma (~700 fotos) | meia (~1.700 fotos) |
| 10 GB | nenhuma | 1 |
| 50 GB | 2 | 5 |
| 100 GB | 4–5 | 11 |

**Custo de cada fotógrafo para o dono:** de centavos a ~US$ 2–3/mês.
- Espaço: 5 GB ≈ US$ 0,08; 50 GB ≈ US$ 0,75; 100 GB ≈ US$ 1,50.
- Visitas: ~US$ 1 a cada ~2.400.

**Mudanças propostas:**
1. **Contas de fotógrafo:**
   - login próprio no painel e uma chave própria para o app (guardar só o hash);
   - cada um vê só as próprias galerias;
   - nome e marca do estúdio na página do convidado;
   - `galleries` ganha `photographer_id`.
2. **Planos com limite de espaço:**
   - a conta soma as 3 versões de cada foto;
   - o servidor recusa no `POST /v1/photos` (antes do envio) quando o plano está cheio, já contando os envios em andamento;
   - o uso é atualizado no `complete` e nas exclusões, com uma rotina de recálculo;
   - barra de uso no app e no painel, com aviso a partir de 80%.
3. **Escolha do download por galeria:** alta resolução 3600 px (padrão sugerido), original ou sem download. O app gera a versão de 3600 px.
4. **Painel do dono (super-admin):** criar e suspender fotógrafos, trocar de plano e ver o uso e o custo de cada um.
5. **Limpeza automática (opcional):** apagar galerias depois de X meses. Hoje a data de expiração só bloqueia o acesso, não libera espaço.

Também recomendado: **domínio próprio** (link profissional e cache das imagens) e **termos de uso e política de privacidade (LGPD)**,
porque o serviço passa a guardar fotos de pessoas para terceiros.

## Decisões pendentes (perguntar antes de construir)

1. **Cadastro:** o dono cria a conta de cada fotógrafo ou há auto-cadastro? (sugestão: o dono cria)
2. **Cobrança:** manual (Pix por fora, o dono troca o plano no painel) ou assinatura automática (Asaas ou Mercado Pago, com bloqueio por falta de pagamento)? (sugestão: começar manual)
3. **Download padrão:** alta resolução 3600 px ou original? (sugestão: 3600 px)
4. **Domínio:** comprar agora ou seguir no `workers.dev`?

## Como trabalhar

- Leia `online-gallery/README.md`, `online-gallery/worker/src/index.js` e `migrations/0001_init.sql` antes de mudar.
- Mantenha a API compatível com o app que já está em uso, ou atualize `GalleryApi.java` junto.
- Migrações novas vão em `migrations/0002_...sql`, sem editar a 0001.
- Antes de enviar, rode `cd online-gallery/worker && npm test` e `for t in tests/*.py; do python3 "$t"; done`.
