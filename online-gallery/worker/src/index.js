// Galeria online do Lumo — API e páginas (Cloudflare Worker).
//
// Fotógrafo (app Lumo ou /admin): rotas /v1/*, protegidas por "Authorization: Bearer <ADMIN_TOKEN>".
// Convidado (/g/<slug>): rotas /api/g/<slug>/*, protegidas por cookie de sessão assinado (HMAC).
// Fotos ficam no R2 em p/<galeria>/<foto>/<revisão>/<variante>.jpg; metadados, favoritos e visitantes no D1.

const VARIANTS = {
  thumb: 2_000_000, // miniatura da grade (~640 px)
  preview: 10_000_000, // tela cheia (~2048 px)
  full: 60_000_000, // JPEG editado completo (download)
};
const REQUIRED_VARIANTS = Object.keys(VARIANTS);
const SESSION_DAYS = 30;
const PIN_WINDOW_MS = 15 * 60 * 1000;
const PIN_MAX_FAILURES = 8;
const ADMIN_VISITOR = "fotografo";
const ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
const encoder = new TextEncoder();

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export default {
  async fetch(request, env, ctx) {
    try {
      return await route(request, env, ctx);
    } catch (error) {
      if (error instanceof HttpError) return json({ error: error.message }, error.status);
      console.error(error && error.stack ? error.stack : error);
      return json({ error: "Erro interno no servidor." }, 500);
    }
  },
};

async function route(request, env, ctx) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;
  let m;

  if (path === "/" && method === "GET") return Response.redirect(new URL("/admin", url).toString(), 302);
  if (path === "/admin" && method === "GET") return page(env, url, "/admin.html");
  if ((m = path.match(/^\/g\/([a-z0-9]{6,32})\/?$/)) && method === "GET") return page(env, url, "/guest.html");
  if (path.startsWith("/v1/")) {
    await requireAdmin(request, env);
    return adminRoute(request, env, ctx, url, path, method);
  }
  if ((m = path.match(/^\/api\/g\/([a-z0-9]{6,32})(\/.*)?$/))) return guestRoute(request, env, url, m[1], m[2] || "/", method);
  return new Response("Página não encontrada.", { status: 404, headers: { "content-type": "text/plain; charset=utf-8" } });
}

// ---------------------------------------------------------------- páginas

async function page(env, url, asset) {
  const res = await env.ASSETS.fetch(new Request(new URL(asset, url).toString()));
  if (!res.ok) return res;
  const headers = new Headers(res.headers);
  headers.set("content-type", "text/html; charset=utf-8");
  headers.set("cache-control", "no-cache");
  headers.set("referrer-policy", "no-referrer");
  headers.set("x-content-type-options", "nosniff");
  headers.set("x-frame-options", "DENY");
  return new Response(res.body, { status: 200, headers });
}

// ---------------------------------------------------------------- fotógrafo

async function requireAdmin(request, env) {
  if (!env.ADMIN_TOKEN) throw new HttpError(503, "ADMIN_TOKEN não configurado no servidor.");
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!token || !(await sameSecret(token, env.ADMIN_TOKEN))) throw new HttpError(401, "Chave de acesso inválida.");
}

async function adminRoute(request, env, ctx, url, path, method) {
  let m;
  if (path === "/v1/me" && method === "GET") return json({ ok: true, studio: studio(env) });

  if (path === "/v1/galleries") {
    if (method === "GET") return json({ galleries: await listGalleries(env, url) });
    if (method === "POST") return createGallery(request, env, url);
  }
  if ((m = path.match(/^\/v1\/galleries\/([a-z0-9]+)$/))) {
    if (method === "GET") return json({ gallery: await galleryJson(env, url, await galleryById(env, m[1])) });
    if (method === "PATCH") return updateGallery(request, env, url, m[1]);
    if (method === "DELETE") return deleteGallery(env, m[1]);
  }
  if ((m = path.match(/^\/v1\/galleries\/([a-z0-9]+)\/photos$/)) && method === "GET") return adminPhotos(env, url, m[1]);
  if ((m = path.match(/^\/v1\/galleries\/([a-z0-9]+)\/favorites$/)) && method === "GET") return adminFavorites(env, m[1]);
  if ((m = path.match(/^\/v1\/galleries\/([a-z0-9]+)\/preview-session$/)) && method === "POST") return previewSession(env, url, m[1]);

  if (path === "/v1/photos" && method === "POST") return registerPhoto(request, env, url);
  if ((m = path.match(/^\/v1\/photos\/([a-z0-9]+)\/r\/(\d+)\/([a-z]+)$/)) && method === "PUT") return uploadVariant(request, env, m[1], Number(m[2]), m[3]);
  if ((m = path.match(/^\/v1\/photos\/([a-z0-9]+)\/complete$/)) && method === "POST") return completePhoto(request, env, ctx, m[1]);
  if ((m = path.match(/^\/v1\/photos\/([a-z0-9]+)$/)) && method === "DELETE") return deletePhoto(env, m[1]);

  throw new HttpError(404, "Rota não encontrada.");
}

async function listGalleries(env, url) {
  const { results } = await env.DB.prepare(
    `SELECT g.*,
      (SELECT COUNT(*) FROM photos p WHERE p.gallery_id = g.id) AS photo_count,
      (SELECT COUNT(*) FROM photos p WHERE p.gallery_id = g.id AND p.ready_revision IS NOT NULL) AS ready_count,
      (SELECT COUNT(*) FROM favorites f WHERE f.gallery_id = g.id AND f.visitor_id <> ?1) AS favorite_count,
      (SELECT COUNT(*) FROM visitors v WHERE v.gallery_id = g.id AND v.id <> ?1) AS visitor_count
     FROM galleries g ORDER BY g.created_at DESC`
  ).bind(ADMIN_VISITOR).all();
  return results.map((g) => galleryShape(url, g, env));
}

async function galleryJson(env, url, g) {
  const counts = await env.DB.prepare(
    `SELECT
      (SELECT COUNT(*) FROM photos WHERE gallery_id = ?1) AS photo_count,
      (SELECT COUNT(*) FROM photos WHERE gallery_id = ?1 AND ready_revision IS NOT NULL) AS ready_count,
      (SELECT COUNT(*) FROM favorites WHERE gallery_id = ?1 AND visitor_id <> ?2) AS favorite_count,
      (SELECT COUNT(*) FROM visitors WHERE gallery_id = ?1 AND id <> ?2) AS visitor_count`
  ).bind(g.id, ADMIN_VISITOR).first();
  return galleryShape(url, { ...g, ...counts }, env);
}

function galleryShape(url, g, env) {
  return {
    id: g.id,
    slug: g.slug,
    clientKey: g.client_key,
    title: g.title,
    url: publicOrigin(env, url) + "/g/" + g.slug,
    hasPin: !!g.pin_hash,
    allowDownload: !!g.allow_download,
    status: g.status,
    order: g.sort_order,
    expiresAt: g.expires_at,
    createdAt: g.created_at,
    photoCount: g.photo_count ?? 0,
    readyCount: g.ready_count ?? 0,
    favoriteCount: g.favorite_count ?? 0,
    visitorCount: g.visitor_count ?? 0,
  };
}

async function createGallery(request, env, url) {
  const body = await readJson(request);
  const title = cleanText(body.title, 120);
  if (!title) throw new HttpError(400, "Informe o nome da galeria.");
  const clientKey = body.clientKey == null || body.clientKey === "" ? null : cleanText(String(body.clientKey), 200);
  if (clientKey) {
    const existing = await env.DB.prepare("SELECT * FROM galleries WHERE client_key = ?").bind(clientKey).first();
    if (existing) return json({ gallery: await galleryJson(env, url, existing), created: false });
  }
  const now = Date.now();
  const id = rid(16);
  const slug = rid(10);
  const pin = body.pin == null || body.pin === "" ? null : await hashPin(validPin(body.pin));
  const allow = body.allowDownload === false ? 0 : 1;
  const order = body.order === "asc" ? "asc" : "desc";
  const expires = optionalTime(body.expiresAt);
  await env.DB.prepare(
    `INSERT INTO galleries (id, slug, client_key, title, pin_hash, allow_download, status, sort_order, expires_at, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)`
  ).bind(id, slug, clientKey, title, pin, allow, order, expires, now, now).run();
  return json({ gallery: await galleryJson(env, url, await galleryById(env, id)), created: true }, 201);
}

async function updateGallery(request, env, url, id) {
  const g = await galleryById(env, id);
  const body = await readJson(request);
  const sets = [];
  const args = [];
  if (body.title !== undefined) {
    const title = cleanText(body.title, 120);
    if (!title) throw new HttpError(400, "Informe o nome da galeria.");
    sets.push("title = ?");
    args.push(title);
  }
  if (body.pin !== undefined) {
    sets.push("pin_hash = ?");
    args.push(body.pin === null || body.pin === "" ? null : await hashPin(validPin(body.pin)));
  }
  if (body.allowDownload !== undefined) {
    sets.push("allow_download = ?");
    args.push(body.allowDownload ? 1 : 0);
  }
  if (body.status !== undefined) {
    if (!["active", "hidden"].includes(body.status)) throw new HttpError(400, "Situação inválida.");
    sets.push("status = ?");
    args.push(body.status);
  }
  if (body.order !== undefined) {
    sets.push("sort_order = ?");
    args.push(body.order === "asc" ? "asc" : "desc");
  }
  if (body.expiresAt !== undefined) {
    sets.push("expires_at = ?");
    args.push(optionalTime(body.expiresAt));
  }
  if (sets.length) {
    sets.push("updated_at = ?");
    args.push(Date.now(), g.id);
    await env.DB.prepare(`UPDATE galleries SET ${sets.join(", ")} WHERE id = ?`).bind(...args).run();
    gallerySlugCache.delete(g.slug);
  }
  return json({ gallery: await galleryJson(env, url, await galleryById(env, id)) });
}

async function deleteGallery(env, id) {
  const g = await galleryById(env, id);
  await deletePrefix(env, `p/${g.id}/`);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM favorites WHERE gallery_id = ?").bind(g.id),
    env.DB.prepare("DELETE FROM photo_variants WHERE photo_id IN (SELECT id FROM photos WHERE gallery_id = ?)").bind(g.id),
    env.DB.prepare("DELETE FROM photos WHERE gallery_id = ?").bind(g.id),
    env.DB.prepare("DELETE FROM visitors WHERE gallery_id = ?").bind(g.id),
    env.DB.prepare("DELETE FROM pin_failures WHERE gallery_id = ?").bind(g.id),
    env.DB.prepare("DELETE FROM galleries WHERE id = ?").bind(g.id),
  ]);
  gallerySlugCache.delete(g.slug);
  return json({ ok: true });
}

async function adminPhotos(env, url, galleryId) {
  const g = await galleryById(env, galleryId);
  const limit = clampInt(url.searchParams.get("limit"), 1, 500, 200);
  const cursor = url.searchParams.get("cursor") || "";
  const [ct, cid] = parseCursor(cursor);
  const { results } = await env.DB.prepare(
    `SELECT p.id, p.client_photo_id, p.filename, p.taken_at, p.width, p.height, p.revision, p.ready_revision, p.full_size, p.created_at,
      (SELECT COUNT(*) FROM favorites f WHERE f.photo_id = p.id AND f.visitor_id <> ?) AS favorites
     FROM photos p
     WHERE p.gallery_id = ? AND (? = '' OR p.taken_at < ? OR (p.taken_at = ? AND p.id < ?))
     ORDER BY p.taken_at DESC, p.id DESC LIMIT ?`
  ).bind(ADMIN_VISITOR, g.id, cursor, ct, ct, cid, limit + 1).all();
  const more = results.length > limit;
  const rows = more ? results.slice(0, limit) : results;
  return json({
    photos: rows.map((p) => ({
      id: p.id,
      clientPhotoId: p.client_photo_id,
      name: p.filename,
      takenAt: p.taken_at,
      w: p.width,
      h: p.height,
      revision: p.revision,
      v: p.ready_revision,
      ready: p.ready_revision != null,
      size: p.full_size,
      favorites: p.favorites,
    })),
    next: more ? cursorOf(rows[rows.length - 1]) : null,
  });
}

async function adminFavorites(env, galleryId) {
  const g = await galleryById(env, galleryId);
  const { results } = await env.DB.prepare(
    `SELECT f.photo_id, p.filename, p.ready_revision AS v, p.width AS w, p.height AS h, f.visitor_id, v.name
     FROM favorites f JOIN photos p ON p.id = f.photo_id
     LEFT JOIN visitors v ON v.gallery_id = f.gallery_id AND v.id = f.visitor_id
     WHERE f.gallery_id = ? AND f.visitor_id <> ?
     ORDER BY f.created_at`
  ).bind(g.id, ADMIN_VISITOR).all();
  const byPhoto = new Map();
  for (const r of results) {
    let item = byPhoto.get(r.photo_id);
    if (!item) {
      item = { photoId: r.photo_id, name: r.filename, v: r.v, w: r.w, h: r.h, count: 0, visitors: [] };
      byPhoto.set(r.photo_id, item);
    }
    item.count++;
    item.visitors.push(r.name || "Convidado sem nome");
  }
  const photos = [...byPhoto.values()].sort((a, b) => b.count - a.count);
  return json({ photos });
}

async function previewSession(env, url, galleryId) {
  const g = await galleryById(env, galleryId);
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO visitors (gallery_id, id, name, created_at, last_seen) VALUES (?, ?, 'Fotógrafo', ?, ?)
     ON CONFLICT (gallery_id, id) DO UPDATE SET last_seen = excluded.last_seen`
  ).bind(g.id, ADMIN_VISITOR, now, now).run();
  const cookie = await sessionCookie(env, url, g, ADMIN_VISITOR, "a", 1);
  return json({ slug: g.slug }, 200, { "set-cookie": cookie });
}

async function registerPhoto(request, env, url) {
  const body = await readJson(request);
  const g = await galleryById(env, String(body.galleryId || ""));
  const clientPhotoId = cleanText(String(body.clientPhotoId || ""), 200);
  if (!clientPhotoId) throw new HttpError(400, "clientPhotoId obrigatório.");
  const revision = Number(body.revision);
  if (!Number.isSafeInteger(revision) || revision < 0) throw new HttpError(400, "Revisão inválida.");
  const filename = cleanFilename(body.filename);
  const takenAt = Number.isSafeInteger(Number(body.takenAt)) && Number(body.takenAt) > 0 ? Number(body.takenAt) : Date.now();
  const width = clampInt(body.width, 0, 100000, 0);
  const height = clampInt(body.height, 0, 100000, 0);
  const variants = body.variants || {};
  for (const name of REQUIRED_VARIANTS) {
    const v = variants[name];
    if (!v || !Number.isSafeInteger(v.size) || v.size <= 0 || v.size > VARIANTS[name]) throw new HttpError(400, `Variante ${name}: tamanho inválido.`);
    if (typeof v.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(v.sha256)) throw new HttpError(400, `Variante ${name}: sha256 inválido.`);
  }

  const now = Date.now();
  let photo = await env.DB.prepare("SELECT * FROM photos WHERE gallery_id = ? AND client_photo_id = ?").bind(g.id, clientPhotoId).first();
  if (photo && revision < photo.revision) throw new HttpError(409, "Já existe uma revisão mais nova desta foto.");
  if (!photo) {
    const id = rid(16);
    await env.DB.prepare(
      `INSERT INTO photos (id, gallery_id, client_photo_id, filename, taken_at, width, height, revision, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (gallery_id, client_photo_id) DO NOTHING`
    ).bind(id, g.id, clientPhotoId, filename, takenAt, width, height, revision, now, now).run();
    photo = await env.DB.prepare("SELECT * FROM photos WHERE gallery_id = ? AND client_photo_id = ?").bind(g.id, clientPhotoId).first();
  } else if (revision > photo.revision || photo.ready_revision == null) {
    await env.DB.prepare("UPDATE photos SET revision = ?, filename = ?, taken_at = ?, width = ?, height = ?, updated_at = ? WHERE id = ?")
      .bind(revision, filename, takenAt, width, height, now, photo.id).run();
  }

  await env.DB.batch(
    REQUIRED_VARIANTS.map((name) =>
      env.DB.prepare(
        `INSERT INTO photo_variants (photo_id, revision, variant, size, sha256, uploaded) VALUES (?, ?, ?, ?, ?, 0)
         ON CONFLICT (photo_id, revision, variant) DO UPDATE SET
           uploaded = CASE WHEN photo_variants.sha256 = excluded.sha256 AND photo_variants.size = excluded.size THEN photo_variants.uploaded ELSE 0 END,
           size = excluded.size, sha256 = excluded.sha256`
      ).bind(photo.id, revision, name, variants[name].size, variants[name].sha256)
    )
  );
  const { results } = await env.DB.prepare("SELECT variant, uploaded FROM photo_variants WHERE photo_id = ? AND revision = ?").bind(photo.id, revision).all();
  const done = new Map(results.map((r) => [r.variant, !!r.uploaded]));
  return json({
    photoId: photo.id,
    revision,
    ready: photo.ready_revision === revision && REQUIRED_VARIANTS.every((n) => done.get(n)),
    uploads: REQUIRED_VARIANTS.map((name) => ({ variant: name, url: `/v1/photos/${photo.id}/r/${revision}/${name}`, done: !!done.get(name) })),
    completeUrl: `/v1/photos/${photo.id}/complete`,
  });
}

async function uploadVariant(request, env, photoId, revision, variant) {
  if (!VARIANTS[variant]) throw new HttpError(400, "Variante inválida.");
  const v = await env.DB.prepare(
    `SELECT pv.size, pv.sha256, pv.uploaded, p.gallery_id FROM photo_variants pv JOIN photos p ON p.id = pv.photo_id
     WHERE pv.photo_id = ? AND pv.revision = ? AND pv.variant = ?`
  ).bind(photoId, revision, variant).first();
  if (!v) throw new HttpError(404, "Envio não registrado. Chame POST /v1/photos antes.");
  if (v.uploaded) return json({ ok: true, already: true });
  const length = Number(request.headers.get("content-length") || "0");
  if (length !== v.size) throw new HttpError(400, `Tamanho enviado (${length}) diferente do registrado (${v.size}).`);
  if (!request.body) throw new HttpError(400, "Corpo vazio.");
  const key = objectKey(v.gallery_id, photoId, revision, variant);
  let stored;
  try {
    stored = await env.PHOTOS.put(key, request.body, { sha256: v.sha256, httpMetadata: { contentType: "image/jpeg" } });
  } catch (error) {
    throw new HttpError(422, "Arquivo corrompido no envio (checksum não confere). Tente novamente.");
  }
  if (!stored || stored.size !== v.size) {
    await env.PHOTOS.delete(key);
    throw new HttpError(422, "Arquivo incompleto. Tente novamente.");
  }
  await env.DB.prepare("UPDATE photo_variants SET uploaded = 1 WHERE photo_id = ? AND revision = ? AND variant = ?").bind(photoId, revision, variant).run();
  return json({ ok: true });
}

async function completePhoto(request, env, ctx, photoId) {
  const body = await readJson(request);
  const revision = Number(body.revision);
  const photo = await env.DB.prepare("SELECT * FROM photos WHERE id = ?").bind(photoId).first();
  if (!photo) throw new HttpError(404, "Foto não encontrada.");
  if (photo.ready_revision === revision) return json({ photoId, revision, ready: true });
  if (photo.revision !== revision) throw new HttpError(409, "Uma revisão mais nova desta foto foi registrada.");
  const { results } = await env.DB.prepare("SELECT variant, size, uploaded FROM photo_variants WHERE photo_id = ? AND revision = ?").bind(photoId, revision).all();
  const byName = new Map(results.map((r) => [r.variant, r]));
  const missing = REQUIRED_VARIANTS.filter((n) => !byName.get(n) || !byName.get(n).uploaded);
  if (missing.length) throw new HttpError(409, "Faltam variantes: " + missing.join(", "));
  const now = Date.now();
  await env.DB.prepare("UPDATE photos SET ready_revision = ?, full_size = ?, first_ready_at = COALESCE(first_ready_at, ?), updated_at = ? WHERE id = ?")
    .bind(revision, byName.get("full").size, now, now, photoId).run();
  const old = photo.ready_revision;
  if (old != null && old !== revision) {
    ctx.waitUntil(
      Promise.all([
        deletePrefix(env, `p/${photo.gallery_id}/${photoId}/${old}/`),
        env.DB.prepare("DELETE FROM photo_variants WHERE photo_id = ? AND revision < ?").bind(photoId, revision).run(),
      ])
    );
  }
  return json({ photoId, revision, ready: true });
}

async function deletePhoto(env, photoId) {
  const photo = await env.DB.prepare("SELECT * FROM photos WHERE id = ?").bind(photoId).first();
  if (!photo) return json({ ok: true, missing: true });
  await deletePrefix(env, `p/${photo.gallery_id}/${photoId}/`);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM favorites WHERE photo_id = ?").bind(photoId),
    env.DB.prepare("DELETE FROM photo_variants WHERE photo_id = ?").bind(photoId),
    env.DB.prepare("DELETE FROM photos WHERE id = ?").bind(photoId),
  ]);
  return json({ ok: true });
}

// ---------------------------------------------------------------- convidado

async function guestRoute(request, env, url, slug, rest, method) {
  let m;
  if (rest === "/" && method === "GET") {
    const g = await galleryBySlug(env, slug, false);
    if (!g) throw new HttpError(404, "Galeria não encontrada.");
    const session = await readSession(request, env, g);
    if (!session || session.role !== "a") assertOpen(g);
    const count = await env.DB.prepare("SELECT COUNT(*) AS n FROM photos WHERE gallery_id = ? AND ready_revision IS NOT NULL").bind(g.id).first();
    return json({
      title: g.title,
      studio: studio(env),
      needsPin: !!g.pin_hash,
      allowDownload: !!g.allow_download,
      order: g.sort_order,
      photoCount: count.n,
      session: session ? { visitorId: session.visitorId, role: session.role } : null,
    });
  }
  if (rest === "/session" && method === "POST") return createSession(request, env, url, slug);

  const { g, session } = await guestAuth(request, env, slug);
  if (rest === "/photos" && method === "GET") return guestPhotos(env, url, g);
  if (rest === "/updates" && method === "GET") {
    const since = clampInt(url.searchParams.get("since"), 0, Number.MAX_SAFE_INTEGER, 0);
    const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM photos WHERE gallery_id = ? AND ready_revision IS NOT NULL AND first_ready_at > ?").bind(g.id, since).first();
    return json({ count: row.n, now: Date.now() });
  }
  if (rest === "/favorites" && method === "GET") {
    const { results } = await env.DB.prepare(
      "SELECT f.photo_id FROM favorites f JOIN photos p ON p.id = f.photo_id WHERE f.gallery_id = ? AND f.visitor_id = ? AND p.ready_revision IS NOT NULL ORDER BY f.created_at"
    ).bind(g.id, session.visitorId).all();
    return json({ ids: results.map((r) => r.photo_id) });
  }
  if ((m = rest.match(/^\/fav\/([a-z0-9]+)$/)) && (method === "PUT" || method === "DELETE")) {
    if (method === "PUT") {
      const photo = await env.DB.prepare("SELECT id FROM photos WHERE id = ? AND gallery_id = ? AND ready_revision IS NOT NULL").bind(m[1], g.id).first();
      if (!photo) throw new HttpError(404, "Foto não encontrada.");
      await env.DB.prepare("INSERT INTO favorites (gallery_id, photo_id, visitor_id, created_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")
        .bind(g.id, m[1], session.visitorId, Date.now()).run();
    } else {
      await env.DB.prepare("DELETE FROM favorites WHERE gallery_id = ? AND photo_id = ? AND visitor_id = ?").bind(g.id, m[1], session.visitorId).run();
    }
    return json({ ok: true });
  }
  if (rest === "/name" && method === "POST") {
    const body = await readJson(request);
    const name = cleanText(body.name, 60) || null;
    await env.DB.prepare("UPDATE visitors SET name = ? WHERE gallery_id = ? AND id = ?").bind(name, g.id, session.visitorId).run();
    return json({ ok: true, name });
  }
  if ((m = rest.match(/^\/p\/([a-z0-9]+)\/(\d+)\/([a-z]+)$/)) && (method === "GET" || method === "HEAD")) return guestImage(request, env, url, g, session, m[1], Number(m[2]), m[3]);
  throw new HttpError(404, "Rota não encontrada.");
}

async function createSession(request, env, url, slug) {
  const g = await galleryBySlug(env, slug, false);
  if (!g) throw new HttpError(404, "Galeria não encontrada.");
  assertOpen(g);
  const body = await readJson(request);
  const ip = request.headers.get("cf-connecting-ip") || "local";
  const now = Date.now();
  if (g.pin_hash) {
    const fail = await env.DB.prepare("SELECT window_start, count FROM pin_failures WHERE gallery_id = ? AND ip = ?").bind(g.id, ip).first();
    if (fail && now - fail.window_start < PIN_WINDOW_MS && fail.count >= PIN_MAX_FAILURES) throw new HttpError(429, "Muitas tentativas. Aguarde 15 minutos e tente de novo.");
    const pin = String(body.pin ?? "").trim();
    if (!pin || !(await checkPin(pin, g.pin_hash))) {
      if (fail && now - fail.window_start < PIN_WINDOW_MS) {
        await env.DB.prepare("UPDATE pin_failures SET count = count + 1 WHERE gallery_id = ? AND ip = ?").bind(g.id, ip).run();
      } else {
        await env.DB.prepare("INSERT INTO pin_failures (gallery_id, ip, window_start, count) VALUES (?, ?, ?, 1) ON CONFLICT (gallery_id, ip) DO UPDATE SET window_start = excluded.window_start, count = 1")
          .bind(g.id, ip, now).run();
      }
      throw new HttpError(401, "Senha incorreta.");
    }
    if (fail) await env.DB.prepare("DELETE FROM pin_failures WHERE gallery_id = ? AND ip = ?").bind(g.id, ip).run();
  }
  const visitorId = typeof body.visitorId === "string" && /^[a-z0-9]{16,32}$/.test(body.visitorId) && body.visitorId !== ADMIN_VISITOR ? body.visitorId : rid(20);
  const name = cleanText(body.name, 60) || null;
  await env.DB.prepare(
    `INSERT INTO visitors (gallery_id, id, name, created_at, last_seen) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (gallery_id, id) DO UPDATE SET name = COALESCE(excluded.name, visitors.name), last_seen = excluded.last_seen`
  ).bind(g.id, visitorId, name, now, now).run();
  const cookie = await sessionCookie(env, url, g, visitorId, "g", SESSION_DAYS);
  return json({ visitorId, name }, 200, { "set-cookie": cookie });
}

async function guestAuth(request, env, slug) {
  const g = await galleryBySlug(env, slug, true);
  if (!g) throw new HttpError(404, "Galeria não encontrada.");
  const session = await readSession(request, env, g);
  if (!session) throw new HttpError(401, "Sessão expirada. Entre novamente.");
  if (session.role !== "a") assertOpen(g);
  return { g, session };
}

async function guestPhotos(env, url, g) {
  const limit = clampInt(url.searchParams.get("limit"), 1, 500, 300);
  const order = url.searchParams.get("order") === "asc" || (url.searchParams.get("order") !== "desc" && g.sort_order === "asc") ? "asc" : "desc";
  const cursor = url.searchParams.get("cursor") || "";
  const [ct, cid] = parseCursor(cursor);
  const cmp = order === "asc" ? ">" : "<";
  const dir = order === "asc" ? "ASC" : "DESC";
  const { results } = await env.DB.prepare(
    `SELECT id, ready_revision AS v, width AS w, height AS h, filename AS name, taken_at AS t FROM photos
     WHERE gallery_id = ? AND ready_revision IS NOT NULL AND (? = '' OR taken_at ${cmp} ? OR (taken_at = ? AND id ${cmp} ?))
     ORDER BY taken_at ${dir}, id ${dir} LIMIT ?`
  ).bind(g.id, cursor, ct, ct, cid, limit + 1).all();
  const more = results.length > limit;
  const rows = more ? results.slice(0, limit) : results;
  return json({ photos: rows, next: more ? cursorOf({ taken_at: rows[rows.length - 1].t, id: rows[rows.length - 1].id }) : null, order, now: Date.now() });
}

async function guestImage(request, env, url, g, session, photoId, revision, variant) {
  if (!VARIANTS[variant]) throw new HttpError(404, "Variante inválida.");
  if (variant === "full" && !g.allow_download && session.role !== "a") throw new HttpError(403, "O fotógrafo desativou o download desta galeria.");
  const key = objectKey(g.id, photoId, revision, variant);
  const obj = await env.PHOTOS.get(key, { onlyIf: request.headers });
  if (!obj) throw new HttpError(404, "Foto não encontrada.");
  const headers = new Headers({
    "content-type": "image/jpeg",
    etag: obj.httpEtag,
    "cache-control": "private, max-age=31536000, immutable",
    "x-content-type-options": "nosniff",
  });
  if (!("body" in obj)) return new Response(null, { status: 304, headers });
  if (url.searchParams.get("dl") === "1") {
    const name = cleanFilename(url.searchParams.get("n") || "foto.jpg");
    headers.set("content-disposition", `attachment; filename="${name.replace(/[^\x20-\x7e]/g, "_")}"; filename*=UTF-8''${encodeURIComponent(name)}`);
  }
  headers.set("content-length", String(obj.size));
  return new Response(request.method === "HEAD" ? null : obj.body, { status: 200, headers });
}

function assertOpen(g) {
  if (g.status !== "active") throw new HttpError(404, "Galeria não encontrada.");
  if (g.expires_at && g.expires_at < Date.now()) throw new HttpError(410, "Esta galeria expirou. Fale com o fotógrafo.");
}

// ---------------------------------------------------------------- sessão

async function sessionCookie(env, url, g, visitorId, role, days) {
  const exp = Date.now() + days * 86400000;
  const payload = `${g.id}.${visitorId}.${exp}.${role}`;
  const sig = await sign(env, payload + "." + (g.pin_hash || ""));
  const secure = url.protocol === "https:" ? "; Secure" : "";
  return `lg_${g.slug}=${payload}.${sig}; Path=/api/g/${g.slug}; Max-Age=${days * 86400}; HttpOnly; SameSite=Lax${secure}`;
}

async function readSession(request, env, g) {
  const raw = cookie(request, `lg_${g.slug}`);
  if (!raw) return null;
  const parts = raw.split(".");
  if (parts.length !== 5) return null;
  const [gid, visitorId, exp, role, sig] = parts;
  if (gid !== g.id || Number(exp) < Date.now() || !["g", "a"].includes(role)) return null;
  if (!(await verify(env, `${gid}.${visitorId}.${exp}.${role}.${g.pin_hash || ""}`, sig))) return null;
  return { visitorId, role };
}

async function hmacKey(env) {
  const secret = env.SESSION_SECRET || "lumo-session:" + (env.ADMIN_TOKEN || "");
  return crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

async function sign(env, message) {
  const sig = await crypto.subtle.sign("HMAC", await hmacKey(env), encoder.encode(message));
  return b64url(new Uint8Array(sig));
}

async function verify(env, message, sig) {
  let bytes;
  try {
    bytes = fromB64url(sig);
  } catch {
    return false;
  }
  return crypto.subtle.verify("HMAC", await hmacKey(env), bytes, encoder.encode(message));
}

function cookie(request, name) {
  const header = request.headers.get("cookie") || "";
  for (const part of header.split(";")) {
    const i = part.indexOf("=");
    if (i > 0 && part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
  }
  return null;
}

// ---------------------------------------------------------------- utilitários

const gallerySlugCache = new Map();

async function galleryBySlug(env, slug, cached) {
  const hit = gallerySlugCache.get(slug);
  if (cached && hit && Date.now() - hit.at < 15000) return hit.row;
  const row = await env.DB.prepare("SELECT * FROM galleries WHERE slug = ?").bind(slug).first();
  if (row) gallerySlugCache.set(slug, { row, at: Date.now() });
  else gallerySlugCache.delete(slug);
  if (gallerySlugCache.size > 500) gallerySlugCache.clear();
  return row;
}

async function galleryById(env, id) {
  const g = await env.DB.prepare("SELECT * FROM galleries WHERE id = ?").bind(id).first();
  if (!g) throw new HttpError(404, "Galeria não encontrada.");
  return g;
}

async function deletePrefix(env, prefix) {
  let cursor;
  do {
    const list = await env.PHOTOS.list({ prefix, cursor, limit: 1000 });
    const keys = list.objects.map((o) => o.key);
    if (keys.length) await env.PHOTOS.delete(keys);
    cursor = list.truncated ? list.cursor : undefined;
  } while (cursor);
}

function objectKey(galleryId, photoId, revision, variant) {
  return `p/${galleryId}/${photoId}/${revision}/${variant}.jpg`;
}

function studio(env) {
  return cleanText(env.STUDIO_NAME || "Lumo", 80) || "Lumo";
}

function publicOrigin(env, url) {
  return (env.PUBLIC_URL || url.origin).replace(/\/+$/, "");
}

function json(data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extra },
  });
}

async function readJson(request) {
  const text = await request.text();
  if (!text) return {};
  if (text.length > 64 * 1024) throw new HttpError(413, "Requisição grande demais.");
  try {
    const value = JSON.parse(text);
    return value && typeof value === "object" ? value : {};
  } catch {
    throw new HttpError(400, "JSON inválido.");
  }
}

function rid(length) {
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  let out = "";
  for (const b of bytes) out += ALPHABET[b & 31];
  return out;
}

function cleanText(value, max) {
  if (typeof value !== "string") return "";
  return value.replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim().slice(0, max);
}

function cleanFilename(value) {
  const base = String(value || "").split(/[\\/]/).pop();
  const name = cleanText(base, 160).replace(/["<>:|?*]/g, "_");
  return name || "foto.jpg";
}

function validPin(pin) {
  const value = String(pin).trim();
  if (!/^[0-9A-Za-z]{4,32}$/.test(value)) throw new HttpError(400, "A senha deve ter de 4 a 32 letras ou números.");
  return value;
}

async function hashPin(pin) {
  const salt = rid(16);
  return salt + "$" + (await sha256Hex(salt + ":" + pin));
}

async function checkPin(pin, stored) {
  const [salt, hash] = stored.split("$");
  return sameSecret(await sha256Hex(salt + ":" + pin), hash);
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function sameSecret(a, b) {
  const [x, y] = await Promise.all([crypto.subtle.digest("SHA-256", encoder.encode(a)), crypto.subtle.digest("SHA-256", encoder.encode(b))]);
  const u = new Uint8Array(x);
  const v = new Uint8Array(y);
  let diff = 0;
  for (let i = 0; i < u.length; i++) diff |= u[i] ^ v[i];
  return diff === 0;
}

function b64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromB64url(text) {
  const s = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(s, (c) => c.charCodeAt(0));
}

function clampInt(value, min, max, fallback) {
  if (value === null || value === undefined || value === "") return fallback;
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(n)));
}

function optionalTime(value) {
  if (value === null || value === undefined || value === "") return null;
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n <= 0) throw new HttpError(400, "Data de expiração inválida.");
  return n;
}

function parseCursor(cursor) {
  if (!cursor) return [0, ""];
  const i = cursor.indexOf("_");
  const t = Number(cursor.slice(0, i));
  const id = cursor.slice(i + 1);
  if (i < 0 || !Number.isSafeInteger(t) || !/^[a-z0-9]+$/.test(id)) throw new HttpError(400, "Cursor inválido.");
  return [t, id];
}

function cursorOf(row) {
  return `${row.taken_at}_${row.id}`;
}
