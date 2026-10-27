import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pipeline } from 'node:stream/promises';

const PORT = process.env.PORT || 3000;
const PASS = (process.env.ADMIN_PASS || 'admin').trim();
const PUB = path.join(import.meta.dirname, 'public');
const MEDIA = path.join(import.meta.dirname, 'media');
const DB = path.join(import.meta.dirname, 'data.json');
const CAPT = path.join(import.meta.dirname, 'captures');
fs.mkdirSync(MEDIA, { recursive: true }); fs.mkdirSync(CAPT, { recursive: true });
const wantCap = {}; // device -> instante em que o painel pediu uma captura

let db = fs.existsSync(DB) ? JSON.parse(fs.readFileSync(DB, 'utf8')) : { media: [], playlists: [], screens: [], devices: {} };
db.tags ||= [];
const save = () => fs.writeFileSync(DB, JSON.stringify(db));
const seen = {}; // device -> último contato (em memória)

// ---- relatórios de exibição ----
const STATS = path.join(import.meta.dirname, 'stats.json');
const TZ = process.env.STATS_TZ || 'America/Sao_Paulo'; // fuso usado para separar os dias
let st = { days: {}, names: {}, snames: {}, batches: {} }; // days[dia][telaId] = { online: segundos, c: { conteudoId: [segundos, exibições] } }
try { if (fs.existsSync(STATS)) st = { ...st, ...JSON.parse(fs.readFileSync(STATS, 'utf8')) }; } catch (e) { console.error('stats.json ilegível, começando vazio:', e.message); }
const dayFmt = new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' });
const dayOf = ms => dayFmt.format(new Date(ms)); // AAAA-MM-DD no fuso configurado
const cut = dayOf(Date.now() - 800 * 864e5);
for (const d of Object.keys(st.days)) if (d < cut) delete st.days[d]; // guarda até ~2 anos
const bucket = (day, sid) => ((st.days[day] ||= {})[sid] ||= { online: 0, c: {} });
const lastPoll = {}; let sdirty = false;
const flushStats = () => { if (!sdirty) return; try { fs.writeFileSync(STATS + '.tmp', JSON.stringify(st)); fs.renameSync(STATS + '.tmp', STATS); sdirty = false; } catch (e) { console.error('Falha ao salvar stats:', e.message); } };
setInterval(flushStats, 30000).unref();
for (const sg of ['SIGINT', 'SIGTERM']) process.on(sg, () => { flushStats(); process.exit(0); });
const tokens = new Set();
const fails = {}; // ip -> { n, t }: bloqueia quem erra a senha muitas vezes
const ipOf = req => String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
const vt = (arr, scope) => (Array.isArray(arr) ? arr : []).filter(id => db.tags.some(t => t.id === id && t.scope === scope));
const clean = b => { const o = {};
  if ('rotation' in b) o.rotation = [0, 90, 180, 270].includes(+b.rotation) ? +b.rotation : 0;
  if ('fit' in b) o.fit = ['contain', 'cover', 'fill'].includes(b.fit) ? b.fit : 'contain';
  if ('sound' in b) o.sound = !!b.sound;
  if ('tags' in b) o.tags = vt(b.tags, 'screens');
  if ('schedule' in b) { const x = b.schedule; // horário de funcionamento (null = sempre ligada)
    o.schedule = x && /^\d\d:\d\d$/.test(x.start) && /^\d\d:\d\d$/.test(x.end) && Array.isArray(x.days) && x.days.length
      ? { days: [...new Set(x.days.map(Number).filter(d => d >= 0 && d <= 6))], start: x.start, end: x.end } : null; }
  return o; };
const rid = () => crypto.randomBytes(6).toString('hex');
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.gif': 'image/gif', '.webp': 'image/webp', '.mp4': 'video/mp4', '.webm': 'video/webm' };
const json = (res, code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };
const readBody = async req => { const c = []; for await (const d of req) c.push(d); return JSON.parse(Buffer.concat(c).toString() || '{}'); };

function serve(req, res, file) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404); return res.end('Não encontrado'); }
    const type = MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
    const m = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
    if (m) { // suporte a Range (vídeo)
      let start = m[1] ? +m[1] : 0, end = m[2] ? Math.min(+m[2], st.size - 1) : st.size - 1;
      if (!m[1] && m[2]) { start = Math.max(0, st.size - +m[2]); end = st.size - 1; } // "últimos N bytes"
      if (!(start <= end) || start >= st.size) { res.writeHead(416, { 'content-range': `bytes */${st.size}` }); return res.end(); }
      res.writeHead(206, { 'content-type': type, 'accept-ranges': 'bytes', 'content-range': `bytes ${start}-${end}/${st.size}`, 'content-length': end - start + 1 });
      return fs.createReadStream(file, { start, end }).pipe(res);
    }
    res.writeHead(200, { 'content-type': type, 'content-length': st.size, 'accept-ranges': 'bytes', 'cache-control': file.startsWith(MEDIA) ? 'public, max-age=31536000, immutable' : 'no-cache' });
    fs.createReadStream(file).pipe(res);
  });
}

function manifest(dev, plat) {
  let d = db.devices[dev];
  if (!d) {
    let code;
    do code = String(100000 + crypto.randomInt(900000)); while (Object.values(db.devices).some(x => x.code === code));
    for (const k of Object.keys(db.devices)) // limpa códigos de players que nunca foram pareados
      if (!db.screens.some(x => x.device === k) && (seen[k] || 0) < Date.now() - 30 * 60000) delete db.devices[k];
    d = db.devices[dev] = { code }; save();
  }
  seen[dev] = Date.now();
  const s = db.screens.find(s => s.device === dev);
  if (!s) return { paired: false, code: d.code };
  { const now = Date.now(), prev = lastPoll[dev]; lastPoll[dev] = now; st.snames[s.id] = s.name; // horas "online": soma o intervalo entre contatos (máx. 30s cada)
    if (prev) { const g = Math.min((now - prev) / 1000, 30); if (g > 0) { bucket(dayOf(now), s.id).online += g; sdirty = true; } } }
  if (['Android', 'Windows', 'Apple', 'Outro'].includes(plat) && s.platform !== plat) { s.platform = plat; save(); }
  const pl = db.playlists.find(p => p.id === s.playlistId);
  const items = (pl?.items || []).map(i => {
    if (i.kind === 'clock') return { id: 'clock', type: 'clock', seconds: i.seconds };
    const m = db.media.find(x => x.id === i.mediaId);
    return m && { id: m.id, type: m.type, url: '/media/' + m.file, seconds: i.seconds };
  }).filter(Boolean);
  const man = { paired: true, name: s.name, rotation: s.rotation || 0, fit: s.fit || 'contain', sound: !!s.sound, items };
  man.version = crypto.createHash('sha1').update(JSON.stringify(man)).digest('hex').slice(0, 12);
  if (wantCap[dev] && Date.now() - wantCap[dev] < 60000) man.capture = true; // fora do hash: não reinicia a lista
  return man;
}

process.on('uncaughtException', e => console.error('Erro não tratado:', e));
process.on('unhandledRejection', e => console.error('Promessa rejeitada:', e));
http.createServer(async (req, res) => {
  try {
    const u = new URL(req.url, 'http://x'), p = u.pathname, m = req.method;
    if (p === '/api/login' && m === 'POST') {
      const { pass } = await readBody(req);
      const ip = ipOf(req), f = fails[ip], recent = f && Date.now() - f.t < 15 * 60000;
      if (recent && f.n >= 8) return json(res, 429, { error: 'Muitas tentativas erradas. Aguarde 15 minutos.' });
      if (!pass) return json(res, 401, { error: 'Informe a senha' });
      if (String(pass).trim() !== PASS) { fails[ip] = { n: (recent ? f.n : 0) + 1, t: Date.now() }; return json(res, 401, { error: 'Senha incorreta' }); }
      delete fails[ip];
      const t = crypto.randomBytes(24).toString('hex'); tokens.add(t); return json(res, 200, { token: t });
    }
    if (p === '/api/player/capture' && m === 'POST') {
      const dev = u.searchParams.get('device') || '', sc = db.screens.find(x => x.device === dev);
      if (!sc) { req.resume(); return json(res, 403, { error: 'Tela não pareada' }); }
      const ch = []; let n = 0;
      for await (const c of req) { n += c.length; if (n > 3e6) return json(res, 413, { error: 'Imagem grande demais' }); ch.push(c); }
      const buf = Buffer.concat(ch);
      if (buf[0] !== 0xff || buf[1] !== 0xd8) return json(res, 400, { error: 'Imagem inválida' });
      fs.writeFileSync(path.join(CAPT, sc.id + '.jpg'), buf); sc.captureAt = Date.now(); delete wantCap[dev]; save();
      return json(res, 200, {});
    }
    if (p === '/api/player/log' && m === 'POST') {
      const dev = u.searchParams.get('device') || '', sc = db.screens.find(x => x.device === dev);
      if (!sc) { req.resume(); return json(res, 403, { error: 'Tela não pareada' }); }
      const ch = []; let n = 0;
      for await (const c of req) { n += c.length; if (n > 1e6) return json(res, 413, { error: 'Grande demais' }); ch.push(c); }
      let b; try { b = JSON.parse(Buffer.concat(ch).toString()); } catch { return json(res, 400, { error: 'JSON inválido' }); }
      const seenB = (st.batches[dev] ||= []);
      if (typeof b.b === 'string' && seenB.includes(b.b)) return json(res, 200, { dup: true }); // reenvio de um lote já recebido
      const now = Date.now(); let ok = 0;
      for (const r of (Array.isArray(b.rows) ? b.rows.slice(0, 5000) : [])) {
        if (!r || !/^\d{4}-\d\d-\d\dT\d\d$/.test(r.h || '') || !/^[\w-]{1,40}$/.test(r.c || '')) continue;
        const t = Date.parse(r.h + ':30:00Z'); if (!(t > now - 400 * 864e5 && t < now + 864e5)) continue;
        const secs = Math.min(Math.max(+r.s || 0, 0), 3600), plays = Math.min(Math.max(+r.n || 0, 0), 3600); if (!secs) continue;
        const cell = (bucket(dayOf(t), sc.id).c[r.c] ||= [0, 0]); cell[0] += secs; cell[1] += plays; ok++;
        const mm = db.media.find(x => x.id === r.c); if (mm) st.names[r.c] = mm.name; else if (r.c === 'clock') st.names.clock = 'Relógio e data';
      }
      if (typeof b.b === 'string') { seenB.push(b.b.slice(0, 40)); if (seenB.length > 50) seenB.shift(); }
      st.snames[sc.id] = sc.name; sdirty = true; return json(res, 200, { ok });
    }
    if (p === '/api/player') {
      const dev = u.searchParams.get('device') || '';
      if (!/^[\w-]{8,64}$/.test(dev)) return json(res, 400, { error: 'device inválido' });
      return json(res, 200, manifest(dev, u.searchParams.get('p')));
    }
    if (p.startsWith('/media/')) return serve(req, res, path.join(MEDIA, path.basename(p)));
    if (p.startsWith('/api/')) {
      if (!tokens.has((req.headers.authorization || '').slice(7))) return json(res, 401, { error: 'Não autorizado' });
      let r;
      if (p === '/api/stats' && m === 'GET') {
        const from = u.searchParams.get('from') || '', to = u.searchParams.get('to') || '', ok = /^\d{4}-\d\d-\d\d$/;
        if (!ok.test(from) || !ok.test(to) || to < from || Date.parse(to) - Date.parse(from) > 800 * 864e5) return json(res, 400, { error: 'Período inválido' });
        const days = {}; for (const d of Object.keys(st.days)) if (d >= from && d <= to) days[d] = st.days[d];
        const names = { ...st.names, clock: 'Relógio e data' }; for (const x of db.media) names[x.id] = x.name;
        const screens = { ...st.snames }; for (const x of db.screens) screens[x.id] = x.name;
        return json(res, 200, { tz: TZ, today: dayOf(Date.now()), days, names, screens });
      }
      if (p === '/api/state') {
        return json(res, 200, { now: Date.now(), tags: db.tags, media: db.media, playlists: db.playlists,
          screens: db.screens.map(s => ({ ...s, seen: seen[s.device] || 0 })) });
      }
      if (p === '/api/tags' && m === 'POST') {
        const b = await readBody(req); let t = b.id && db.tags.find(x => x.id === b.id);
        if (!t) {
          if (!['screens', 'files', 'lists'].includes(b.scope) || !String(b.name || '').trim()) return json(res, 400, { error: 'Informe o nome da etiqueta.' });
          t = { id: rid(), scope: b.scope, name: '', color: '#f2a900' }; db.tags.push(t);
        }
        if (b.name !== undefined) { const n = String(b.name).trim().slice(0, 40); if (!n) return json(res, 400, { error: 'O nome não pode ficar vazio.' }); t.name = n; }
        if (/^#[0-9a-f]{6}$/i.test(b.color || '')) t.color = b.color;
        save(); return json(res, 200, t);
      }
      if ((r = p.match(/^\/api\/tags\/(\w+)$/)) && m === 'DELETE') {
        db.tags = db.tags.filter(x => x.id !== r[1]);
        for (const l of [db.screens, db.media, db.playlists]) l.forEach(e => { if (e.tags) e.tags = e.tags.filter(i => i !== r[1]); });
        save(); return json(res, 200, {});
      }
      if (p === '/api/media' && m === 'POST') {
        const name = u.searchParams.get('name') || 'arquivo', ext = path.extname(name).toLowerCase().replace(/[^.\w]/g, '');
        const IMG = ['.jpg', '.jpeg', '.png', '.gif', '.webp'], VID = ['.mp4', '.webm', '.mov', '.m4v'];
        const type = IMG.includes(ext) ? 'image' : VID.includes(ext) ? 'video' : null;
        const size = +req.headers['content-length'] || 0;
        if (!type) { req.resume(); return json(res, 400, { error: 'Tipo não aceito. Use JPG, PNG, GIF, WEBP, MP4, WEBM, MOV ou M4V.' }); }
        if (size > 1024 ** 3) { req.resume(); return json(res, 400, { error: 'O arquivo passa do limite de 1 GB.' }); }
        const id = rid(), file = id + ext;
        await pipeline(req, fs.createWriteStream(path.join(MEDIA, file)));
        db.media.push({ id, name, type, kind: ext === '.gif' ? 'gif' : type, file, size, duration: Math.round((+u.searchParams.get('dur') || 0) * 10) / 10, created: Date.now() });
        save(); return json(res, 200, { id });
      }
      if ((r = p.match(/^\/api\/media\/(\w+)$/)) && m === 'PATCH') {
        const x = db.media.find(a => a.id === r[1]); if (!x) return json(res, 404, { error: 'Arquivo não encontrado' });
        const b = await readBody(req);
        if (b.name) x.name = String(b.name).slice(0, 120);
        if ('tags' in b) x.tags = vt(b.tags, 'files');
        if (+b.duration > 0) x.duration = Math.round(+b.duration * 10) / 10;
        save(); return json(res, 200, {});
      }
      if ((r = p.match(/^\/api\/media\/(\w+)$/)) && m === 'DELETE') {
        const x = db.media.find(a => a.id === r[1]);
        if (x) { fs.rmSync(path.join(MEDIA, x.file), { force: true }); db.media = db.media.filter(a => a !== x);
          db.playlists.forEach(pl => pl.items = pl.items.filter(i => i.mediaId !== x.id)); save(); }
        return json(res, 200, {});
      }
      if (p === '/api/playlists' && m === 'POST') {
        const b = await readBody(req);
        const pl = { id: b.id || rid(), name: String(b.name || 'Sem nome').slice(0, 80), orientation: b.orientation === 'v' ? 'v' : 'h',
          items: (b.items || []).map(i => ({ kind: i.kind === 'clock' ? 'clock' : 'media', mediaId: i.mediaId, seconds: Math.max(1, +i.seconds || 10) })) };
        const k = db.playlists.findIndex(x => x.id === pl.id);
        pl.created = k < 0 ? Date.now() : db.playlists[k].created || Date.now();
        pl.tags = 'tags' in b ? vt(b.tags, 'lists') : (k < 0 ? [] : db.playlists[k].tags || []);
        k < 0 ? db.playlists.push(pl) : (db.playlists[k] = pl); save(); return json(res, 200, pl);
      }
      if ((r = p.match(/^\/api\/playlists\/(\w+)$/)) && m === 'DELETE') {
        db.playlists = db.playlists.filter(x => x.id !== r[1]); db.screens.forEach(s => { if (s.playlistId === r[1]) s.playlistId = null; });
        save(); return json(res, 200, {});
      }
      if (p === '/api/screens' && m === 'POST') {
        const b = await readBody(req);
        const dev = Object.keys(db.devices).find(k => db.devices[k].code === String(b.code).trim());
        if (!dev) return json(res, 404, { error: 'Código não encontrado. Confira o código exibido na tela.' });
        if (db.screens.some(s => s.device === dev)) return json(res, 409, { error: 'Esta tela já está pareada.' });
        db.screens.push({ id: rid(), device: dev, name: String(b.name || 'Nova tela').slice(0, 80), playlistId: b.playlistId || null, rotation: 0, fit: 'contain', sound: false, created: Date.now(), ...clean(b) });
        save(); return json(res, 200, {});
      }
      if ((r = p.match(/^\/api\/screens\/(\w+)\/capture$/)) && m === 'POST') {
        const sc = db.screens.find(x => x.id === r[1]); if (!sc) return json(res, 404, { error: 'Tela não encontrada' });
        wantCap[sc.device] = Date.now(); return json(res, 200, {});
      }
      if ((r = p.match(/^\/api\/captures\/(\w+)$/)) && m === 'GET') return serve(req, res, path.join(CAPT, r[1] + '.jpg'));
      if ((r = p.match(/^\/api\/screens\/(\w+)$/))) {
        const s = db.screens.find(x => x.id === r[1]); if (!s) return json(res, 404, { error: 'Tela não encontrada' });
        if (m === 'PATCH') { const b = await readBody(req); if ('playlistId' in b) s.playlistId = b.playlistId || null; if (b.name) s.name = String(b.name).slice(0, 80); Object.assign(s, clean(b)); }
        if (m === 'DELETE') { fs.rmSync(path.join(CAPT, s.id + '.jpg'), { force: true }); db.screens = db.screens.filter(x => x !== s); delete db.devices[s.device]; }
        save(); return json(res, 200, {});
      }
      return json(res, 404, { error: 'Rota não encontrada' });
    }
    const f = path.join(PUB, p === '/' ? 'index.html' : p);
    if (!f.startsWith(PUB)) { res.writeHead(403); return res.end(); }
    serve(req, res, f);
  } catch (e) { json(res, 500, { error: String(e.message || e) }); }
}).listen(PORT, process.env.HOST || '0.0.0.0', () => {
  console.log(process.env.ADMIN_PASS ? `Senha de admin definida (${PASS.length} caracteres)` : 'ADMIN_PASS não definida: a senha é "admin"');
  console.log(`Painel: http://localhost:${PORT}  |  Player: http://localhost:${PORT}/player.html`);
});
