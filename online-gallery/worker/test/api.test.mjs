// Teste de ponta a ponta da API: sobe `wrangler dev` local (R2 e D1 simulados) e exercita
// o fluxo do fotógrafo (criar galeria, enviar variantes, concluir) e do convidado (senha,
// listar, baixar, favoritar), além de revisões, checksum, limite de tentativas e exclusão.
// Uso: npm test  (ou LUMO_GALLERY_URL=http://127.0.0.1:8787 para usar um servidor já aberto)

import { spawn, execFileSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TOKEN = "teste-" + randomBytes(12).toString("hex");
const PORT = 8800 + Math.floor(Math.random() * 100);
let BASE = process.env.LUMO_GALLERY_URL || `http://127.0.0.1:${PORT}`;
let server;
let persist;

const results = [];
function check(cond, label) {
  results.push([!!cond, label]);
  if (!cond) throw new Error("FALHOU: " + label);
}

async function startServer() {
  persist = mkdtempSync(join(tmpdir(), "lumo-galeria-"));
  writeFileSync(join(ROOT, ".dev.vars"), `ADMIN_TOKEN=${TOKEN}\n`);
  const wrangler = join(ROOT, "node_modules/.bin/wrangler");
  execFileSync(wrangler, ["d1", "migrations", "apply", "lumo-galeria", "--local", "--persist-to", persist], { cwd: ROOT, stdio: "pipe", env: { ...process.env, CI: "1" } });
  server = spawn(wrangler, ["dev", "--local", "--ip", "127.0.0.1", "--port", String(PORT), "--persist-to", persist, "--show-interactive-dev-session=false"], {
    cwd: ROOT,
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, CI: "1" },
  });
  let log = "";
  server.stdout.on("data", (d) => (log += d));
  server.stderr.on("data", (d) => (log += d));
  for (let i = 0; i < 120; i++) {
    try {
      const r = await fetch(BASE + "/v1/me");
      if (r.status === 401) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("wrangler dev não respondeu:\n" + log);
}

function stopServer() {
  if (server) server.kill("SIGTERM");
  try {
    rmSync(join(ROOT, ".dev.vars"));
  } catch {}
  if (persist) rmSync(persist, { recursive: true, force: true });
}

const admin = (path, init = {}) => fetch(BASE + path, { ...init, headers: { authorization: "Bearer " + TOKEN, "content-type": "application/json", ...(init.headers || {}) } });
const guest = (jar, path, init = {}) => fetch(BASE + path, { ...init, headers: { ...(jar.cookie ? { cookie: jar.cookie } : {}), "content-type": "application/json", ...(init.headers || {}) } });
const sha = (buf) => createHash("sha256").update(buf).digest("hex");

function takeCookie(res, jar) {
  const set = res.headers.get("set-cookie");
  if (set) jar.cookie = set.split(";")[0];
}

async function sendPhoto(galleryId, clientPhotoId, revision, takenAt) {
  const files = { thumb: randomBytes(3000), preview: randomBytes(20000), full: randomBytes(90000) };
  const variants = Object.fromEntries(Object.entries(files).map(([k, b]) => [k, { size: b.length, sha256: sha(b) }]));
  const reg = await admin("/v1/photos", { method: "POST", body: JSON.stringify({ galleryId, clientPhotoId, revision, filename: `IMG_${clientPhotoId}_Editada.jpg`, takenAt, width: 3000, height: 2000, variants }) });
  const plan = await reg.json();
  check(reg.status === 200 && plan.photoId, "registrar foto " + clientPhotoId);
  for (const up of plan.uploads) {
    if (up.done) continue;
    const r = await admin(up.url, { method: "PUT", body: files[up.variant], headers: { "content-type": "image/jpeg" } });
    check(r.status === 200, `enviar variante ${up.variant} (${r.status} ${await r.clone().text()})`);
  }
  const done = await admin(plan.completeUrl, { method: "POST", body: JSON.stringify({ revision }) });
  check(done.status === 200 && (await done.json()).ready, "concluir foto " + clientPhotoId);
  return { plan, files };
}

async function main() {
  if (!process.env.LUMO_GALLERY_URL) await startServer();

  check((await fetch(BASE + "/v1/me")).status === 401, "API do fotógrafo exige chave");
  check((await fetch(BASE + "/v1/me", { headers: { authorization: "Bearer errado" } })).status === 401, "chave errada é recusada");
  check((await admin("/v1/me")).status === 200, "chave correta é aceita");

  const created = await admin("/v1/galleries", { method: "POST", body: JSON.stringify({ title: "Casamento Ana & Leo", clientKey: "evento-1", pin: "2468" }) });
  const { gallery } = await created.json();
  check(created.status === 201 && gallery.slug && gallery.hasPin, "criar galeria com senha");
  const again = await (await admin("/v1/galleries", { method: "POST", body: JSON.stringify({ title: "Outro nome", clientKey: "evento-1" }) })).json();
  check(again.gallery.id === gallery.id && again.created === false, "mesmo evento do app reaproveita a galeria");

  const t0 = Date.now() - 3600_000;
  const first = await sendPhoto(gallery.id, "job-1", 1000, t0);
  await sendPhoto(gallery.id, "job-2", 1000, t0 + 1000);
  await sendPhoto(gallery.id, "job-3", 1000, t0 + 2000);

  const repeat = await (await admin("/v1/photos", { method: "POST", body: JSON.stringify({ galleryId: gallery.id, clientPhotoId: "job-1", revision: 1000, filename: "x.jpg", variants: Object.fromEntries(Object.entries(first.files).map(([k, b]) => [k, { size: b.length, sha256: sha(b) }])) }) })).json();
  check(repeat.ready && repeat.uploads.every((u) => u.done), "reenvio da mesma revisão é reconhecido sem novo upload");

  // checksum e tamanho
  const bad = randomBytes(5000);
  const regBad = await (await admin("/v1/photos", { method: "POST", body: JSON.stringify({ galleryId: gallery.id, clientPhotoId: "job-bad", revision: 1, filename: "bad.jpg", variants: { thumb: { size: 5000, sha256: sha(bad) }, preview: { size: 5000, sha256: sha(bad) }, full: { size: 5000, sha256: sha(bad) } } }) })).json();
  const corrupted = Buffer.from(bad);
  corrupted[10] ^= 0xff;
  check((await admin(regBad.uploads[0].url, { method: "PUT", body: corrupted })).status === 422, "arquivo corrompido é recusado pelo checksum");
  check((await admin(regBad.uploads[0].url, { method: "PUT", body: bad.subarray(0, 100) })).status === 400, "tamanho diferente é recusado");
  check((await admin(regBad.completeUrl, { method: "POST", body: JSON.stringify({ revision: 1 }) })).status === 409, "não conclui com variantes faltando");

  // convidado
  const jar = {};
  const info = await (await fetch(BASE + `/api/g/${gallery.slug}`)).json();
  check(info.needsPin && info.title === "Casamento Ana & Leo" && info.photoCount === 3, "convidado vê título e pedido de senha (fotos incompletas ficam ocultas)");
  check((await guest(jar, `/api/g/${gallery.slug}/photos`)).status === 401, "sem sessão não lista fotos");
  const wrong = await guest(jar, `/api/g/${gallery.slug}/session`, { method: "POST", body: JSON.stringify({ pin: "0000" }) });
  check(wrong.status === 401, "senha errada é recusada");
  const ok = await guest(jar, `/api/g/${gallery.slug}/session`, { method: "POST", body: JSON.stringify({ pin: "2468", name: "Maria" }) });
  takeCookie(ok, jar);
  check(ok.status === 200 && jar.cookie && ok.headers.get("set-cookie").includes("HttpOnly"), "senha certa cria sessão HttpOnly");

  const list = await (await guest(jar, `/api/g/${gallery.slug}/photos`)).json();
  check(list.photos.length === 3 && list.photos[0].name === "IMG_job-3_Editada.jpg", "lista em ordem de mais recentes");
  const asc = await (await guest(jar, `/api/g/${gallery.slug}/photos?order=asc&limit=2`)).json();
  check(asc.photos.length === 2 && asc.photos[0].name === "IMG_job-1_Editada.jpg" && asc.next, "ordem do evento com paginação");
  const asc2 = await (await guest(jar, `/api/g/${gallery.slug}/photos?order=asc&limit=2&cursor=${asc.next}`)).json();
  check(asc2.photos.length === 1 && !asc2.next, "segunda página termina a lista");

  const p1 = list.photos.find((p) => p.name.includes("job-1"));
  const thumb = Buffer.from(await (await guest(jar, `/api/g/${gallery.slug}/p/${p1.id}/${p1.v}/thumb`)).arrayBuffer());
  check(thumb.equals(first.files.thumb), "miniatura servida é idêntica à enviada");
  const dl = await guest(jar, `/api/g/${gallery.slug}/p/${p1.id}/${p1.v}/full?dl=1&n=${encodeURIComponent(p1.name)}`);
  check(dl.status === 200 && (dl.headers.get("content-disposition") || "").includes("attachment") && Buffer.from(await dl.arrayBuffer()).equals(first.files.full), "download da foto completa");
  const etag = dl.headers.get("etag");
  check((await guest(jar, `/api/g/${gallery.slug}/p/${p1.id}/${p1.v}/thumb`, { headers: { "if-none-match": etag } })).status !== 500, "cabeçalho de cache não quebra");
  check((await fetch(BASE + `/api/g/${gallery.slug}/p/${p1.id}/${p1.v}/thumb`)).status === 401, "imagem exige sessão");

  check((await guest(jar, `/api/g/${gallery.slug}/fav/${p1.id}`, { method: "PUT" })).status === 200, "favoritar");
  check((await guest(jar, `/api/g/${gallery.slug}/fav/${p1.id}`, { method: "PUT" })).status === 200, "favoritar de novo é idempotente");
  const favs = await (await guest(jar, `/api/g/${gallery.slug}/favorites`)).json();
  check(favs.ids.length === 1 && favs.ids[0] === p1.id, "lista de favoritas do convidado");
  const adminFavs = await (await admin(`/v1/galleries/${gallery.id}/favorites`)).json();
  check(adminFavs.photos[0].count === 1 && adminFavs.photos[0].visitors[0] === "Maria", "fotógrafo vê quem favoritou");

  const since = list.now;
  await sendPhoto(gallery.id, "job-4", 1000, t0 + 3000);
  const upd = await (await guest(jar, `/api/g/${gallery.slug}/updates?since=${since}`)).json();
  check(upd.count === 1, "aviso de fotos novas durante o evento");

  // nova revisão (foto reeditada no app)
  await sendPhoto(gallery.id, "job-1", 2000, t0);
  const list2 = await (await guest(jar, `/api/g/${gallery.slug}/photos`)).json();
  const p1b = list2.photos.find((p) => p.id === p1.id);
  check(p1b.v === 2000, "reedição substitui a foto mantendo o mesmo ID e os favoritos");
  await new Promise((r) => setTimeout(r, 300));
  check((await guest(jar, `/api/g/${gallery.slug}/p/${p1.id}/1000/thumb`)).status === 404, "revisão antiga é apagada do armazenamento");
  check((await admin("/v1/photos", { method: "POST", body: JSON.stringify({ galleryId: gallery.id, clientPhotoId: "job-1", revision: 1500, filename: "x.jpg", variants: repeat.uploads && Object.fromEntries(Object.entries(first.files).map(([k, b]) => [k, { size: b.length, sha256: sha(b) }])) }) })).status === 409, "revisão mais antiga é recusada");

  // download desativado
  await admin(`/v1/galleries/${gallery.id}`, { method: "PATCH", body: JSON.stringify({ allowDownload: false }) });
  await new Promise((r) => setTimeout(r, 16000)); // cache de 15 s por instância
  check((await guest(jar, `/api/g/${gallery.slug}/p/${p1.id}/2000/full`)).status === 403, "download desativado bloqueia a foto completa");
  check((await guest(jar, `/api/g/${gallery.slug}/p/${p1.id}/2000/preview`)).status === 200, "visualização continua com download desativado");

  // prévia do fotógrafo (sessão de administrador)
  const pjar = {};
  const prev = await admin(`/v1/galleries/${gallery.id}/preview-session`, { method: "POST" });
  takeCookie(prev, pjar);
  check((await guest(pjar, `/api/g/${gallery.slug}/p/${p1.id}/2000/full`)).status === 200, "fotógrafo vê a foto completa mesmo sem download liberado");

  // trocar a senha encerra sessões
  await admin(`/v1/galleries/${gallery.id}`, { method: "PATCH", body: JSON.stringify({ pin: "1357" }) });
  await new Promise((r) => setTimeout(r, 16000));
  check((await guest(jar, `/api/g/${gallery.slug}/photos`)).status === 401, "trocar a senha encerra as sessões antigas");

  // limite de tentativas
  let last = 0;
  for (let i = 0; i < 9; i++) last = (await guest({}, `/api/g/${gallery.slug}/session`, { method: "POST", body: JSON.stringify({ pin: "9999" }) })).status;
  check(last === 429, "muitas senhas erradas bloqueiam por 15 minutos");

  // galeria sem senha
  const openG = (await (await admin("/v1/galleries", { method: "POST", body: JSON.stringify({ title: "Formatura" }) })).json()).gallery;
  const ojar = {};
  const os = await guest(ojar, `/api/g/${openG.slug}/session`, { method: "POST", body: JSON.stringify({}) });
  takeCookie(os, ojar);
  check(os.status === 200 && (await (await guest(ojar, `/api/g/${openG.slug}/photos`)).json()).photos.length === 0, "galeria sem senha abre direto");
  check((await guest(ojar, `/api/g/${gallery.slug}/photos`)).status === 401, "sessão de uma galeria não abre outra");

  // listagem do fotógrafo e exclusões
  const all = await (await admin("/v1/galleries")).json();
  const mine = all.galleries.find((g) => g.id === gallery.id);
  check(mine.readyCount === 4 && mine.photoCount === 5 && mine.favoriteCount === 1 && mine.visitorCount === 1, "resumo do fotógrafo (prontas, total, favoritas, visitantes)");
  check((await admin(`/v1/photos/${p1.id}`, { method: "DELETE" })).status === 200, "excluir foto");
  takeCookie(await admin(`/v1/galleries/${gallery.id}/preview-session`, { method: "POST" }), pjar);
  check((await guest(pjar, `/api/g/${gallery.slug}/p/${p1.id}/2000/thumb`)).status === 404, "foto excluída some do armazenamento");
  check((await admin(`/v1/galleries/${gallery.id}`, { method: "DELETE" })).status === 200, "excluir galeria");
  check((await fetch(BASE + `/api/g/${gallery.slug}`)).status === 404, "galeria excluída deixa de existir");

  check((await fetch(BASE + "/admin")).headers.get("content-type").includes("text/html"), "página do fotógrafo");
  check((await fetch(BASE + `/g/${openG.slug}`)).headers.get("content-type").includes("text/html"), "página do convidado");
}

try {
  await main();
  console.log(results.map(([ok, label]) => (ok ? "  ok  " : "  ERRO ") + label).join("\n"));
  console.log(`PASS: ${results.length} verificações da galeria online.`);
  stopServer();
} catch (error) {
  console.log(results.map(([ok, label]) => (ok ? "  ok  " : "  ERRO ") + label).join("\n"));
  console.error(error);
  stopServer();
  process.exit(1);
}
