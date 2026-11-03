// The buddy's world in 3D.
//
// The mod writes a copy of the chunks the buddy's game holds (World3d.java, world3d.bin: block states and biomes by number, the
// players and other entities). This page reads it and builds the meshes itself, as a server list site's 3D map does with region
// files: the block models, textures and biome colours come from Mojang's own client jar of the same version, read in
// pieces straight from piston-data.mojang.com (it allows that from any page) and kept in the browser (IndexedDB), so the
// panel hosts none of Mojang's art and needs nothing per Minecraft version.
//
// What it does not do yet: block entities (a chest, bed or sign is a plain box in its particle texture), mobs' own
// models (a box of their size), uvlock, biome blending, light. Nothing here sends anything to the buddy.

import * as THREE from 'three';
import { OrbitControls } from '/3d/OrbitControls.js';

// colours as the game has them: no conversion between colour spaces anywhere (the textures, the sky, the figures)
THREE.ColorManagement.enabled = false;

// the page that starts the view names its elements (start3d's ids); one it lacks is left alone
let IDS = {};
const el = name => IDS[name] ? document.getElementById(IDS[name]) : null;
const status = html => { const e = el('status'); if (e) e.innerHTML = html; };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const RADIUS = 10;

// ---------- the browser's own store, for the pieces of the jar ----------

const store = {
  db: null,
  async open() {
    if (this.db) return;
    try {
      this.db = await new Promise((res, rej) => {
        const r = indexedDB.open('buddy-3d', 1);
        r.onupgradeneeded = () => r.result.createObjectStore('files');
        r.onsuccess = () => res(r.result);
        r.onerror = () => rej(r.error);
      });
    } catch { this.db = null; }
  },
  async getMany(keys) {
    const out = new Map();
    if (!this.db || !keys.length) return out;
    try {
      await new Promise((res, rej) => {
        const tx = this.db.transaction('files', 'readonly'), os = tx.objectStore('files');
        for (const k of keys) { const q = os.get(k); q.onsuccess = () => { if (q.result) out.set(k, q.result); }; }
        tx.oncomplete = res; tx.onerror = () => rej(tx.error);
      });
    } catch { /* without the store it works, only slower */ }
    return out;
  },
  async putMany(entries) {
    if (!this.db || !entries.length) return;
    try {
      await new Promise((res, rej) => {
        const tx = this.db.transaction('files', 'readwrite'), os = tx.objectStore('files');
        for (const [k, v] of entries) os.put(v, k);
        tx.oncomplete = res; tx.onerror = () => rej(tx.error);
      });
    } catch { }
  },
};

// ---------- Mojang's client jar, read in pieces ----------

async function jarOf(version) {
  const key = 'buddy3d-jar-' + version;
  try { const c = JSON.parse(localStorage.getItem(key) || 'null'); if (c && c.url) return c; } catch { }
  const man = await (await fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')).json();
  const v = man.versions.find(x => x.id === version);
  if (!v) throw new Error('Mojang does not know version ' + version);
  const vj = await (await fetch(v.url)).json();
  const c = { url: vj.downloads.client.url, sha1: vj.downloads.client.sha1, size: vj.downloads.client.size };
  try { localStorage.setItem(key, JSON.stringify(c)); } catch { }
  return c;
}

async function range(url, a, b) {
  const r = await fetch(url, { headers: { Range: `bytes=${a}-${b - 1}` }, cache: 'no-store' });
  if (r.status !== 206 && r.status !== 200) throw new Error('jar: ' + r.status);
  const buf = new Uint8Array(await r.arrayBuffer());
  return r.status === 200 ? buf.subarray(a, b) : buf;
}

async function inflate(bytes) {
  const s = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(s).arrayBuffer());
}

class Jar {
  constructor(info) { this.info = info; this.entries = null; }

  async open() {
    const k = this.info.sha1 + '/central';
    let cd = (await store.getMany([k])).get(k);
    if (!cd) {
      const size = this.info.size, tailLen = Math.min(size, 66000);
      const tail = await range(this.info.url, size - tailLen, size);
      const dv = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
      let e = -1;
      for (let i = tail.length - 22; i >= 0; i--) if (dv.getUint32(i, true) === 0x06054b50) { e = i; break; }
      if (e < 0) throw new Error('jar: no end of the central directory');
      const cdSize = dv.getUint32(e + 12, true), cdOff = dv.getUint32(e + 16, true);
      cd = await range(this.info.url, cdOff, cdOff + cdSize);
      await store.putMany([[k, cd]]);
    }
    const dv = new DataView(cd.buffer, cd.byteOffset, cd.byteLength), dec = new TextDecoder();
    this.entries = new Map();
    for (let p = 0; p + 46 <= cd.length && dv.getUint32(p, true) === 0x02014b50;) {
      const method = dv.getUint16(p + 10, true), csize = dv.getUint32(p + 20, true);
      const n = dv.getUint16(p + 28, true), x = dv.getUint16(p + 30, true), c = dv.getUint16(p + 32, true);
      const lho = dv.getUint32(p + 42, true), name = dec.decode(cd.subarray(p + 46, p + 46 + n));
      this.entries.set(name, { method, csize, lho, n });
      p += 46 + n + x + c;
    }
  }

  /** The files asked for that the jar has, as bytes; nearby files are fetched in one request. */
  async read(names, progress) {
    const out = new Map(), keyOf = n => this.info.sha1 + '/' + n;
    const want = [...new Set(names)].filter(n => this.entries.has(n));
    const cached = await store.getMany(want.map(keyOf));
    const missing = [];
    for (const n of want) { const v = cached.get(keyOf(n)); if (v) out.set(n, v); else missing.push(n); }
    if (!missing.length) return out;
    const items = missing.map(n => ({ name: n, ...this.entries.get(n) })).sort((a, b) => a.lho - b.lho);
    const groups = [];
    for (const it of items) {
      const a = it.lho, b = it.lho + 30 + it.n + it.csize + 1024;
      const g = groups[groups.length - 1];
      if (g && a - g.b < 64 * 1024 && b - g.a < 6 * 1024 * 1024) { g.b = Math.max(g.b, b); g.items.push(it); }
      else groups.push({ a, b, items: [it] });
    }
    let done = 0;
    const fresh = [];
    const work = async (g) => {
      const buf = await range(this.info.url, g.a, Math.min(g.b, this.info.size));
      const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
      for (const it of g.items) {
        let o = it.lho - g.a, data;
        if (o + 30 <= buf.length && dv.getUint32(o, true) === 0x04034b50) {
          const s = o + 30 + dv.getUint16(o + 26, true) + dv.getUint16(o + 28, true);
          if (s + it.csize <= buf.length) data = buf.subarray(s, s + it.csize);
        }
        if (!data) {
          const one = await range(this.info.url, it.lho, Math.min(it.lho + 30 + it.n + it.csize + 65535, this.info.size));
          const d1 = new DataView(one.buffer, one.byteOffset, one.byteLength);
          const s = 30 + d1.getUint16(26, true) + d1.getUint16(28, true);
          data = one.subarray(s, s + it.csize);
        }
        const bytes = it.method === 8 ? await inflate(data) : data.slice();
        out.set(it.name, bytes);
        fresh.push([keyOf(it.name), bytes]);
      }
      done += g.items.length;
      if (progress) progress(done, items.length);
    };
    const queue = groups.slice();
    await Promise.all(Array.from({ length: 6 }, async () => { while (queue.length) await work(queue.shift()); }));
    await store.putMany(fresh);
    return out;
  }
}

/**
 * Pictures of the game (its screens' backgrounds and sprites) for the panel, as data addresses: names are paths under
 * assets/minecraft/textures without .png. Read from Mojang's jar like the blocks' textures, and kept the same way.
 */
const JARS = new Map();
export async function gameImages(version, names) {
  await store.open();
  let jar = JARS.get(version);
  if (!jar) { jar = new Jar(await jarOf(version)); JARS.set(version, jar); await jar.open(); }
  const files = await jar.read(names.map(n => `assets/minecraft/textures/${n}.png`));
  const out = new Map();
  for (const n of names) {
    const b = files.get(`assets/minecraft/textures/${n}.png`);
    if (!b) continue;
    let bin = '';
    for (let i = 0; i < b.length; i += 0x8000) bin += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
    out.set(n, 'data:image/png;base64,' + btoa(bin));
  }
  return out;
}

// ---------- the copy of the world from the mod ----------

// ---------- light: sky and block light per corner, the time of day in the shader ----------

// The game's light map, simply: a corner's sky light times the daylight, or its block light, whichever is brighter, through
// the game's curve (l / (4 - 3 l)), never darker than the dimension's ambient. The daylight follows the buddy's clock.
// Then the brightness the viewer chose, so that a dark cave can be seen while watching: the game's own Brightness slider
// (gamma, its curve 1 - (1 - x)^4 mixed in), and above it a floor that lifts the dark as Night Vision does.
const LIGHT = { day: { value: 1 }, ambient: { value: 0 }, gamma: { value: 1 }, floor: { value: 0.55 }, night: 0 };
const BRIGHTNESS = { game: [0.5, 0], bright: [1, 0], full: [1, 0.55] };
function setBrightness(level) {
  const b = BRIGHTNESS[level] || BRIGHTNESS.full;
  LIGHT.gamma.value = b[0]; LIGHT.floor.value = b[1];
}
/** The same steps as the shader's, for what is drawn with one colour (mobs, chests, held items). */
function brighten(f) {
  f = f + (1 - Math.pow(1 - f, 4) - f) * LIGHT.gamma.value;
  return LIGHT.floor.value + (1 - LIGHT.floor.value) * f;
}
function lit(mat) {
  mat.onBeforeCompile = sh => {
    sh.uniforms.uDay = LIGHT.day;
    sh.uniforms.uAmbient = LIGHT.ambient;
    sh.uniforms.uGamma = LIGHT.gamma;
    sh.uniforms.uFloor = LIGHT.floor;
    sh.vertexShader = 'attribute vec2 light;\nvarying vec2 vLight;\n' + sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\n vLight = light;');
    sh.fragmentShader = 'varying vec2 vLight;\nuniform float uDay;\nuniform float uAmbient;\nuniform float uGamma;\nuniform float uFloor;\n' + sh.fragmentShader.replace('#include <color_fragment>',
      '#include <color_fragment>\n float lv = max(vLight.x * uDay, vLight.y);\n float lb = lv / (4.0 - 3.0 * lv);\n float lf = max(uAmbient, mix(0.06, 1.0, lb));' +
      '\n lf = mix(lf, 1.0 - pow(1.0 - lf, 4.0), uGamma);\n diffuseColor.rgb *= uFloor + (1.0 - uFloor) * lf;');
  };
  mat.needsUpdate = true;
}

/** Daylight from the buddy's clock, as the game's sky: 1 at noon, low at midnight, the change around sunrise and sunset. */
function daylight(time) {
  const t = ((time % 24000) + 24000) % 24000 / 24000 - 0.25;
  let f = t - Math.floor(t);
  const g = f;
  f = 1 - (Math.cos(f * Math.PI) + 1) / 2;
  f = g + (f - g) / 3;
  return Math.max(0, Math.min(1, Math.cos(f * Math.PI * 2) * 2 + 0.5));
}

/** A section's light: a byte (1 sky, 2 block follow), then 2048 bytes of nibbles each, as the game holds them. */
function readLight(buf, p) {
  const f = buf[p++];
  let sky = null, blk = null;
  if (f & 1) { sky = buf.slice(p, p + 2048); p += 2048; }
  if (f & 2) { blk = buf.slice(p, p + 2048); p += 2048; }
  return { p, l: sky || blk ? { sky, blk } : null };
}

/** Two sections' light (readLight's {sky, blk}, or null for daylight without block light) the same, byte for byte. */
function sameLight(a, b) {
  if (!a || !b) return !a && !b;
  const eq = (x, y) => { if (!x || !y) return !x && !y; if (x.length !== y.length) return false; for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return false; return true; };
  return eq(a.sky, b.sky) && eq(a.blk, b.blk);
}

/** The buddy's name, from the copy or from the panel's state, so that the view names whoever runs it. */
let BUDDY = null;
fetch('/api/state', { cache: 'no-store' }).then(r => r.ok ? r.json() : null).then(s => { if (s && s.name && !BUDDY) BUDDY = s.name; }).catch(() => { });
const who = cap => BUDDY ? escHtml(BUDDY) : cap ? 'The buddy' : 'the buddy';

async function loadWorld(fresh) {
  const r = await fetch('/api/world3d' + (fresh ? `?fresh=1&r=${RADIUS}` : ''), { cache: 'no-store' });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error('world3d: ' + r.status);
  const s = r.body.pipeThrough(new DecompressionStream('gzip'));
  const buf = new Uint8Array(await new Response(s).arrayBuffer());
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  if (new TextDecoder().decode(buf.subarray(0, 4)) !== 'MQ3D') throw new Error('world3d: not a copy of the world');
  let p = 4;
  const mlen = dv.getInt32(p); p += 4;
  const meta = JSON.parse(new TextDecoder().decode(buf.subarray(p, p + mlen))); p += mlen;
  const n = dv.getInt32(p); p += 4;
  const chunks = new Map();
  for (let c = 0; c < n; c++) {
    const cx = dv.getInt32(p), cz = dv.getInt32(p + 4), ns = dv.getUint16(p + 8); p += 10;
    const secs = new Array(meta.sections).fill(null), light = new Array(meta.sections).fill(null);
    for (let k = 0; k < ns; k++) {
      const si = buf[p]; p += 1;
      // with light: 0 blocks and light, 2 light only (air)
      const kind = meta.light ? buf[p++] : 0;
      if (kind === 0) {
        const blocks = new Uint16Array(4096), biomes = new Uint16Array(64);
        for (let i = 0; i < 4096; i++, p += 2) blocks[i] = dv.getUint16(p);
        for (let i = 0; i < 64; i++, p += 2) biomes[i] = dv.getUint16(p);
        secs[si] = { blocks, biomes };
      }
      if (meta.light) { const r = readLight(buf, p); p = r.p; light[si] = r.l; }
    }
    chunks.set(cx + ',' + cz, { cx, cz, secs, light });
  }
  return { meta, chunks, be: new Map(Object.entries(meta.be || {})) };
}

// ---------- block states, models, textures ----------

const INVISIBLE = new Set(['air', 'cave_air', 'void_air', 'light', 'barrier', 'structure_void', 'moving_piston']);
const IMPLICIT_WATER = new Set(['water', 'bubble_column', 'kelp', 'kelp_plant', 'seagrass', 'tall_seagrass']);
const GRASS_TINT = new Set(['grass_block', 'short_grass', 'tall_grass', 'fern', 'large_fern', 'potted_fern', 'sugar_cane', 'bush']);
const FOLIAGE_TINT = new Set(['oak_leaves', 'jungle_leaves', 'acacia_leaves', 'dark_oak_leaves', 'mangrove_leaves', 'vine']);
const FIXED_TINT = { birch_leaves: 0x80a755, spruce_leaves: 0x619961, lily_pad: 0x208030, attached_melon_stem: 0xe0c71c, attached_pumpkin_stem: 0xe0c71c };
const DIRS = {
  down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0],
};
const SHADE = { up: 1.0, down: 0.5, north: 0.8, south: 0.8, west: 0.6, east: 0.6 };

function parseState(s) {
  const b = s.indexOf('[');
  let name = b < 0 ? s : s.slice(0, b);
  if (name.startsWith('minecraft:')) name = name.slice(10);
  const props = {};
  if (b >= 0) for (const kv of s.slice(b + 1, -1).split(',')) { const [k, v] = kv.split('='); props[k] = v; }
  return { name, props };
}

const bare = id => { id = String(id); if (id.startsWith('minecraft:')) id = id.slice(10); return id; };
const modelFile = id => `assets/minecraft/models/${bare(id)}.json`;
const textureFile = id => `assets/minecraft/textures/${bare(id)}.png`;

function matchesVariant(key, props) {
  if (key === '') return true;
  for (const kv of key.split(',')) { const [k, v] = kv.split('='); if (props[k] !== v) return false; }
  return true;
}

function matchesWhen(w, props) {
  if (!w) return true;
  if (w.OR) return w.OR.some(x => matchesWhen(x, props));
  if (w.AND) return w.AND.every(x => matchesWhen(x, props));
  for (const [k, v] of Object.entries(w)) if (!String(v).split('|').includes(props[k])) return false;
  return true;
}

/** Per state: the models it applies, as lists to pick from by position (a variant with several models). */
function appliedModels(bs, props) {
  if (!bs) return [];
  if (bs.variants) {
    for (const [k, v] of Object.entries(bs.variants)) if (matchesVariant(k, props)) return [Array.isArray(v) ? v : [v]];
    return [];
  }
  if (bs.multipart) {
    const out = [];
    for (const part of bs.multipart) if (matchesWhen(part.when, props)) out.push(Array.isArray(part.apply) ? part.apply : [part.apply]);
    return out;
  }
  return [];
}

function flattenModel(models, id) {
  let elements = null, ao = null;
  const textures = {}, chain = [];
  for (let cur = bare(id), guard = 0; cur && guard < 20; guard++) {
    const m = models.get(cur);
    if (!m) break;
    chain.push(m);
    if (!elements && m.elements) elements = m.elements;
    if (ao === null && m.ambientocclusion !== undefined) ao = m.ambientocclusion;
    cur = m.parent ? bare(m.parent) : null;
  }
  for (let i = chain.length - 1; i >= 0; i--) Object.assign(textures, chain[i].textures || {});
  return { elements: elements || [], textures, ao: ao !== false };
}

function resolveTexture(textures, ref) {
  // since 26.x a texture may be {sprite, force_translucent} (stained glass)
  const name = r => r && typeof r === 'object' ? r.sprite : r;
  ref = name(ref);
  for (let i = 0; i < 10 && typeof ref === 'string' && ref.startsWith('#'); i++) ref = name(textures[ref.slice(1)]);
  return typeof ref === 'string' && !ref.startsWith('#') ? bare(ref) : null;
}

// rotations of the block state, around the block's middle (x then y, as the game does: x=90 turns north to down,
// y=90 turns north to east)
function rotX(v, steps) { let [x, y, z] = v; for (let i = 0; i < steps; i++) [y, z] = [z, -y]; return [x, y, z]; }
function rotY(v, steps) { let [x, y, z] = v; for (let i = 0; i < steps; i++) [x, z] = [-z, x]; return [x, y, z]; }

function faceCorners(f, t, dir) {
  switch (dir) {
    case 'north': return [[t[0], t[1], f[2]], [f[0], t[1], f[2]], [f[0], f[1], f[2]], [t[0], f[1], f[2]]];
    case 'south': return [[f[0], t[1], t[2]], [t[0], t[1], t[2]], [t[0], f[1], t[2]], [f[0], f[1], t[2]]];
    case 'west': return [[f[0], t[1], f[2]], [f[0], t[1], t[2]], [f[0], f[1], t[2]], [f[0], f[1], f[2]]];
    case 'east': return [[t[0], t[1], t[2]], [t[0], t[1], f[2]], [t[0], f[1], f[2]], [t[0], f[1], t[2]]];
    case 'up': return [[f[0], t[1], f[2]], [t[0], t[1], f[2]], [t[0], t[1], t[2]], [f[0], t[1], t[2]]];
    default: return [[f[0], f[1], t[2]], [t[0], f[1], t[2]], [t[0], f[1], f[2]], [f[0], f[1], f[2]]];
  }
}

function defaultUv(f, t, dir) {
  switch (dir) {
    case 'down': return [f[0], 16 - t[2], t[0], 16 - f[2]];
    case 'up': return [f[0], f[2], t[0], t[2]];
    case 'north': return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]];
    case 'south': return [f[0], 16 - t[1], t[0], 16 - f[1]];
    case 'west': return [f[2], 16 - t[1], t[2], 16 - f[1]];
    default: return [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]];
  }
}

function elementRotation(r) {
  // returns a function on a point relative to nothing (positions in 0..16)
  if (!r) return null;
  const o = r.origin || [8, 8, 8];
  const m = new THREE.Matrix4();
  let scale = [1, 1, 1];
  if (r.axis) {
    const a = (r.angle || 0) * Math.PI / 180;
    if (r.axis === 'x') m.makeRotationX(a); else if (r.axis === 'y') m.makeRotationY(a); else m.makeRotationZ(a);
    if (r.rescale && Math.abs(Math.cos(a)) > 1e-4) {
      const k = 1 / Math.cos(a);
      scale = r.axis === 'x' ? [1, k, k] : r.axis === 'y' ? [k, 1, k] : [k, k, 1];
    }
  } else {
    const e = new THREE.Euler((r.x || 0) * Math.PI / 180, (r.y || 0) * Math.PI / 180, (r.z || 0) * Math.PI / 180, 'XYZ');
    m.makeRotationFromEuler(e);
  }
  const v = new THREE.Vector3();
  return {
    point: p => { v.set(p[0] - o[0], p[1] - o[1], p[2] - o[2]).applyMatrix4(m); return [v.x * scale[0] + o[0], v.y * scale[1] + o[1], v.z * scale[2] + o[2]]; },
    dir: d => { v.set(d[0], d[1], d[2]).applyMatrix4(m); return [v.x, v.y, v.z]; },
  };
}

function dirName(v) {
  let best = 'up', bd = -2;
  for (const [n, d] of Object.entries(DIRS)) { const s = d[0] * v[0] + d[1] * v[1] + d[2] * v[2]; if (s > bd) { bd = s; best = n; } }
  return best;
}

/** The quads of one applied model (with the state's x and y), positions 0..1. */
function modelQuads(models, apply) {
  const m = flattenModel(models, apply.model);
  const sx = ((apply.x || 0) / 90) & 3, sy = ((apply.y || 0) / 90) & 3;
  const turnP = p => { let q = [p[0] / 16 - 0.5, p[1] / 16 - 0.5, p[2] / 16 - 0.5]; q = rotY(rotX(q, sx), sy); return [q[0] + 0.5, q[1] + 0.5, q[2] + 0.5]; };
  const turnD = d => rotY(rotX(d, sx), sy);
  const quads = [];
  for (const el of m.elements) {
    const f = el.from, t = el.to, er = elementRotation(el.rotation);
    for (const [dir, face] of Object.entries(el.faces || {})) {
      const tex = resolveTexture(m.textures, face.texture);
      let pts = faceCorners(f, t, dir);
      let normal = DIRS[dir];
      if (er) { pts = pts.map(er.point); normal = er.dir(normal); }
      pts = pts.map(turnP);
      normal = turnD(normal);
      const uv = face.uv || defaultUv(f, t, dir);
      const c = [[uv[0], uv[1]], [uv[2], uv[1]], [uv[2], uv[3]], [uv[0], uv[3]]];
      const steps = ((face.rotation || 0) / 90) & 3;
      const uvs = [0, 1, 2, 3].map(k => c[(k - steps + 4) % 4]);
      const cull = face.cullface ? dirName(turnD(DIRS[face.cullface])) : null;
      const shadeDir = el.shade_direction_override ? dirName(turnD(DIRS[el.shade_direction_override])) : dirName(normal);
      quads.push({ pts, uvs, tex, cull, shadeDir, normal, tint: face.tintindex !== undefined && face.tintindex >= 0, ao: m.ao && !!face.cullface });
    }
  }
  return { quads, textures: m.textures, elements: m.elements };
}

// ---------- biome colours ----------

function hexColour(v, fallback) {
  if (typeof v === 'number') return v & 0xffffff;
  if (typeof v === 'string' && v.startsWith('#')) return parseInt(v.slice(1), 16);
  return fallback;
}

function biomeColours(json, grassMap, foliageMap) {
  const temp = Math.min(1, Math.max(0, json?.temperature ?? 0.8)), down = Math.min(1, Math.max(0, json?.downfall ?? 0.4)) * temp;
  const pick = (map, fb) => {
    if (!map) return fb;
    const i = Math.floor((1 - temp) * 255), j = Math.floor((1 - down) * 255), k = (j * 256 + i) * 4;
    return k + 2 < map.length ? (map[k] << 16) | (map[k + 1] << 8) | map[k + 2] : fb;
  };
  const fx = json?.effects || {};
  let grass = fx.grass_color !== undefined ? hexColour(fx.grass_color, 0x91bd59) : pick(grassMap, 0x91bd59);
  const foliage = fx.foliage_color !== undefined ? hexColour(fx.foliage_color, 0x77ab2f) : pick(foliageMap, 0x77ab2f);
  if (fx.grass_color_modifier === 'dark_forest') grass = (((grass & 0xfefefe) + 0x28340a) >> 1) & 0xffffff;
  else if (fx.grass_color_modifier === 'swamp') grass = 0x6a7039;
  return { grass, foliage, water: hexColour(fx.water_color, 0x3f76e4) };
}

// ---------- building it all ----------

async function decodeImage(bytes) {
  return await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
}

async function pixelsOf(bytes) {
  const bmp = await decodeImage(bytes);
  const c = new OffscreenCanvas(bmp.width, bmp.height), g = c.getContext('2d');
  g.drawImage(bmp, 0, 0);
  return g.getImageData(0, 0, bmp.width, bmp.height).data;
}

// The atlas has room for 4096 textures (the game has fewer than 2000 for blocks), so that one that comes later gets a
// cell of its own and the faces built before keep theirs. Each cell is 32 pixels: the texture in the middle and its edge
// pixels drawn outwards, so that the smaller versions of the atlas (mipmaps, without which leaves and bamboo shimmer as
// the view moves) do not bleed into the neighbour cell.
const ATLAS = 2048, CELL = 32, PAD = 8, PER = ATLAS / CELL;

/** What the page knows of block states and biomes, numbered as the mod numbers them, and grown as new ones come. */
class Prep {
  constructor(jar, version) {
    this.jar = jar;
    this.version = version;
    this.dec = new TextDecoder();
    this.states = [];
    this.infos = [];
    this.blockstates = new Map();
    this.models = new Map();
    this.cells = new Map();
    this.nextCell = 0;
    this.biomeIds = [];
    this.biomes = [];
    const c = document.createElement('canvas');
    c.width = c.height = ATLAS;
    this.g = c.getContext('2d', { willReadFrequently: true });
    this.g.imageSmoothingEnabled = false;
    this.texture = new THREE.CanvasTexture(c);
    this.texture.magFilter = THREE.NearestFilter;
    this.texture.minFilter = THREE.NearestMipmapLinearFilter;
    this.texture.generateMipmaps = true;
    this.texture.anisotropy = renderer.capabilities.getMaxAnisotropy();
    this.texture.flipY = false;
    this.texture.colorSpace = THREE.NoColorSpace;
    this.mats = [
      new THREE.MeshBasicMaterial({ map: this.texture, vertexColors: true }),
      // one side, as the game draws them: a flat model with an up and a down face in one plane (leaf litter, petals)
      // flickered when both were drawn from both sides
      new THREE.MeshBasicMaterial({ map: this.texture, vertexColors: true, alphaTest: 0.5, alphaToCoverage: true }),
      new THREE.MeshBasicMaterial({ map: this.texture, vertexColors: true, transparent: true, depthWrite: false, side: THREE.DoubleSide }),
    ];
    this.mats.forEach(lit);
  }

  async init(onStep) {
    onStep(`Reading the game's files (Minecraft <b>${this.version}</b>)…`);
    await this.jar.open();
    const maps = ['assets/minecraft/textures/colormap/grass.png', 'assets/minecraft/textures/colormap/foliage.png'];
    const f = await this.jar.read(maps);
    this.grassMap = f.get(maps[0]) ? await pixelsOf(f.get(maps[0])) : null;
    this.foliageMap = f.get(maps[1]) ? await pixelsOf(f.get(maps[1])) : null;
    await this.addTextures(['__missing', 'block/water_still', 'block/lava_still']);
    this.water = this.fluid('block/water_still', 2);
    this.lava = this.fluid('block/lava_still', 0);
  }

  uvOf(cell, u, v) {
    return [(cell.x + PAD + Math.min(15.98, Math.max(0.02, u))) / ATLAS, (cell.y + PAD + Math.min(15.98, Math.max(0.02, v))) / ATLAS];
  }

  fluid(id, layer) {
    const c = this.cells.get(id);
    return { cell: c, uv: [this.uvOf(c, 0, 0), this.uvOf(c, 16, 0), this.uvOf(c, 16, 16), this.uvOf(c, 0, 16)], layer };
  }

  /** Each texture in a cell of the atlas: the first frame of an animated one, 16 by 16. */
  async addTextures(ids, onStep) {
    const fresh = [...new Set(ids)].filter(id => id && !this.cells.has(id));
    if (!fresh.length) return;
    const files = await this.jar.read(fresh.map(textureFile), onStep && ((d, t) => onStep(`Reading textures: <b>${d}</b> of ${t}`)));
    const g = this.g;
    for (const id of fresh) {
      if (this.nextCell >= PER * PER) { this.cells.set(id, this.cells.get('__missing')); continue; }
      const cx = (this.nextCell % PER) * CELL, cy = Math.floor(this.nextCell / PER) * CELL, tx = cx + PAD, ty = cy + PAD;
      this.nextCell++;
      const b = files.get(textureFile(id));
      let kind = 'missing';
      if (b) {
        try {
          const bmp = await decodeImage(b);
          g.clearRect(cx, cy, CELL, CELL);
          g.drawImage(bmp, 0, 0, bmp.width, bmp.width, tx, ty, 16, 16);
          const px = g.getImageData(tx, ty, 16, 16).data;
          let clear = 0, part = 0;
          for (let i = 3; i < px.length; i += 4) { if (px[i] === 0) clear++; else if (px[i] < 250) part++; }
          kind = part > 12 ? 'translucent' : clear || part ? 'cutout' : 'opaque';
        } catch { kind = 'missing'; }
      }
      if (kind === 'missing') {
        g.fillStyle = '#f0f'; g.fillRect(tx, ty, 16, 16); g.fillStyle = '#000'; g.fillRect(tx, ty, 8, 8); g.fillRect(tx + 8, ty + 8, 8, 8);
        kind = 'opaque';
      }
      // the edge pixels outwards: the sides, then the corners
      const c = g.canvas;
      g.drawImage(c, tx, ty, 16, 1, tx, cy, 16, PAD);
      g.drawImage(c, tx, ty + 15, 16, 1, tx, ty + 16, 16, PAD);
      g.drawImage(c, tx, cy, 1, CELL, cx, cy, PAD, CELL);
      g.drawImage(c, tx + 15, cy, 1, CELL, tx + 16, cy, PAD, CELL);
      this.cells.set(id, { x: cx, y: cy, kind });
    }
    this.texture.needsUpdate = true;
  }

  /** New block states, in the mod's order: their block states, models and textures read, their faces made. */
  async addStates(list, onStep) {
    if (!list.length) return;
    const step = onStep || (() => {});
    const parsed = list.map(parseState);
    const names = [...new Set(parsed.map(s => s.name).filter(n => !INVISIBLE.has(n) && !this.blockstates.has(n)))];
    if (names.length) {
      const files = await this.jar.read(names.map(n => `assets/minecraft/blockstates/${n}.json`),
        (d, t) => step(`Reading block states: <b>${d}</b> of ${t}`));
      for (const n of names) {
        const b = files.get(`assets/minecraft/blockstates/${n}.json`);
        this.blockstates.set(n, b ? JSON.parse(this.dec.decode(b)) : null);
      }
    }
    // the models the states apply, with their parents
    let want = new Set();
    for (const s of parsed) for (const opts of appliedModels(this.blockstates.get(s.name), s.props)) for (const a of opts) if (!this.models.has(bare(a.model))) want.add(bare(a.model));
    for (let round = 0; want.size && round < 12; round++) {
      const files = await this.jar.read([...want].map(modelFile), (d, t) => step(`Reading block models: <b>${d}</b> of ${t}`));
      const next = new Set();
      for (const id of want) {
        const b = files.get(modelFile(id));
        const m = b ? JSON.parse(this.dec.decode(b)) : {};
        this.models.set(id, m);
        if (m.parent && !this.models.has(bare(m.parent)) && !bare(m.parent).startsWith('builtin/')) next.add(bare(m.parent));
      }
      want = next;
    }
    const infos = parsed.map(s => this.info(s));
    const tex = [];
    for (const info of infos) for (const q of info.options.flat()) if (q.tex) tex.push(q.tex);
    await this.addTextures(tex, step);
    for (const info of infos) this.finish(info);
    this.states.push(...list);
    this.infos.push(...infos);
  }

  /** A block state's faces, as lists to pick from by position. */
  info(s) {
    const info = { name: s.name, props: s.props, none: INVISIBLE.has(s.name), options: [], occludes: false, water: false, lava: false, self: false, tintKind: null, fixedTint: null };
    if (s.name === 'lava') { info.lava = true; info.none = true; }
    if (IMPLICIT_WATER.has(s.name) || s.props.waterlogged === 'true') info.water = true;
    if (s.name === 'water' || s.name === 'bubble_column') info.none = true;
    if (GRASS_TINT.has(s.name)) info.tintKind = 'grass';
    else if (FOLIAGE_TINT.has(s.name)) info.tintKind = 'foliage';
    else if (FIXED_TINT[s.name] !== undefined) info.fixedTint = FIXED_TINT[s.name];
    else if (s.name === 'water_cauldron') info.tintKind = 'water';
    else if (s.name === 'redstone_wire') {
      const f = Number(s.props.power || 0) / 15;
      info.fixedTint = (Math.round(255 * (f * 0.6 + (f > 0 ? 0.4 : 0.3))) << 16) | (Math.round(255 * Math.max(0, f * f * 0.7 - 0.5)) << 8) | Math.round(255 * Math.max(0, f * f * 0.6 - 0.7));
    } else if (s.name === 'pumpkin_stem' || s.name === 'melon_stem') {
      const a = Number(s.props.age || 0);
      info.fixedTint = ((a * 32) << 16) | ((255 - a * 8) << 8) | (a * 4);
    }
    if (info.none) return info;
    const bs = this.blockstates.get(s.name);
    const applied = appliedModels(bs, s.props);
    if (!applied.length) { info.none = true; return info; }
    // multipart: every part's first model at once; variants: one list of options to pick from by position
    if (bs.multipart) {
      const q = [];
      for (const opts of applied) q.push(...modelQuads(this.models, opts[0]).quads);
      info.options = [q];
    } else {
      info.options = applied[0].map(a => modelQuads(this.models, a).quads);
    }
    if (info.options.every(q => q.length === 0)) {
      // drawn by the game's code, not a model (chest, bed, sign, banner, head): a box in its particle texture
      const m = flattenModel(this.models, applied[0][0].model);
      const tex = resolveTexture(m.textures, '#particle');
      if (!tex) { info.none = true; return info; }
      const n = s.name, h = n.endsWith('_bed') ? 9 : n.includes('chest') ? 14 : n.includes('sign') || n.includes('banner') ? 16 : 12;
      const lo = n.endsWith('_bed') ? 0 : n.includes('chest') ? 1 : 3, hi = 16 - lo;
      const el = { from: [lo, 0, lo], to: [hi, h, hi], faces: {} };
      for (const d of Object.keys(DIRS)) el.faces[d] = { texture: '#p', cullface: d === 'down' ? 'down' : undefined };
      this.models.set('__box_' + n, { textures: { p: tex }, elements: [el], ambientocclusion: true });
      info.options = [modelQuads(this.models, { model: '__box_' + n }).quads];
    }
    info.self = /glass|ice$|^ice|slime_block|honey_block|leaves/.test(s.name);
    return info;
  }

  /** The uvs into the atlas, the layer of each face, and whether the block hides its neighbours. */
  finish(info) {
    for (const q of info.options.flat()) {
      const cell = this.cells.get(q.tex) || this.cells.get('__missing');
      q.auv = q.uvs.map(([u, v]) => this.uvOf(cell, u, v));
      q.layer = cell.kind === 'opaque' ? 0 : cell.kind === 'cutout' ? 1 : 2;
    }
    // a full cube with all six sides opaque hides what is behind each side
    const full = new Set();
    for (const q of info.options[0] || []) {
      if (!q.cull || q.layer !== 0) continue;
      const xs = q.pts.map(p => p[0]), ys = q.pts.map(p => p[1]), zs = q.pts.map(p => p[2]);
      const span = a => Math.max(...a) - Math.min(...a);
      const d = q.cull, flat = d === 'up' || d === 'down' ? span(xs) > 0.99 && span(zs) > 0.99 : d === 'north' || d === 'south' ? span(xs) > 0.99 && span(ys) > 0.99 : span(zs) > 0.99 && span(ys) > 0.99;
      if (flat) full.add(d);
    }
    info.occludes = !info.none && full.size === 6;
  }

  async addBiomes(ids) {
    if (!ids.length) return;
    const names = ids.map(b => b.startsWith('minecraft:') ? b.slice(10) : null);
    const files = await this.jar.read(names.filter(Boolean).map(b => `data/minecraft/worldgen/biome/${b}.json`));
    for (const b of names) {
      const f = b && files.get(`data/minecraft/worldgen/biome/${b}.json`);
      let json = null;
      try { json = f ? JSON.parse(this.dec.decode(f)) : null; } catch { }
      this.biomes.push(biomeColours(json, this.grassMap, this.foliageMap));
    }
    this.biomeIds.push(...ids);
  }
}

// ---------- the meshes ----------

function hash(x, y, z) {
  let h = (x * 3129871) ^ (z * 116129781) ^ y;
  h = h * h * 42317861 + h * 11;
  return (h >>> 16) & 0x7fffffff;
}

function buildChunk(world, prep, chunk, ox, oz) {
  const { meta, chunks } = world, { infos, biomes } = prep;
  const minY = meta.minY, nsec = meta.sections;
  const airIdx = -1;
  let lastKey = null, last = null;
  const at = (x, y, z) => {
    const si = (y - minY) >> 4;
    if (si < 0 || si >= nsec) return airIdx;
    const cx = x >> 4, cz = z >> 4;
    let c;
    if (cx === chunk.cx && cz === chunk.cz) c = chunk;
    else { const key = cx + ',' + cz; if (key !== lastKey) { lastKey = key; last = chunks.get(key); } c = last; }
    if (!c) return airIdx;
    const s = c.secs[si];
    if (!s) return airIdx;
    return s.blocks[((((y - minY) & 15) * 16) + (z & 15)) * 16 + (x & 15)];
  };
  const occ = (x, y, z) => { const i = at(x, y, z); return i >= 0 && infos[i].occludes; };
  // the light of a block's place: sky * 16 + block, as the game holds it; without it, daylight
  const skyDefault = meta.skylight === false ? 0 : 15;
  const lightAt = (x, y, z) => {
    const si = (y - minY) >> 4;
    if (si < 0) return 0;
    if (si >= nsec) return skyDefault * 16;
    const cx = x >> 4, cz = z >> 4;
    const c = cx === chunk.cx && cz === chunk.cz ? chunk : chunks.get(cx + ',' + cz);
    const L = c && c.light && c.light[si];
    if (!L) return (c && c.light ? skyDefault : 15) * 16;
    const i = ((((y - minY) & 15) * 16) + (z & 15)) * 16 + (x & 15), sh = (i & 1) * 4;
    return (L.sky ? (L.sky[i >> 1] >> sh) & 15 : skyDefault) * 16 + (L.blk ? (L.blk[i >> 1] >> sh) & 15 : 0);
  };
  const isWater = (x, y, z) => { const i = at(x, y, z); return i >= 0 && infos[i].water; };
  const isLava = (x, y, z) => { const i = at(x, y, z); return i >= 0 && infos[i].lava; };

  const L = [0, 1, 2].map(() => ({ pos: [], uv: [], col: [], lit: [], idx: [] }));
  const push = (layer, pts, uvs, cols, flip, lits) => {
    const o = layer.pos.length / 3;
    for (let i = 0; i < 4; i++) {
      layer.pos.push(pts[i][0], pts[i][1], pts[i][2]);
      layer.uv.push(uvs[i][0], uvs[i][1]);
      layer.col.push(cols[i][0], cols[i][1], cols[i][2]);
      const l = lits ? lits[i] : 15 * 16;
      layer.lit.push((l >> 4) / 15, (l & 15) / 15);
    }
    if (flip) layer.idx.push(o, o + 1, o + 3, o + 1, o + 2, o + 3);
    else layer.idx.push(o, o + 1, o + 2, o, o + 2, o + 3);
  };
  const AO = [0.55, 0.7, 0.85, 1.0];
  const tmpCol = [0, 0, 0];

  for (let si = 0; si < nsec; si++) {
    const s = chunk.secs[si];
    if (!s) continue;
    for (let i = 0; i < 4096; i++) {
      const idx = s.blocks[i], info = infos[idx];
      if (!info || (info.none && !info.water && !info.lava)) continue;
      const lx = i & 15, lz = (i >> 4) & 15, ly = i >> 8;
      const X = chunk.cx * 16 + lx, Y = minY + si * 16 + ly, Z = chunk.cz * 16 + lz;
      const bc = biomes[s.biomes[((ly >> 2) * 4 + (lz >> 2)) * 4 + (lx >> 2)]] || biomes[0];
      let tint = 0xffffff;
      if (info.tintKind) tint = bc[info.tintKind];
      else if (info.fixedTint !== null) tint = info.fixedTint;
      const tr = ((tint >> 16) & 255) / 255, tg = ((tint >> 8) & 255) / 255, tb = (tint & 255) / 255;

      // drawn with the game's own model (a chest, a head): not the box in its particle texture
      if (!info.none && !(MODELS.data && blockEntityParts(info))) {
        const quads = info.options.length > 1 ? info.options[hash(X, Y, Z) % info.options.length] : info.options[0];
        for (const q of quads) {
          if (q.cull) {
            const d = DIRS[q.cull], ni = at(X + d[0], Y + d[1], Z + d[2]);
            if (ni >= 0 && (infos[ni].occludes || (info.self && infos[ni].name === info.name))) continue;
          }
          const sh = SHADE[q.shadeDir] ?? 1;
          const r = q.tint ? tr : 1, gg = q.tint ? tg : 1, b = q.tint ? tb : 1;
          const pts = q.pts.map(p => [X - ox + p[0], Y + p[1], Z - oz + p[2]]);
          const cols = [], aos = [], lits = [];
          // a face that is no side of the block (a plant, a slab's top within it): the light of its own place, or of
          // the place its face looks to when that is brighter (inside a solid block the game holds no light)
          let own = -1;
          if (!q.ao) {
            const a1 = lightAt(X, Y, Z), nd = q.normal, b1 = lightAt(X + Math.round(nd[0]), Y + Math.round(nd[1]), Z + Math.round(nd[2]));
            own = Math.max(a1 >> 4, b1 >> 4) * 16 + Math.max(a1 & 15, b1 & 15);
          }
          for (let v = 0; v < 4; v++) {
            let a = 3;
            if (q.ao) {
              const d = DIRS[q.cull], p = q.pts[v];
              const axes = d[0] ? [1, 2] : d[1] ? [0, 2] : [0, 1];
              const s1 = [0, 0, 0], s2 = [0, 0, 0];
              s1[axes[0]] = p[axes[0]] < 0.5 ? -1 : 1;
              s2[axes[1]] = p[axes[1]] < 0.5 ? -1 : 1;
              const bx = X + d[0], by = Y + d[1], bz = Z + d[2];
              const o1 = occ(bx + s1[0], by + s1[1], bz + s1[2]), o2 = occ(bx + s2[0], by + s2[1], bz + s2[2]);
              const oc = occ(bx + s1[0] + s2[0], by + s1[1] + s2[1], bz + s1[2] + s2[2]);
              a = o1 && o2 ? 0 : 3 - o1 - o2 - oc;
              // smooth light, as the game: the four places around the corner outside the face, the solid ones left out
              let ns = 0, sk = 0, bk = 0;
              const add = (x, y, z) => { const l = lightAt(x, y, z); sk += l >> 4; bk += l & 15; ns++; };
              add(bx, by, bz);
              if (!o1) add(bx + s1[0], by + s1[1], bz + s1[2]);
              if (!o2) add(bx + s2[0], by + s2[1], bz + s2[2]);
              if (!oc && !(o1 && o2)) add(bx + s1[0] + s2[0], by + s1[1] + s2[1], bz + s1[2] + s2[2]);
              lits.push(Math.round(sk / ns) * 16 + Math.round(bk / ns));
            } else lits.push(own);
            aos.push(a);
            const k = sh * AO[a];
            cols.push([r * k, gg * k, b * k]);
          }
          // the triangles counter-clockwise seen from outside, split along the brighter diagonal
          const e1 = [pts[1][0] - pts[0][0], pts[1][1] - pts[0][1], pts[1][2] - pts[0][2]];
          const e2 = [pts[2][0] - pts[0][0], pts[2][1] - pts[0][1], pts[2][2] - pts[0][2]];
          const n = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]];
          const ccw = n[0] * q.normal[0] + n[1] * q.normal[1] + n[2] * q.normal[2] > 0;
          let P = pts, U = q.auv, C = cols, A = aos, W = lits;
          if (!ccw) { P = [pts[0], pts[3], pts[2], pts[1]]; U = [U[0], U[3], U[2], U[1]]; C = [cols[0], cols[3], cols[2], cols[1]]; A = [aos[0], aos[3], aos[2], aos[1]]; W = [lits[0], lits[3], lits[2], lits[1]]; }
          push(L[q.layer], P, U, C, A[0] + A[2] < A[1] + A[3], W);
        }
      }

      // water and lava: their surface lower than a full block when nothing of the same lies on top
      const liquid = info.water ? 'water' : info.lava ? 'lava' : null;
      if (liquid) {
        const same = liquid === 'water' ? isWater : isLava, f = liquid === 'water' ? prep.water : prep.lava;
        const level = Number(info.props.level || 0);
        const top = same(X, Y + 1, Z) ? 1 : level >= 8 ? 1 : level === 0 ? 8 / 9 : (8 - level) / 9;
        const wr = liquid === 'water' ? ((bc.water >> 16) & 255) / 255 : 1, wg = liquid === 'water' ? ((bc.water >> 8) & 255) / 255 : 1, wb = liquid === 'water' ? (bc.water & 255) / 255 : 1;
        for (const [dir, d] of Object.entries(DIRS)) {
          const nx = X + d[0], ny = Y + d[1], nz = Z + d[2];
          if (same(nx, ny, nz)) continue;
          if (dir !== 'up' && occ(nx, ny, nz)) continue;
          const pts = faceCorners([0, 0, 0], [16, top * 16, 16], dir).map(p => [X - ox + p[0] / 16, Y + p[1] / 16, Z - oz + p[2] / 16]);
          const k = liquid === 'lava' ? 1 : SHADE[dir];
          const c = [wr * k, wg * k, wb * k];
          // the corners as seen from outside run clockwise: turned round, so that lava's side faces outwards (water is
          // drawn from both sides anyway, to be seen from under water too)
          const lw = lightAt(X + d[0], Y + d[1], Z + d[2]), lo = lightAt(X, Y, Z), ll = Math.max(lw >> 4, lo >> 4) * 16 + Math.max(lw & 15, lo & 15);
          push(L[f.layer], [pts[0], pts[3], pts[2], pts[1]], [f.uv[0], f.uv[3], f.uv[2], f.uv[1]], [c, c, c, c], false, [ll, ll, ll, ll]);
        }
      }
    }
  }
  return L;
}

// ---------- the scene (made by start3d) ----------

let canvas, renderer, scene, sky, camera, orbit, worldGroup, box, ray, CFG = {};
let origin = { x: 0, z: 0 }, current = null, me = null;

function resize() {
  const w = canvas.clientWidth || window.innerWidth, h = canvas.clientHeight || window.innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  if (HAND.cam) { HAND.cam.aspect = w / h; HAND.cam.updateProjectionMatrix(); }
}

function label(text, colour) {
  const c = document.createElement('canvas'), g = c.getContext('2d');
  g.font = '600 28px Inter, system-ui, sans-serif';
  const w = Math.ceil(g.measureText(text).width) + 20;
  c.width = w; c.height = 40;
  g.font = '600 28px Inter, system-ui, sans-serif';
  g.fillStyle = 'rgba(0,0,0,.45)'; g.fillRect(0, 0, w, 40);
  g.fillStyle = colour; g.textBaseline = 'middle'; g.fillText(text, 10, 21);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.NoColorSpace;
  // the same size on the screen near and far, and seen through walls, as name tags are in the game
  const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: t, depthTest: false, transparent: true, sizeAttenuation: false }));
  s.scale.set(w / 40 * 0.035, 0.035, 1);
  s.renderOrder = 10;
  return s;
}

function figure(e, colour, w, h, name, nameColour, opacity = 1) {
  const g = new THREE.Group();
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, w), new THREE.MeshBasicMaterial({ color: colour, transparent: opacity < 1, opacity }));
  m.position.y = h / 2;
  g.add(m);
  const edge = new THREE.LineSegments(new THREE.EdgesGeometry(m.geometry), new THREE.LineBasicMaterial({ color: 0x000000 }));
  edge.position.copy(m.position);
  g.add(edge);
  if (name) { const l = label(name, nameColour || '#fff'); l.position.y = h + 0.45; g.add(l); }
  g.position.set(e.x - origin.x, e.y, e.z - origin.z);
  g.rotation.y = -(e.yaw || 0) * Math.PI / 180;
  return g;
}

let prep = null, summary = '', lastChange = 0;
const meshes = new Map(), figures = new Map();
const live = { seq: 0, base: 0, busy: false, ok: false, ent: null };

/** A chunk's meshes made anew from what the page holds of it (none when it is gone). */
function dropArray() { this.array = null; }
/** Neighbours of chunks that came in whole, built again one a frame (their faces against the new chunk). */
const LATER = new Set();
function rebuild(key) {
  LATER.delete(key);
  for (const m of meshes.get(key) || []) {
    worldGroup.remove(m);
    if (!m.userData.shared) m.geometry.dispose();
    if (m.userData.signText) { HELD_MATS.delete(m.material); m.material.map.dispose(); m.material.dispose(); }
  }
  meshes.delete(key);
  const chunk = current.world.chunks.get(key);
  if (!chunk) return 0;
  const L = buildChunk(current.world, prep, chunk, origin.x, origin.z), list = [];
  let quads = 0;
  for (let k = 0; k < 3; k++) {
    if (!L[k].idx.length) continue;
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(L[k].pos, 3));
    geo.setAttribute('uv', new THREE.Float32BufferAttribute(L[k].uv, 2));
    geo.setAttribute('color', new THREE.Float32BufferAttribute(L[k].col, 3));
    geo.setAttribute('light', new THREE.Float32BufferAttribute(L[k].lit, 2));
    geo.setIndex(L[k].pos.length / 3 > 65535 ? new THREE.Uint32BufferAttribute(L[k].idx, 1) : new THREE.Uint16BufferAttribute(L[k].idx, 1));
    geo.computeBoundingSphere();
    // once the graphics card has a chunk's numbers the page's own copy goes (a chunk is built anew, never changed): some
    // 250 MB less for 400 chunks
    for (const at of [...Object.values(geo.attributes), geo.index]) at.onUpload(dropArray);
    const mesh = new THREE.Mesh(geo, prep.mats[k]);
    if (k === 2) mesh.renderOrder = 1;
    worldGroup.add(mesh);
    list.push(mesh);
    quads += L[k].idx.length / 6;
  }
  blockEntities(chunk, list);
  meshes.set(key, list);
  return quads;
}

/** The copy of the world on the panel, drawn whole. */
async function show(fresh) {
  const t0 = performance.now();
  live.busy = true;
  try {
    status(fresh ? `Asking ${who()} for a new copy of the world…` : `Loading the world around ${who()}…`);
    let world = await loadWorld(fresh);
    if (!world && !fresh) { status(`No copy yet: asking ${who()} for one…`); world = await loadWorld(true); }
    // a copy of fewer chunks around the buddy than the view wants (from before the view went further): a new one
    else if (world && !fresh && (world.meta.radius || 0) < RADIUS) { status(`Asking ${who()} for a wider copy…`); world = (await loadWorld(true)) || world; }
    if (!world) { status(`${who(true)} has not sent a copy of the world. Is ${who()} online? Try <b>Refresh</b> in a minute.`); return; }
    if (world.meta.name) BUDDY = world.meta.name;
    const { meta } = world;
    // the numbers of block states hold for the buddy's game session: a copy that does not start with what the page knows
    // comes from a new session, and the page starts over
    const same = prep && prep.version === meta.version && prep.states.every((x, i) => meta.states[i] === x) && prep.biomeIds.every((x, i) => meta.biomes[i] === x);
    if (!same) {
      if (prep) { prep.texture.dispose(); prep.mats.forEach(m => m.dispose()); }
      prep = new Prep(new Jar(await jarOf(meta.version)), meta.version);
      await prep.init(status);
    }
    await prep.addStates(meta.states.slice(prep.states.length), status);
    loadModels();
    await prep.addBiomes(meta.biomes.slice(prep.biomeIds.length));
    const moved = !current || current.world.meta.dim !== meta.dim;
    // where the buddy was drawn until now: a new copy comes after the buddy went further (a walk, a teleport), most of all after the page
    // was in a hidden tab, where it asked for no copy; the view goes along to where the copy has the buddy
    const was = moved ? null : { x: current.world.meta.x, y: current.world.meta.y, z: current.world.meta.z };
    if (moved) origin = { x: Math.floor(meta.x), z: Math.floor(meta.z) };
    clearFigures();
    for (const key of [...meshes.keys()]) { for (const m of meshes.get(key)) { worldGroup.remove(m); if (!m.userData.shared) m.geometry.dispose(); } meshes.delete(key); }
    current = { world };
    scene.fog = new THREE.Fog(sky, meta.radius * 16 * 0.75, meta.radius * 16 * 1.25);
    if (!positioned || moved) { if (mode === 'drive') drive(); else if (mode === 'map') mapView(); else lookAround(); positioned = true; }
    else if (was) caughtUp(was, meta);
    const list = [...world.chunks.values()];
    // nearest chunks first, so that what is around the buddy shows first
    const pcx = Math.floor(meta.x) >> 4, pcz = Math.floor(meta.z) >> 4;
    list.sort((a, b) => Math.hypot(a.cx - pcx, a.cz - pcz) - Math.hypot(b.cx - pcx, b.cz - pcz));
    let quads = 0;
    for (let n = 0; n < list.length; n++) {
      quads += rebuild(list[n].cx + ',' + list[n].cz);
      if (n % 4 === 3) { status(`Building chunks: <b>${n + 1}</b> of ${list.length}`); await sleep(0); }
    }
    entities(meta);
    live.seq = meta.seq || 0;
    live.base = meta.base || 0;
    summary = `<b>${list.length}</b> chunks, ${quads.toLocaleString('en')} faces, built in ${((performance.now() - t0) / 1000).toFixed(1)} s`;
    lastChange = meta.at || Date.now();
    showStatus();
  } finally {
    live.busy = false;
  }
}

function showStatus() {
  if (!current) return;
  const age = Math.max(0, Math.round((Date.now() - lastChange) / 1000));
  const when = age < 90 ? age + ' s' : age < 5400 ? Math.round(age / 60) + ' min' : Math.round(age / 3600) + ' h';
  const dot = live.ok ? '<span style="color:#3fb950">● live</span>' : '<span style="color:var(--soft)">○ not live</span>';
  const fps = FPS.value ? ` · ${FPS.value} fps` : '';
  status(`${dot} · ${summary} · last change ${when} ago · ${current.world.meta.dim.replace('minecraft:', '')}${fps}`);
}
setInterval(showStatus, 1000);

// ---------- figures: the buddy, the others, the rest ----------

function entities(m) {
  if (!current || m.dim !== current.world.meta.dim) return;
  // around the buddy means around the buddy: the view moves along at a walk or a teleport
  const was = current.world.meta;
  if (mode === 'orbit' && Number.isFinite(was.x) && (m.x !== was.x || m.y !== was.y || m.z !== was.z)) {
    const d = new THREE.Vector3(m.x - was.x, m.y - was.y, m.z - was.z);
    camera.position.add(d);
    orbit.target.add(d);
  }
  Object.assign(current.world.meta, { x: m.x, y: m.y, z: m.z, yaw: m.yaw, pitch: m.pitch, eye: m.eye ?? current.world.meta.eye });
  // the buddy's motion per tick, to carry the figure on between two messages (they come every tenth of a second at best)
  if (typeof m.vx === 'number') MOTION.v = { x: m.vx, y: Math.abs(m.vy) > 0.1 ? m.vy : 0, z: m.vz, at: performance.now() };
  track(m);
  // the buddy's clock, for the daylight; the dimension's own light (the nether has no sky but some light everywhere)
  if (typeof m.time === 'number') LIGHT.clock = { time: m.time, at: Date.now() };
  if (m.skylight !== undefined) { LIGHT.sky = m.skylight; LIGHT.ambient.value = m.ambient > 0 ? 0.25 : 0; }
  const seen = new Set();
  const put = (key, e, make) => {
    seen.add(key);
    let f = figures.get(key);
    // new armour or a new skin: the model anew; so too a frame with another item, a cushion dyed, a painting changed
    const anew = f && ((f.g.userData.gear && f.g.userData.gear !== JSON.stringify([e.armor || null, f.g.userData.gear && JSON.parse(f.g.userData.gear)[1]]))
      || (f.g.userData.look !== undefined && f.g.userData.look !== lookOf(e)));
    if (anew) { worldGroup.remove(f.g); dispose(f.g); figures.delete(key); if (key === 'me') me = null; f = null; }
    if (!f) { f = { g: make(), x: e.x, y: e.y, z: e.z, yaw: e.yaw || 0 }; f.g.position.set(e.x - origin.x, e.y, e.z - origin.z); worldGroup.add(f.g); figures.set(key, f); }
    f.x = e.x; f.y = e.y; f.z = e.z; f.yaw = e.yaw || 0;
    // what the crosshair can point at: its number in the buddy's game, its kind or name, its size (or, for what hangs on a wall,
    // its box round its place)
    f.id = e.id; f.kind = e.name || e.kind || 'player'; f.w = e.w || 0.6; f.h = e.h || 1.8; f.box = e.box; f.me = key === 'me';
    // how the model stands: body, head, the walk, a blow, crouching, how hurt (red when the health went down)
    f.body = e.body; f.head = e.head ?? (key === 'me' ? e.yaw : undefined); f.pitch = e.pitch; f.crouch = !!e.crouch; f.baby = !!e.baby;
    f.walkSpeed = e.walk_speed || 0; f.swing = e.swing || 0;
    if (typeof e.hp === 'number') { if (typeof f.hp === 'number' && e.hp < f.hp) f.hurtUntil = performance.now() + 350; f.hp = e.hp; f.maxHp = e.max_hp; }
    if (e.hurt && !f.hurtSeen) f.hurtUntil = performance.now() + 350;
    f.hurtSeen = !!e.hurt;
    f.fire = !!e.fire; f.item = e.item;
    // what the hover names besides the kind: a painting's picture, a cushion's colour, the block that falls
    f.detail = e.variant ? bare(e.variant) : e.kind === 'falling_block' && e.block ? parseState(e.block).name : e.color;
    holdItems(f, e.hand, e.offhand);
  };
  const withName = (g, name, colour) => { if (g && name) { const l = label(name, colour); l.position.y = 2.25; g.add(l); } return g; };
  const mv = Math.hypot(m.vx || 0, m.vz || 0);
  m.walk_speed = Math.min(1, mv * 4);
  HAND.news = { hand: m.hand || null, off: m.offhand || null, using: m.using || null, useHand: m.use_hand === 'off' ? 'off' : 'main', walk: mv };
  // a blow the buddy began (the game's swing starts at 0 and grows; a new one starts it again): the buddy's hand swings for who watches
  if (m.tk !== HAND.tk) {
    const sw = typeof m.swing === 'number' ? m.swing : null;
    if (sw !== null && (HAND.prevSwing === null || sw < HAND.prevSwing)) HAND.streamSwing = performance.now();
    HAND.prevSwing = sw; HAND.tk = m.tk;
    if (Array.isArray(m.breaks)) breakBits(m.breaks);
  }
  put('me', m, () => { me = withName(mobFigure({ kind: 'player', x: m.x, y: m.y, z: m.z, yaw: m.yaw, body: m.body, skin: m.skin, skin_default: m.skin_default, slim: m.slim, armor: m.armor }), current.world.meta.name || BUDDY || 'The buddy', '#e3b341') || figure({ x: m.x, y: m.y, z: m.z, yaw: m.yaw }, 0xe3b341, 0.6, 1.8, current.world.meta.name || BUDDY || 'The buddy', '#e3b341'); me.visible = mode !== 'eyes'; return me; });
  for (const p of m.players || []) put('p' + p.id, p, () => withName(mobFigure(Object.assign({}, p, { kind: 'player' })), p.name, '#fff') || figure(p, 0x4f8fe0, 0.6, 1.8, p.name, '#fff'));
  // what has no body of its own (floating texts, displays, the click boxes of figures) is left out
  const BODILESS = /^(text_display|block_display|item_display|interaction|marker|area_effect_cloud|experience_orb|lightning_bolt)$/;
  for (const e of m.entities || []) {
    if (BODILESS.test(e.kind)) continue;
    const colour = e.kind === 'item' ? 0xf2d14b : e.hostile ? 0xd0453a : e.kind === 'figure' ? 0x9aa0a6 : 0x6fbf5a;
    put('e' + (e.id ?? Math.random()), e, () => (e.kind === 'item' ? itemFigure(e) : objectFigure(e) || mobFigure(e)) || figure(e, colour, Math.max(0.2, e.w || 0.5), Math.max(0.2, e.h || 0.5), null, null, e.hostile ? 0.85 : 0.45));
  }
  for (const [k, f] of figures) if (!seen.has(k)) { worldGroup.remove(f.g); dispose(f.g); figures.delete(k); }
  showCracks(m.cracks || []);
  showBeams(m.beacons || []);
}

// ---------- breaking: the cracks on a block someone is breaking ----------

// The game's own ten pictures (destroy_stage_0 to 9), laid over the block with its own blending: the cracks darken what
// lies under them (DST_COLOR, SRC_COLOR). A box a hair larger than the block, so a plant or a slab gets a cube of cracks.
const cracks = { tex: null, loading: false, meshes: new Map(), geo: null };

async function crackTextures() {
  if (cracks.tex || cracks.loading || !prep) return;
  cracks.loading = true;
  try {
    const names = Array.from({ length: 10 }, (_, i) => `assets/minecraft/textures/block/destroy_stage_${i}.png`);
    const f = await prep.jar.read(names);
    const tex = [];
    for (const n of names) {
      const b = f.get(n);
      if (!b) { tex.push(null); continue; }
      const t = new THREE.Texture(await decodeImage(b));
      t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.colorSpace = THREE.NoColorSpace;
      t.flipY = false; t.generateMipmaps = false; t.needsUpdate = true;
      tex.push(t);
    }
    cracks.tex = tex;
  } catch (e) { console.warn('cracks', e); } finally { cracks.loading = false; }
}

/** The list the mod sends: [x, y, z, stage] for the buddy's own breaking and the others'. */
function showCracks(list) {
  if (!worldGroup) return;
  if (list.length && !cracks.tex) crackTextures();
  const seen = new Set();
  for (const [x, y, z, s] of list) {
    const tex = cracks.tex && cracks.tex[Math.max(0, Math.min(9, s))];
    if (!tex) continue;
    const k = x + ',' + y + ',' + z;
    seen.add(k);
    let m = cracks.meshes.get(k);
    if (!m) {
      cracks.geo = cracks.geo || new THREE.BoxGeometry(1.004, 1.004, 1.004);
      m = new THREE.Mesh(cracks.geo, new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false, alphaTest: 0.1,
        blending: THREE.CustomBlending, blendSrc: THREE.DstColorFactor, blendDst: THREE.SrcColorFactor,
        polygonOffset: true, polygonOffsetFactor: -1, polygonOffsetUnits: -1 }));
      m.renderOrder = 5;
      worldGroup.add(m);
      cracks.meshes.set(k, m);
    }
    m.position.set(x + 0.5 - origin.x, y + 0.5, z + 0.5 - origin.z);
    if (m.material.map !== tex) { m.material.map = tex; m.material.needsUpdate = true; }
  }
  for (const [k, m] of cracks.meshes) if (!seen.has(k)) { worldGroup.remove(m); m.material.dispose(); cracks.meshes.delete(k); }
}

// ---------- the bits of a broken block ----------

// As ClientLevel.addDestroyBlockEffect and TerrainParticle make them: four by four by four bits over the block, each flying
// out from the middle and a little up, falling with the game's gravity (0.04 a tick), slowed by 0.98 a tick and 0.7 on the
// ground, alive 4 to 40 ticks, a quarter of the block's particle texture, 0.1 to 0.2 blocks across, always facing the
// camera. The mod sends what broke near the buddy (World3d.broke: the buddy's own blocks and the other players').
const BITS = { prep: null, list: [], geos: new Map(), mats: new Map(), cells: new Map() };
/** The caches belong to one atlas: a new copy of the world (a new atlas) starts them again. */
function bitsStore() {
  if (BITS.prep !== prep) {
    for (const b of BITS.list) worldGroup.remove(b.m);
    for (const g of BITS.geos.values()) g.dispose();
    for (const m of BITS.mats.values()) m.dispose();
    BITS.list = []; BITS.geos.clear(); BITS.mats.clear(); BITS.cells.clear(); BITS.prep = prep;
  }
  return BITS;
}
function bitCell(state) {
  let c = BITS.cells.get(state);
  if (!c) {
    c = (async () => {
      const s = parseState(state);
      if (INVISIBLE.has(s.name)) return null;
      if (!prep.blockstates.has(s.name)) {
        const f = `assets/minecraft/blockstates/${s.name}.json`;
        const b = (await prep.jar.read([f])).get(f);
        prep.blockstates.set(s.name, b ? JSON.parse(prep.dec.decode(b)) : null);
      }
      const applied = appliedModels(prep.blockstates.get(s.name), s.props);
      if (!applied.length) return null;
      await readModels(applied.flat().map(a => a.model));
      const tex = resolveTexture(flattenModel(prep.models, applied[0][0].model).textures, '#particle');
      if (!tex) return null;
      await prep.addTextures([tex]);
      // tinted as the game tints them, but not the grass block's (its bits are dirt)
      const tint = FOLIAGE_TINT.has(s.name) ? 0x48b518 : GRASS_TINT.has(s.name) && s.name !== 'grass_block' ? 0x91bd59 : FIXED_TINT[s.name] ?? 0xffffff;
      return { cell: prep.cells.get(tex), tint };
    })().catch(() => null);
    BITS.cells.set(state, c);
  }
  return c;
}
function breakBits(list) {
  if (!worldGroup || !prep || !current) return;
  bitsStore();
  for (const [x, y, z, state] of list.slice(0, 16)) {
    if (Math.abs(x - current.world.meta.x) > 48 || Math.abs(z - current.world.meta.z) > 48) continue;
    const p0 = prep;
    bitCell(state).then(c => { if (c && c.cell && worldGroup && prep === p0) spawnBits(x, y, z, c); });
  }
}
function spawnBits(x, y, z, c) {
  let mat = BITS.mats.get(c.tint);
  if (!mat) {
    mat = new THREE.MeshBasicMaterial({ map: prep.texture, alphaTest: 0.5, side: THREE.DoubleSide });
    mat.userData.tint = new THREE.Color(c.tint);
    BITS.mats.set(c.tint, mat);
  }
  for (let xx = 0; xx < 4; xx++) for (let yy = 0; yy < 4; yy++) for (let zz = 0; zz < 4; zz++) {
    if (BITS.list.length > 1500) return;
    const rx = (xx + 0.5) / 4, ry = (yy + 0.5) / 4, rz = (zz + 0.5) / 4;
    // Particle's start: the push from the middle, some random, its speed set to 0.15 to 0.45 times 0.4, and 0.1 up
    let vx = rx - 0.5 + (Math.random() * 2 - 1) * 0.4, vy = ry - 0.5 + (Math.random() * 2 - 1) * 0.4, vz = rz - 0.5 + (Math.random() * 2 - 1) * 0.4;
    const sp = (Math.random() + Math.random() + 1) * 0.15, len = Math.hypot(vx, vy, vz) || 1;
    vx = vx / len * sp * 0.4; vy = vy / len * sp * 0.4 + 0.1; vz = vz / len * sp * 0.4;
    // a quarter of the texture (four by four pixels) at one of sixteen places, as TerrainParticle picks it
    const u0 = Math.floor(Math.random() * 4) * 3, v0 = Math.floor(Math.random() * 4) * 3, key = c.cell.x + ',' + c.cell.y + ',' + u0 + ',' + v0;
    let geo = BITS.geos.get(key);
    if (!geo) {
      geo = new THREE.PlaneGeometry(1, 1);
      // the plane's corners: top left, top right, bottom left, bottom right
      const [a, b] = [prep.uvOf(c.cell, u0, v0), prep.uvOf(c.cell, u0 + 4, v0 + 4)];
      geo.setAttribute('uv', new THREE.Float32BufferAttribute([a[0], a[1], b[0], a[1], a[0], b[1], b[0], b[1]], 2));
      BITS.geos.set(key, geo);
    }
    const m = new THREE.Mesh(geo, mat);
    const size = 0.1 + Math.random() * 0.1;
    m.scale.set(size, size, size);
    m.position.set(x + rx - origin.x, y + ry, z + rz - origin.z);
    m.renderOrder = 4;
    worldGroup.add(m);
    BITS.list.push({ m, vx, vy, vz, age: 0, life: Math.floor(4 / (Math.random() * 0.9 + 0.1)), acc: 0 });
  }
}
/** The bits go on at the game's pace, twenty ticks a second, and always face the camera. */
function bitsTick(dt) {
  if (!BITS.list.length) return;
  const k = LIGHT.models ?? 1;
  for (const mat of BITS.mats.values()) mat.color.copy(mat.userData.tint).multiplyScalar(k);
  const q = camera.getWorldQuaternion(new THREE.Quaternion());
  for (let i = BITS.list.length - 1; i >= 0; i--) {
    const b = BITS.list[i];
    b.acc += dt * 20;
    while (b.acc >= 1 && b.age < b.life) {
      b.acc -= 1; b.age++;
      b.vy -= 0.04;
      const p = b.m.position, nx = p.x + b.vx, ny = p.y + b.vy, nz = p.z + b.vz;
      // the ground stops them, as the game's particles collide with the blocks
      const n = blockAt(Math.floor(nx + origin.x), Math.floor(ny), Math.floor(nz + origin.z)), inf = n >= 0 && prep && prep.infos[n];
      if (inf && inf.occludes) { if (b.vy < 0) { b.vy = 0; b.vx *= 0.7; b.vz *= 0.7; } }
      else p.set(nx, ny, nz);
      b.vx *= 0.98; b.vy *= 0.98; b.vz *= 0.98;
    }
    if (b.age >= b.life) { worldGroup.remove(b.m); BITS.list.splice(i, 1); continue; }
    b.m.quaternion.copy(q);
  }
}

// ---------- beacon beams ----------

// As BeaconRenderer draws them: per part of the beam (its colour changes at stained glass) a turning inner beam of 0.2 in
// the part's colour, drawn solid, and a still glow of 0.25 around it at an eighth of that colour; the texture runs up the
// beam, two and a half times a block in the inner beam and once a block in the glow; the last part goes on to 2048 above
// the beacon, and the beam is wider the further away it is (one block of width per 96 away), always lit. The mod sends a
// beacon only while the game draws its beam (World3d.beacons): with its pyramid and the sky above it free.
const beams = { tex: null, loading: false, list: new Map() };

async function beamTexture() {
  if (beams.tex || beams.loading || !prep) return;
  beams.loading = true;
  try {
    const name = 'assets/minecraft/textures/entity/beacon/beacon_beam.png';
    const b = (await prep.jar.read([name])).get(name);
    if (b) {
      const t = new THREE.Texture(await decodeImage(b));
      t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.colorSpace = THREE.NoColorSpace;
      t.wrapS = THREE.RepeatWrapping; t.wrapT = THREE.RepeatWrapping; t.flipY = false; t.generateMipmaps = false; t.needsUpdate = true;
      beams.tex = t;
    }
  } catch (e) { console.warn('beacon beam', e); } finally { beams.loading = false; }
}

/** Four quads round the beam's axis from y0 to y1 (corners as the game's: [x, z]), the texture's v counting per block. */
function beamGeometry(corners, y0, y1, perBlock) {
  const pos = [], uv = [], idx = [];
  const quad = (a, b) => {
    const n = pos.length / 3;
    pos.push(a[0], y1, a[1], a[0], y0, a[1], b[0], y0, b[1], b[0], y1, b[1]);
    uv.push(1, (y1 - y0) * perBlock, 1, 0, 0, 0, 0, (y1 - y0) * perBlock);
    idx.push(n, n + 1, n + 2, n, n + 2, n + 3);
  };
  const [wn, en, ws, es] = corners;
  quad(wn, en); quad(es, ws); quad(en, es); quad(ws, wn);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  return g;
}

/** The list the mod sends: {x, y, z, beam: [[colour, height], ...]}. */
function showBeams(list) {
  if (!worldGroup) return;
  if (list.length && !beams.tex) beamTexture();
  const seen = new Set();
  for (const b of list) {
    if (!beams.tex || !Array.isArray(b.beam) || !b.beam.length) continue;
    const key = b.x + ',' + b.y + ',' + b.z + ' ' + JSON.stringify(b.beam);
    seen.add(key);
    let g = beams.list.get(key);
    if (!g) {
      g = new THREE.Group();
      const spin = new THREE.Group();
      g.add(spin);
      const r = 0.2, R = 0.25;
      let y0 = 0;
      b.beam.forEach(([colour, height], i) => {
        const y1 = y0 + (i === b.beam.length - 1 ? 2048 : height);
        const solid = new THREE.Mesh(beamGeometry([[0, r], [r, 0], [-r, 0], [0, -r]], y0, y1, 0.5 / r),
          new THREE.MeshBasicMaterial({ map: beams.tex, color: new THREE.Color(colour), side: THREE.DoubleSide }));
        const glow = new THREE.Mesh(beamGeometry([[-R, -R], [R, -R], [-R, R], [R, R]], y0, y1, 1),
          new THREE.MeshBasicMaterial({ map: beams.tex, color: new THREE.Color(colour), transparent: true, opacity: 32 / 255, depthWrite: false, side: THREE.DoubleSide }));
        glow.renderOrder = 4;
        spin.add(solid); g.add(glow);
        y0 += height;
      });
      g.userData.spin = spin;
      worldGroup.add(g);
      beams.list.set(key, g);
    }
    g.position.set(b.x + 0.5 - origin.x, b.y, b.z + 0.5 - origin.z);
  }
  // the texture is shared by every beam: only the beam's own geometry and materials go
  for (const [k, g] of beams.list) if (!seen.has(k)) { worldGroup.remove(g); g.traverse(o => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); }); beams.list.delete(k); }
}

/** Each frame: the inner beam turns and the texture runs up, as the game's (forty ticks a round of the texture's step). */
function beamTick() {
  if (!beams.list.size || !beams.tex) return;
  const anim = (performance.now() / 50) % 40;
  const scroll = -anim;
  const off = scroll * 0.2 - Math.floor(scroll * 0.1);
  beams.tex.offset.y = -1 + (off - Math.floor(off));
  for (const g of beams.list.values()) {
    g.userData.spin.rotation.y = -(anim * 2.25 - 45) * Math.PI / 180;
    const d = Math.hypot(camera.position.x - g.position.x, camera.position.z - g.position.z);
    const k = Math.max(1, d / 96);
    g.scale.set(k, 1, k);
  }
}

// ---------- the game's own models: chests, shulker boxes, heads, bells, pots, and the mobs (models.json) ----------

// The mod exports every model the game draws in code (Models: the parts' faces as four corners of x, y, z and u, v, as
// the game holds them), once per game; the page draws them with the textures of Mojang's jar. Kept per version.
const MODELS = { data: null, loading: null, version: null, tex: new Map(), geo: new Map() };
const HIDDEN = new THREE.MeshBasicMaterial({ visible: false });

async function loadModels() {
  if (!prep) return null;
  if (MODELS.data && MODELS.version === prep.version) return MODELS.data;
  if (MODELS.loading) return MODELS.loading;
  MODELS.loading = (async () => {
    try {
      const k = 'models/' + prep.version;
      let bytes = (await store.getMany([k])).get(k);
      let data = bytes ? JSON.parse(new TextDecoder().decode(bytes)) : null;
      if (!data || data.version !== prep.version) {
        const r = await fetch('/api/models', { cache: 'no-store' });
        if (!r.ok) return null;
        bytes = new Uint8Array(await r.arrayBuffer());
        data = JSON.parse(new TextDecoder().decode(bytes));
        if (data.version === prep.version) await store.putMany([[k, bytes]]);
      }
      MODELS.data = data; MODELS.version = prep.version;
      MODELS.geo.clear();
      // what was drawn before they came is drawn again with them
      if (current) {
        for (const key of [...meshes.keys()]) rebuild(key);
        clearFigures();
        entities(Object.assign({}, current.world.meta, live.ent && live.ent.base === live.base ? live.ent : {}));
      }
      return data;
    } catch (e) { console.warn('models', e); return null; } finally { MODELS.loading = null; }
  })();
  return MODELS.loading;
}

/** The material of one of the jar's textures (assets/minecraft/<path>), shown once it is read; a mesh waits for it. */
function modelMaterial(path, dye) {
  path = path.replace(/^minecraft:/, '');
  if (dye) {
    // dyed leather: the same picture, tinted, as a material of its own
    const k = path + '#' + dye;
    let d = MODELS.tex.get(k);
    if (d) return d;
    const base = modelMaterial(path);
    d = { mat: null, waiting: [], dye };
    MODELS.tex.set(k, d);
    const make = () => { d.mat = base.mat.clone(); d.mat.userData.dye = new THREE.Color('#' + dye); d.mat.color.copy(d.mat.userData.dye); for (const m of d.waiting) m.material = d.mat; d.waiting = []; };
    if (base.mat) make(); else base.waiting.push({ set material(_) { make(); } });
    return d;
  }
  let t = MODELS.tex.get(path);
  if (t) return t;
  t = { mat: null, waiting: [] };
  MODELS.tex.set(path, t);
  const file = 'assets/minecraft/' + path;
  // a player's skin comes through the panel (Mojang's texture server sends no CORS), the rest from the jar
  const bytes = path.startsWith('skin:')
    ? fetch('/api/skin?h=' + path.slice(5)).then(r => r.ok ? r.arrayBuffer() : null).then(a => a && new Uint8Array(a))
    : prep.jar.read([file]).then(f => f.get(file));
  bytes.then(async b => {
    if (!b) return;
    const tx = new THREE.Texture(await decodeImage(b));
    tx.magFilter = THREE.NearestFilter; tx.minFilter = THREE.NearestFilter; tx.colorSpace = THREE.NoColorSpace;
    tx.flipY = false; tx.generateMipmaps = false; tx.needsUpdate = true;
    t.mat = new THREE.MeshBasicMaterial({ map: tx, vertexColors: true, alphaTest: 0.1, side: THREE.DoubleSide });
    for (const m of t.waiting) m.material = t.mat;
    t.waiting = [];
  }).catch(e => console.warn('texture', path, e));
  return t;
}

/** One model's faces as a geometry, through fn (x, y, z of the export to the place in the scene), shaded as blocks are. */
function modelGeometry(key, parts, fn) {
  let g = MODELS.geo.get(key);
  if (g) return g;
  const pos = [], uv = [], col = [], idx = [];
  const a = new THREE.Vector3(), b = new THREE.Vector3(), n = new THREE.Vector3();
  for (const part of parts) {
    const v = part.v;
    for (let q = 0; q + 20 <= v.length; q += 20) {
      const pts = [0, 1, 2, 3].map(i => fn(v[q + i * 5], v[q + i * 5 + 1], v[q + i * 5 + 2]));
      a.set(pts[1][0] - pts[0][0], pts[1][1] - pts[0][1], pts[1][2] - pts[0][2]);
      b.set(pts[3][0] - pts[0][0], pts[3][1] - pts[0][1], pts[3][2] - pts[0][2]);
      n.crossVectors(a, b).normalize();
      const k = Math.abs(n.y) > 0.7 ? (n.y > 0 ? 1 : 0.5) : Math.abs(n.x) > Math.abs(n.z) ? 0.6 : 0.8;
      const o = pos.length / 3;
      for (let i = 0; i < 4; i++) { pos.push(...pts[i]); uv.push(v[q + i * 5 + 3], v[q + i * 5 + 4]); col.push(k, k, k); }
      idx.push(o, o + 1, o + 2, o, o + 2, o + 3);
    }
  }
  g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  g.setIndex(idx);
  g.computeBoundingSphere();
  MODELS.geo.set(key, g);
  return g;
}

function modelMesh(key, parts, fn, texture) {
  const t = modelMaterial(texture);
  const m = new THREE.Mesh(modelGeometry(key, parts, fn), t.mat || HIDDEN);
  if (!t.mat) t.waiting.push(m);
  m.userData.shared = true;
  return m;
}

// facing to the game's yRot (south 0, west 90, north 180, east 270)
const YROT = { south: 0, west: 90, north: 180, east: 270 };
const SKULLS = {
  skeleton_skull: ['minecraft:skeleton_skull#main', 'textures/entity/skeleton/skeleton.png'],
  wither_skeleton_skull: ['minecraft:wither_skeleton_skull#main', 'textures/entity/skeleton/wither_skeleton.png'],
  zombie_head: ['minecraft:zombie_head#main', 'textures/entity/zombie/zombie.png'],
  creeper_head: ['minecraft:creeper_head#main', 'textures/entity/creeper/creeper.png'],
  piglin_head: ['minecraft:piglin_head#main', 'textures/entity/piglin/piglin.png'],
  dragon_head: ['minecraft:dragon_skull#main', 'textures/entity/enderdragon/dragon.png'],
  player_head: ['minecraft:player_head#main', 'textures/entity/player/wide/steve.png'],
};
const SHULKER_TURN = { up: [0, 0, 0], down: [Math.PI, 0, 0], north: [Math.PI / 2, 0, Math.PI], south: [Math.PI / 2, 0, 0], west: [Math.PI / 2, 0, Math.PI / 2], east: [Math.PI / 2, 0, -Math.PI / 2] };

/** What of a block state the game draws with a model of its own (a chest, a head): the meshes for one block of it. */
function blockEntityParts(info) {
  if (info._be !== undefined) return info._be;
  const L = MODELS.data && MODELS.data.layers, n = info.name, pr = info.props || {};
  let out = null;
  const turned = (deg, cx, cz) => m => { m.rotation.y = -deg * Math.PI / 180; m.userData.pivot = [cx, cz]; return m; };
  const chest = n.match(/^(?:waxed_)?(?:(exposed|weathered|oxidized)_)?(copper_chest)$|^(chest|trapped_chest|ender_chest)$/);
  if (L && chest) {
    const base = chest[3] ? { chest: 'normal', trapped_chest: 'trapped', ender_chest: 'ender' }[chest[3]] : 'copper' + (chest[1] ? '_' + chest[1] : '');
    const half = pr.type === 'left' ? 'left' : pr.type === 'right' ? 'right' : '';
    const layer = half ? 'minecraft:double_chest_' + half + '#main' : 'minecraft:chest#main';
    if (L[layer]) out = [{ key: layer, parts: L[layer], fn: (x, y, z) => [x - 0.5, y, z - 0.5], tex: 'textures/entity/chest/' + base + (half ? '_' + half : '') + '.png', yaw: -(YROT[pr.facing] || 0) }];
  }
  const shulker = n.match(/^(?:(\w+)_)?shulker_box$/);
  if (L && shulker && L['minecraft:shulker_box#main']) {
    out = [{ key: 'minecraft:shulker_box#main', parts: L['minecraft:shulker_box#main'], fn: (x, y, z) => [x * 0.9995, (1 - y) * 0.9995, -z * 0.9995],
      tex: 'textures/entity/shulker/shulker' + (shulker[1] ? '_' + shulker[1] : '') + '.png', centre: true, turn: SHULKER_TURN[pr.facing || 'up'] }];
  }
  const skull = n.match(/^(\w+?)_(wall_)?(skull|head)$/);
  if (L && skull) {
    const kind = SKULLS[skull[1] + '_' + skull[3]];
    if (kind && L[kind[0]]) {
      const wall = !!skull[2], deg = wall ? (YROT[pr.facing] || 0) : (Number(pr.rotation) || 0) * 22.5;
      out = [{ key: kind[0], parts: L[kind[0]], fn: (x, y, z) => [-x, -y, z], tex: kind[1], skull: wall ? pr.facing : 'floor', yaw: wall ? deg + 180 : deg }];
    }
  }
  if (L && n === 'bell' && L['minecraft:bell#main']) out = [{ key: 'minecraft:bell#main', parts: L['minecraft:bell#main'], fn: (x, y, z) => [x - 0.5, y, z - 0.5], tex: 'textures/entity/bell/bell_body.png', yaw: 0 }];
  if (L && n === 'decorated_pot' && L['minecraft:decorated_pot_base#main']) {
    const yaw = 180 - (YROT[pr.facing] || 0);
    out = [{ key: 'minecraft:decorated_pot_base#main', parts: L['minecraft:decorated_pot_base#main'], fn: (x, y, z) => [x - 0.5, y, z - 0.5], tex: 'textures/entity/decorated_pot/decorated_pot_base.png', yaw },
      { key: 'minecraft:decorated_pot_sides#main', parts: L['minecraft:decorated_pot_sides#main'], fn: (x, y, z) => [x - 0.5, y, z - 0.5], tex: 'textures/entity/decorated_pot/decorated_pot_side.png', yaw }];
  }
  const sign = n.match(/^(.+?)_(wall_)?sign$/);
  if (sign && !n.includes('hanging')) out = [{ sign: sign[1], wall: !!sign[2], yaw: sign[2] ? (YROT[pr.facing] || 0) : (Number(pr.rotation) || 0) * 22.5 }];
  if (MODELS.data) info._be = out;
  return out;
}

/** A sign as the game draws it (StandingSignRenderer, SignModel: two thirds of the model's size), on its post or on the
 *  wall, and its text on both sides: signs had been a box of planks without their words. Block-local, centred on x and z, y
 *  up from the block's bottom. */
const SIGN_PARTS = {};
function boxQuads(x0, y0, z0, x1, y1, z1, uv, W, H) {
  const v = [], face = (pts, r) => { const t = [[r[0], r[1]], [r[2], r[1]], [r[2], r[3]], [r[0], r[3]]]; pts.forEach((p, i) => v.push(p[0], p[1], p[2], t[i][0] / W, t[i][1] / H)); };
  face([[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]], uv[0]);
  face([[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]], uv[1]);
  face([[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]], uv[2]);
  face([[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]], uv[3]);
  face([[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]], uv[4]);
  face([[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]], uv[5]);
  return v;
}
function signParts(wall) {
  const k = wall ? 'wall' : 'standing';
  if (SIGN_PARTS[k]) return SIGN_PARTS[k];
  const t = 1 / 24, y0 = wall ? 0.27083 : 0.58333, zc = wall ? -0.4375 : 0;
  // the texture's layout (64 x 32): the board's front, back, sides, top and bottom; the post below it
  const parts = [{ p: 'board', v: boxQuads(-0.5, y0, zc - t, 0.5, y0 + 0.5, zc + t, [[2, 2, 26, 14], [28, 2, 52, 14], [26, 2, 28, 14], [0, 2, 2, 14], [26, 0, 50, 2], [2, 0, 26, 2]], 64, 32) }];
  if (!wall) parts.push({ p: 'stick', v: boxQuads(-t, 0, -t, t, y0, t, [[2, 16, 4, 30], [6, 16, 8, 30], [4, 16, 6, 30], [0, 16, 2, 30], [4, 14, 6, 16], [2, 14, 4, 16]], 64, 32) });
  return SIGN_PARTS[k] = parts;
}
// the text colours of the dyes (DyeColor.getTextColor), darkened as the game does for text that does not glow
const DYE_TEXT = { white: 0xffffff, orange: 0xff681f, magenta: 0xff00ff, light_blue: 0x9ac0cd, yellow: 0xffff00, lime: 0xbfff00, pink: 0xff69b4, gray: 0x808080,
  light_gray: 0xd3d3d3, cyan: 0x00ffff, purple: 0xa020f0, blue: 0x0000ff, brown: 0x8b4513, green: 0x00ff00, red: 0xff0000, black: 0x000000 };
/** The little of the game's SNBT a sign's data needs: compounds, lists, strings, numbers. */
function snbt(src) {
  let i = 0;
  const ws = () => { while (i < src.length && /\s/.test(src[i])) i++; };
  const str = () => { const q = src[i++]; let o = ''; while (i < src.length && src[i] !== q) { if (src[i] === '\\') i++; o += src[i++]; } i++; return o; };
  const word = () => { const st = i; while (i < src.length && /[^,:\]\}\s]/.test(src[i])) i++; return src.slice(st, i); };
  const val = () => {
    ws();
    if (src[i] === '{') { i++; const o = {}; for (;;) { ws(); if (src[i] === '}') { i++; return o; } const k = src[i] === '"' || src[i] === "'" ? str() : word(); ws(); i++; o[k] = val(); ws(); if (src[i] === ',') i++; } }
    if (src[i] === '[') { i++; ws(); if (/[BIL];/.test(src.slice(i, i + 2))) i += 2; const a = []; for (;;) { ws(); if (src[i] === ']') { i++; return a; } a.push(val()); ws(); if (src[i] === ',') i++; } }
    if (src[i] === '"' || src[i] === "'") return str();
    return word();
  };
  try { return val(); } catch { return null; }
}
const plain = c => typeof c === 'string' ? c : Array.isArray(c) ? c.map(plain).join('') : c && typeof c === 'object' ? (c.text ?? '') + (c.extra ? plain(c.extra) : '') : '';
/** One side of a sign's text as a picture: four lines, centred, in the colour of its dye. */
function signText(side) {
  if (!side || !side.messages) return null;
  const lines = side.messages.map(plain);
  if (!lines.some(l => l.trim())) return null;
  const glow = side.has_glowing_text === '1b' || side.has_glowing_text === 1;
  let col = DYE_TEXT[side.color || 'black'] ?? 0;
  if (!glow && col) col = ((((col >> 16) & 255) * 0.4) << 16) | ((((col >> 8) & 255) * 0.4) << 8) | ((col & 255) * 0.4);
  const c = document.createElement('canvas'); c.width = 384; c.height = 192;
  const g = c.getContext('2d');
  g.font = '32px "Pixelify Sans", monospace'; g.textAlign = 'center'; g.textBaseline = 'top';
  lines.forEach((l, k) => {
    if (glow) { g.fillStyle = '#' + ((col >> 2) & 0x3f3f3f).toString(16).padStart(6, '0'); for (const [dx, dy] of [[-3, 0], [3, 0], [0, -3], [0, 3]]) g.fillText(l, 192 + dx, 16 + 40 * k + dy, 372); }
    g.fillStyle = '#' + col.toString(16).padStart(6, '0'); g.fillText(l, 192, 16 + 40 * k, 372);
  });
  const tx = new THREE.CanvasTexture(c);
  tx.colorSpace = THREE.NoColorSpace;
  return { tx, glow };
}
function signMeshes(p, x, y, z, list) {
  const at = (m, extra) => { m.rotation.y = -p.yaw * Math.PI / 180 + (extra || 0); m.position.set(x - origin.x + 0.5, y, z - origin.z + 0.5); worldGroup.add(m); list.push(m); return m; };
  at(modelMesh('sign:' + (p.wall ? 'wall' : 'standing'), signParts(p.wall), (a, b, c) => [a, b, c], 'textures/block/' + p.sign + '_sign.png'));
  const be = current.world.be?.get((x >> 4) + ',' + (z >> 4)), d = be && be[x + ',' + y + ',' + z], o = d && d.nbt ? snbt(d.nbt) : null;
  if (!o) return;
  const zc = p.wall ? -0.4375 : 0, y0 = p.wall ? 0.27083 : 0.58333;
  for (const [side, back] of [[o.front_text, false], [p.wall ? null : o.back_text, true]]) {
    const t = signText(side);
    if (!t) continue;
    const mat = new THREE.MeshBasicMaterial({ map: t.tx, transparent: true, alphaTest: 0.05, depthWrite: false });
    if (!t.glow) { mat.color.setScalar(LIGHT.models ?? 1); HELD_MATS.add(mat); }
    const plane = new THREE.PlaneGeometry(1, 0.5);
    plane.translate(0, y0 + 0.25, zc + (back ? -1 : 1) * (1 / 24 + 0.003));
    if (back) plane.rotateY(Math.PI);
    const m = at(new THREE.Mesh(plane, mat));
    m.userData.signText = true;
    m.renderOrder = 2;
  }
}

/** The chest, head or bell models of a chunk, put with its own meshes. */
function blockEntities(chunk, list) {
  if (!MODELS.data || !current) return;
  const m = current.world.meta, infos = prep.infos;
  for (let si = 0; si < chunk.secs.length; si++) {
    const sec = chunk.secs[si];
    if (!sec) continue;
    const b = sec.blocks;
    for (let i = 0; i < 4096; i++) {
      const info = infos[b[i]];
      if (!info || info.none || info.occludes) continue;
      const parts = blockEntityParts(info);
      if (!parts) continue;
      const x = chunk.cx * 16 + (i & 15), z = chunk.cz * 16 + ((i >> 4) & 15), y = m.minY + si * 16 + (i >> 8);
      for (const p of parts) {
        if (p.sign) { signMeshes(p, x, y, z, list); continue; }
        const mesh = placePart(modelMesh(p.key, p.parts, p.fn, p.tex), p, x - origin.x, y, z - origin.z);
        worldGroup.add(mesh);
        list.push(mesh);
      }
    }
  }
}

/** One of blockEntityParts' meshes put in the block whose corner is at x, y, z (the scene's own numbers). */
function placePart(mesh, p, x, y, z) {
  if (p.turn) { mesh.rotation.set(p.turn[0], p.turn[1], p.turn[2], 'XYZ'); mesh.position.set(x + 0.5, y + 0.5, z + 0.5); }
  else if (p.skull) {
    const d = { north: [0, -1], south: [0, 1], west: [-1, 0], east: [1, 0] }[p.skull];
    mesh.rotation.y = -p.yaw * Math.PI / 180;
    if (d) mesh.position.set(x + 0.5 - d[0] * 0.25, y + 0.25, z + 0.5 - d[1] * 0.25);
    else mesh.position.set(x + 0.5, y, z + 0.5);
  } else { mesh.rotation.y = (p.yaw || 0) * Math.PI / 180; mesh.position.set(x + 0.5, y, z + 0.5); }
  return mesh;
}

/** An item's picture from the panel's icons (the game's own textures), once per item. */
const ITEM_TEX = new Map();
function itemTex(id) {
  let tx = ITEM_TEX.get(id);
  if (!tx) {
    tx = new THREE.TextureLoader().load('/icon/' + id + '.png');
    tx.magFilter = THREE.NearestFilter; tx.minFilter = THREE.NearestFilter; tx.colorSpace = THREE.NoColorSpace;
    ITEM_TEX.set(id, tx);
  }
  return tx;
}

/** What is held, as something to hang on a hand: a shield is the game's own shield model (it has no picture of its own),
 *  anything else its picture, size blocks high. */
const HELD_MATS = new Set();
function heldObject(id, size) {
  const L = id === 'shield' && MODELS.data && MODELS.data.layers['minecraft:shield#main'];
  if (L) {
    const g = new THREE.Group(), t = modelMaterial('textures/entity/shield/shield_base_nopattern.png');
    for (const ap of L) {
      const mesh = new THREE.Mesh(modelGeometry('held:shield:' + ap.p, [ap], (x, y, z) => [x, -y, -z]), t.mat || HIDDEN);
      if (!t.mat) t.waiting.push(mesh);
      mesh.userData.shared = true;
      g.add(mesh);
    }
    g.scale.setScalar(size / 1.375);
    g.userData.shield = true;
    return g;
  }
  const mat = new THREE.MeshBasicMaterial({ map: itemTex(id), transparent: true, alphaTest: 0.1, side: THREE.DoubleSide });
  mat.color.setScalar(LIGHT.models ?? 1);
  HELD_MATS.add(mat);
  const m = new THREE.Mesh(new THREE.PlaneGeometry(size, size), mat);
  m.userData.held = true;
  return m;
}
function dropHeld(o) {
  o.parent?.remove(o);
  o.traverse(c => { if (c.userData.shared) return; if (c.material) { HELD_MATS.delete(c.material); c.material.dispose(); } if (c.geometry) c.geometry.dispose(); });
}

/** What a model holds, on the hand of its arm (a player, a zombie, a skeleton's bow), seen from the side as the game
 *  draws it in the third person; it moves with the arm. Changed only when what it holds changed. */
function holdItems(f, hand, off) {
  const P = f.g.userData.parts, key = (hand || '') + '|' + (off || '');
  if (!P || f.g.userData.held === key) return;
  f.g.userData.held = key;
  for (const m of f.g.userData.heldMeshes || []) dropHeld(m);
  f.g.userData.heldMeshes = [];
  const s = f.g.userData.scale || 1;
  for (const [arm, id, side] of [['right_arm', hand, -1], ['left_arm', off, 1]]) {
    const host = P[arm];
    if (!id || !host) continue;
    const m = heldObject(id, (id === 'shield' ? 14 : 10) / 16 * s);
    if (m.userData.shield) {
      // a shield on the outside of the forearm, its face outward
      m.rotation.y = side * Math.PI / 2;
      m.position.set(side * s * 3 / 16, -s * 7 / 16, s * 1 / 16);
    } else {
      // the picture's right is forward, its top up: a sword points forward and up from the fist, as in the game
      m.rotation.y = -Math.PI / 2;
      m.position.set(side * s / 16, -s * 7 / 16, s * 5 / 16);
    }
    host.add(m);
    f.g.userData.heldMeshes.push(m);
  }
}

/** The buddy's hands through the buddy's own eyes: the item of each hand at the bottom of the view, the other hand's mirrored, with the
 *  game's movements, simply: a blow swings it, eating or drinking brings it to the middle and bobs it, a bow is drawn back,
 *  a shield raised, a spyglass hides them. A scene of its own on a camera of its own, drawn over the world. */
const HAND = { scene: null, cam: null, main: null, off: null, news: null, swingAt: -1e9, leftWas: false, useWas: false, using: null, useAt: 0, bob: 0,
  tk: null, prevSwing: null, streamSwing: -1e9, streamSeen: -1e9 };
function useKind(id) {
  if (!id) return null;
  if (id === 'bow' || id === 'crossbow') return 'bow';
  if (id === 'shield') return 'shield';
  if (id === 'trident' || id.endsWith('_spear')) return 'spear';
  if (id === 'spyglass') return 'spy';
  return 'eat';
}
function hands(t, dt) {
  const n = HAND.news;
  if (!n) return false;
  if (!HAND.scene) {
    HAND.scene = new THREE.Scene();
    HAND.cam = new THREE.PerspectiveCamera(70, camera.aspect, 0.01, 10);
    for (const k of ['main', 'off']) {
      const m = new THREE.Group();
      m.visible = false;
      m.userData.id = null;
      HAND.scene.add(m);
      HAND[k] = m;
    }
  }
  const held = (CFG.held && CFG.held()) || {};
  // a blow: at a press of the left button, and on and on while it is held (breaking), six ticks each as in the game
  if (held.left && (!HAND.leftWas || t - HAND.swingAt > 300)) HAND.swingAt = t;
  // the right button on something that is not used (a block put down, a door): a swing too
  if (held.use && !HAND.useWas && !useKind(n.using) && n.hand) HAND.swingAt = t;
  HAND.leftWas = !!held.left; HAND.useWas = !!held.use;
  // not steered from this page: the buddy's own blows as the game swung them (the bot's, or another page's)
  if (HAND.streamSwing > HAND.streamSeen) { HAND.streamSeen = HAND.streamSwing; if (!(CFG.steering && CFG.steering())) HAND.swingAt = t; }
  if (n.using !== HAND.using) { HAND.using = n.using; HAND.useAt = t; }
  const kind = useKind(HAND.using), k = 1 - Math.exp(-dt * 18);
  HAND.bob += (n.walk || 0) * dt * 40;
  const bobX = Math.sin(HAND.bob) * 0.012 * Math.min(1, (n.walk || 0) * 5), bobY = -Math.abs(Math.cos(HAND.bob)) * 0.02 * Math.min(1, (n.walk || 0) * 5);
  let any = false;
  for (const side of ['main', 'off']) {
    const m = HAND[side], id = side === 'main' ? n.hand : n.off, mir = side === 'off' ? -1 : 1;
    // the shield model may come later than the first look at it: built again once the models are there
    const want = id ? id + (id === 'shield' && MODELS.data ? '#m' : '') : null;
    if (m.userData.id !== want) {
      m.userData.id = want;
      for (const c of [...m.children]) dropHeld(c);
      if (id) {
        const o = heldObject(id, id === 'shield' ? 0.62 : 0.4);
        // a shield shows its face, a little turned; the picture of anything else as it is
        if (o.userData.shield) o.rotation.y = 0.35 * mir;
        m.add(o);
      }
    }
    m.visible = !!id && kind !== 'spy';
    if (!m.visible) continue;
    any = true;
    // at rest: low at the side, turned a little inward; through the right hand a sword points to the upper left, as in
    // the game (the picture mirrored), through the left to the upper right
    const p = { x: 0.44 * mir, y: -0.36, z: -0.8, rx: 0, ry: -0.35 * mir, rz: 0.05 * mir };
    if (id === 'shield') Object.assign(p, { x: 0.5 * mir, y: -0.46 });
    const used = kind && (n.useHand === side);
    if (used && kind === 'eat') {
      Object.assign(p, { x: 0.14 * mir, y: -0.3, z: -0.58, rx: 0.25, ry: -0.6 * mir, rz: 0.2 * mir });
      // after a moment the bite: up and down, as the game does while the item lasts
      if (t - HAND.useAt > 250) p.y += Math.abs(Math.cos((t - HAND.useAt) / 210 * Math.PI)) * 0.045;
    } else if (used && kind === 'bow') {
      Object.assign(p, { x: 0.2 * mir, y: -0.32, z: -0.62, rz: -0.5 * mir });
      const pull = Math.min(1, (t - HAND.useAt) / 1000);
      p.z += pull * 0.08;
      if (pull >= 1) { p.x += (Math.random() - 0.5) * 0.006; p.y += (Math.random() - 0.5) * 0.006; }
    } else if (used && kind === 'shield') Object.assign(p, { x: 0.3 * mir, y: -0.3, z: -0.62, ry: -0.15 * mir });
    else if (used && kind === 'spear') Object.assign(p, { x: 0.45 * mir, y: -0.22, z: -0.72, rx: -0.7 });
    else if (side === 'main') {
      const sw = (t - HAND.swingAt) / 300;
      if (sw >= 0 && sw < 1) {
        const q = Math.sqrt(sw);
        p.x -= Math.sin(q * Math.PI) * 0.22; p.y += Math.sin(q * Math.PI * 2) * 0.1; p.z -= Math.sin(sw * Math.PI) * 0.12;
        p.rx -= Math.sin(sw * sw * Math.PI) * 0.6; p.rz += Math.sin(q * Math.PI) * 0.5;
      }
    }
    if (!used) { p.x += bobX * mir; p.y += bobY; }
    // the swing at once, the rest glides
    const kk = side === 'main' && t - HAND.swingAt < 300 ? 1 : k;
    m.position.x += (p.x - m.position.x) * kk; m.position.y += (p.y - m.position.y) * kk; m.position.z += (p.z - m.position.z) * kk;
    m.rotation.x += (p.rx - m.rotation.x) * kk; m.rotation.y += (p.ry - m.rotation.y) * kk; m.rotation.z += (p.rz - m.rotation.z) * kk;
    m.scale.x = id === 'shield' ? 1 : -mir;
    m.traverse(c => { if (c.userData.held && c.material) c.material.color.setScalar(LIGHT.models ?? 1); });
  }
  return any;
}

/** An item on the ground: its picture from the panel's icons, as large as the game draws it, turning slowly. */
function itemFigure(e) {
  if (!e.item) return null;
  const tx = itemTex(e.item);
  const g = new THREE.Group();
  const sp = new THREE.Mesh(new THREE.PlaneGeometry(0.5, 0.5), new THREE.MeshBasicMaterial({ map: tx, transparent: true, alphaTest: 0.1, side: THREE.DoubleSide }));
  sp.position.y = 0.3;
  g.add(sp);
  g.userData.spin = sp;
  g.position.set(e.x - origin.x, e.y, e.z - origin.z);
  return g;
}

/** A model's parts (as models.json has them) as meshes in model, each around its pivot and nested as the game nests them
 *  (a hat turns with its head), through fn (the export's x, y, z to the place in the figure), with the material t; by path. */
function layerParts(key, list, fn, t, model) {
  const parts = {};
  for (const part of list) {
    const o = fn(part.o[0], part.o[1], part.o[2]);
    const geo = modelGeometry(key + ':' + part.p, [part], (x, y, z) => { const q = fn(x, y, z); return [q[0] - o[0], q[1] - o[1], q[2] - o[2]]; });
    const mesh = new THREE.Mesh(geo, t.mat || HIDDEN);
    if (!t.mat) t.waiting.push(mesh);
    mesh.userData.shared = true;
    mesh.rotation.order = 'ZYX';
    const parent = part.p.includes('/') ? parts[part.p.slice(0, part.p.lastIndexOf('/'))] : null;
    if (parent) { const po = parent.userData.pivot; mesh.position.set(o[0] - po[0], o[1] - po[1], o[2] - po[2]); parent.add(mesh); }
    else { mesh.position.set(o[0], o[1], o[2]); model.add(mesh); }
    mesh.userData.pivot = o;
    mesh.userData.rest = mesh.position.clone();
    parts[part.p] = mesh;
  }
  return parts;
}

/** A mob as the game draws it: its renderer's model and texture, turned as its body, each part a mesh of its own around
 *  its pivot (nested as the game nests them), so that a head turns, legs walk and arms swing; a texture the export could
 *  not name (a mob of variants: cows, pigs, chickens) is the first of its folder in the jar. */
function mobFigure(e) {
  const E = MODELS.data && MODELS.data.entities;
  // a mannequin (an armour stand of a player's shape) is drawn with a player's model
  const human = e.kind === 'player' || e.kind === 'figure' || e.kind === 'mannequin';
  const kind = human ? (e.slim ? 'player_slim' : 'player_wide') : e.kind;
  const def = E && E[kind];
  if (!def || !def.parts.length) return null;
  let tex = def.tex;
  // a player's or a figure's own skin: by its hash on Mojang's texture server, or the game's own default skin
  const hash = e.skin && (e.skin.match(/\/texture\/([0-9a-f]{20,80})$/) || [])[1];
  if (human && hash) tex = 'skin:' + hash;
  else if (human && e.skin_default) { const d = e.skin_default.replace(/^minecraft:/, ''); tex = d.endsWith('.png') ? d : 'textures/' + d + '.png'; }
  if (!tex || /missingno/.test(tex)) {
    const dir = 'assets/minecraft/textures/entity/' + e.kind + '/';
    const names = [...prep.jar.entries.keys()].filter(f => f.startsWith(dir) && f.endsWith('.png') && !/baby|eyes|overlay|collar|saddle|armor|decor|_e\.png/.test(f));
    const pick = names.find(f => /temperate/.test(f)) || names.find(f => f.endsWith('/' + e.kind + '.png')) || names[0];
    if (!pick) return null;
    tex = pick.slice('assets/minecraft/'.length);
  }
  const s = def.scale || 1;
  // the game: turned by 180 - body yaw, flipped (scale -1, -1, 1), its scale, 1.501 down; the group turns by -yaw
  const fn = (x, y, z) => [s * x, s * (1.501 - y), -s * z];
  const g = new THREE.Group(), model = new THREE.Group();
  g.add(model);
  const t = modelMaterial(tex);
  const parts = layerParts('entity:' + kind, def.parts, fn, t, model);
  // armour: the game's armour layers of this model, on the same parts, with the equipment's texture
  const base = kind === 'player_wide' ? 'minecraft:player' : kind === 'player_slim' ? 'minecraft:player_slim' : 'minecraft:' + e.kind;
  ['helmet', 'chestplate', 'leggings', 'boots'].forEach((slot, i) => {
    const a = e.armor && e.armor[i], L = a && MODELS.data.layers[base + '#' + slot];
    if (!L) return;
    const [asset, dye] = a.split('#');
    const at = modelMaterial('textures/entity/equipment/' + (slot === 'leggings' ? 'humanoid_leggings' : 'humanoid') + '/' + asset + '.png', dye);
    for (const ap of L) {
      const host = parts[ap.p];
      if (!host) continue;
      const o = fn(ap.o[0], ap.o[1], ap.o[2]), ho = host.userData.pivot;
      const geo = modelGeometry('armor:' + base + '#' + slot + ':' + ap.p, [ap], (x, y, z) => { const q = fn(x, y, z); return [q[0] - o[0], q[1] - o[1], q[2] - o[2]]; });
      const mesh = new THREE.Mesh(geo, at.mat || HIDDEN);
      if (!at.mat) at.waiting.push(mesh);
      mesh.userData.shared = true;
      mesh.userData.armor = true;
      mesh.position.set(o[0] - ho[0], o[1] - ho[1], o[2] - ho[2]);
      host.add(mesh);
    }
  });
  g.userData.parts = parts;
  g.userData.model = model;
  g.userData.scale = s;
  g.userData.tex = t;
  g.userData.gear = JSON.stringify([e.armor || null, tex]);
  g.position.set(e.x - origin.x, e.y, e.z - origin.z);
  g.rotation.y = -(e.body ?? e.yaw ?? 0) * Math.PI / 180;
  return g;
}

// ---------- what is no mob: cushions, frames, paintings, boats, minecarts, crystals, falling blocks ----------

// Each as its renderer in the 26.3 client draws it (CushionRenderer, AbstractBoatRenderer, AbstractMinecartRenderer,
// ItemFrameRenderer, PaintingRenderer, EndCrystalRenderer, LeashKnotRenderer, ArrowRenderer, ThrownTridentRenderer,
// FallingBlockRenderer, TntRenderer, ThrownItemRenderer): its model layer from models.json or the block model the world is
// drawn with, through the renderer's own turns, moves and scales. They were a green box. The group turns round y by
// userData.turn (from the figure's yaw, as the renderer turns its pose); what moves inside it moves in userData.tick.

const RAD = Math.PI / 180;
/** The yaw of the side a yaw faces (Direction.fromYRot, then toYRot): south 0, west 90, north 180, east 270. */
const facingYaw = yaw => ((Math.round((yaw || 0) / 90) % 4) + 4) % 4 * 90;
/** What decides how such a thing is drawn: when it changes (another item in a frame, a dyed cushion) it is drawn anew. */
const lookOf = e => [e.color, e.item, e.rotation, e.invisible, e.variant, e.size, e.block, e.facing, e.offset, e.bottom].join('|');

/** The models a list of model ids needs, with their parents, read from the jar into the world's own list. */
async function readModels(ids) {
  let want = new Set(ids.map(bare).filter(id => !prep.models.has(id) && !id.startsWith('builtin/')));
  for (let round = 0; want.size && round < 12; round++) {
    const files = await prep.jar.read([...want].map(modelFile));
    const next = new Set();
    for (const id of want) {
      const b = files.get(modelFile(id));
      const m = b ? JSON.parse(prep.dec.decode(b)) : {};
      prep.models.set(id, m);
      if (m.parent && !prep.models.has(bare(m.parent)) && !bare(m.parent).startsWith('builtin/')) next.add(bare(m.parent));
    }
    want = next;
  }
}

/** Faces in the atlas (block-local, 0 to 1) as one geometry, shaded and tinted as the world's, counter-clockwise outside. */
function facesGeometry(quads, info) {
  const bc = prep.biomes[0], tint = info && info.tintKind && bc ? bc[info.tintKind] : info && info.fixedTint != null ? info.fixedTint : 0xffffff;
  const tc = [((tint >> 16) & 255) / 255, ((tint >> 8) & 255) / 255, (tint & 255) / 255];
  const pos = [], uv = [], col = [], idx = [];
  for (const q of quads) {
    const p = q.pts, k = SHADE[q.shadeDir] ?? 1, c = q.tint ? tc : [1, 1, 1];
    const e1 = [p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]], e2 = [p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]];
    const n = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]];
    const order = n[0] * q.normal[0] + n[1] * q.normal[1] + n[2] * q.normal[2] > 0 ? [0, 1, 2, 3] : [0, 3, 2, 1];
    const o = pos.length / 3;
    for (const i of order) { pos.push(p[i][0], p[i][1], p[i][2]); uv.push(q.auv[i][0], q.auv[i][1]); col.push(c[0] * k, c[1] * k, c[2] * k); }
    idx.push(o, o + 1, o + 2, o, o + 2, o + 3);
  }
  if (!idx.length) return null;
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  g.setIndex(idx);
  g.computeBoundingSphere();
  return g;
}

// What a thing carries that is a block (a falling block, a minecart's load, the frame of an item frame, a block in it),
// with the world's own block models and atlas: per state or item its faces once (a promise), the meshes new per thing.
// The material is as bright as the daylight, as the mobs' (HELD_MATS); none of it is disposed with a figure.
const BLOCKS = { prep: null, made: new Map(), mat: null };
function blockStore() {
  if (BLOCKS.prep !== prep) {
    if (BLOCKS.mat) { HELD_MATS.delete(BLOCKS.mat); BLOCKS.mat.dispose(); }
    BLOCKS.prep = prep; BLOCKS.made.clear();
    BLOCKS.mat = new THREE.MeshBasicMaterial({ map: prep.texture, vertexColors: true, alphaTest: 0.5 });
    BLOCKS.mat.color.setScalar(LIGHT.models ?? 1);
    HELD_MATS.add(BLOCKS.mat);
  }
  return BLOCKS;
}
const blockMesh = geo => { const m = new THREE.Mesh(geo, BLOCKS.mat); m.userData.shared = true; return m; };

/** A block state ("minecraft:chest[facing=north]") as the world draws it, block-local: a promise of a group, or null. */
function blockObject(state) {
  const B = blockStore();
  let made = B.made.get(state);
  if (!made) {
    made = (async () => {
      const s = parseState(state);
      if (INVISIBLE.has(s.name)) return null;
      if (!prep.blockstates.has(s.name)) {
        const f = `assets/minecraft/blockstates/${s.name}.json`;
        const b = (await prep.jar.read([f])).get(f);
        prep.blockstates.set(s.name, b ? JSON.parse(prep.dec.decode(b)) : null);
      }
      await readModels(appliedModels(prep.blockstates.get(s.name), s.props).flat().map(a => a.model));
      const info = prep.info(s);
      await prep.addTextures(info.options.flat().map(q => q.tex));
      prep.finish(info);
      return { info, geo: info.none ? null : facesGeometry(info.options[0] || [], info) };
    })().catch(err => { console.warn('block', state, err); return null; });
    B.made.set(state, made);
  }
  return made.then(m => {
    if (!m || BLOCKS.prep !== prep) return null;
    const g = new THREE.Group();
    // a chest or a head is the game's own model, as in the world; a sign there has no text to show
    const be = MODELS.data && blockEntityParts(m.info);
    if (be) { for (const p of be) if (!p.sign) g.add(placePart(modelMesh(p.key, p.parts, p.fn, p.tex), p, 0, 0, 0)); }
    else if (m.geo) g.add(blockMesh(m.geo));
    return g;
  });
}

/** An item as a frame shows it (ItemDisplayContext.FIXED): a block's own model with its "fixed" display (a block is half
 *  its size there), or else its picture, flat, turned to face out (item/generated's fixed turn). A promise of a group. */
function itemObject(id, glow) {
  const B = blockStore(), key = 'item:' + id;
  let made = B.made.get(key);
  if (!made) {
    made = (async () => {
      const f = `assets/minecraft/items/${id}.json`;
      const b = (await prep.jar.read([f])).get(f);
      const def = b ? JSON.parse(prep.dec.decode(b)).model : null;
      if (!def || def.type !== 'minecraft:model' || !def.model) return null;
      await readModels([def.model]);
      const m = flattenModel(prep.models, def.model);
      if (!m.elements.length) return null;
      const quads = modelQuads(prep.models, { model: def.model }).quads;
      await prep.addTextures(quads.map(q => q.tex));
      prep.finish({ options: [quads] });
      let fixed = null;
      for (let cur = bare(def.model), guard = 0; cur && guard < 20 && !fixed; guard++) {
        const mm = prep.models.get(cur);
        if (!mm) break;
        fixed = mm.display && mm.display.fixed;
        cur = mm.parent ? bare(mm.parent) : null;
      }
      return { geo: facesGeometry(quads, null), fixed: fixed || {} };
    })().catch(err => { console.warn('item', id, err); return null; });
    B.made.set(key, made);
  }
  return made.then(m => {
    const g = new THREE.Group();
    if (m && m.geo && BLOCKS.prep === prep) {
      // ItemTransform: moved (sixteenths), turned (x, then y, then z), scaled; the model round its middle
      const d = m.fixed, t = d.translation || [0, 0, 0], r = d.rotation || [0, 0, 0], s = d.scale || [1, 1, 1];
      g.position.set(t[0] / 16, t[1] / 16, t[2] / 16);
      g.rotation.set(r[0] * RAD, r[1] * RAD, r[2] * RAD, 'XYZ');
      g.scale.set(s[0], s[1], s[2]);
      const mesh = blockMesh(m.geo);
      mesh.position.set(-0.5, -0.5, -0.5);
      g.add(mesh);
      return g;
    }
    // a flat item: its picture where the front of the game's one pixel thick item lies, facing away from the wall
    const mat = new THREE.MeshBasicMaterial({ map: itemTex(id), transparent: true, alphaTest: 0.1, side: THREE.DoubleSide });
    const p = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), mat);
    // a glow frame's item is as bright as the game's full light; the rest as bright as the daylight
    if (!glow) { mat.color.setScalar(LIGHT.models ?? 1); HELD_MATS.add(mat); p.userData.held = true; }
    p.position.z = 1 / 32;
    g.rotation.y = Math.PI;
    g.add(p);
    return g;
  });
}

/** A painting's picture (PaintingRenderer): w by h blocks round its middle, the front at z -1/32 with the picture's u
 *  running against x (it is seen from -z), the back and edges of back.png a block at a time. Quads for modelGeometry. */
function paintingQuads(w, h) {
  const front = [], back = [], t = 1 / 32, x0 = -w / 2, y0 = -h / 2;
  const q = (list, pts) => { for (const p of pts) list.push(...p); };
  q(front, [[x0, -y0, -t, 1, 0], [-x0, -y0, -t, 0, 0], [-x0, y0, -t, 0, 1], [x0, y0, -t, 1, 1]]);
  for (let i = 0; i < w; i++) for (let j = 0; j < h; j++) {
    const a = x0 + i, b = a + 1, c = y0 + j, d = c + 1;
    q(back, [[a, d, t, 0, 0], [b, d, t, 1, 0], [b, c, t, 1, 1], [a, c, t, 0, 1]]);
    // the edges: a sixteenth of the back's picture, on the outer side of the outer blocks
    if (j === h - 1) q(back, [[a, d, -t, 0, 0], [b, d, -t, 1, 0], [b, d, t, 1, 1 / 16], [a, d, t, 0, 1 / 16]]);
    if (j === 0) q(back, [[a, c, t, 0, 0], [b, c, t, 1, 0], [b, c, -t, 1, 1 / 16], [a, c, -t, 0, 1 / 16]]);
    if (i === 0) q(back, [[a, d, -t, 0, 0], [a, d, t, 1 / 16, 0], [a, c, t, 1 / 16, 1], [a, c, -t, 0, 1]]);
    if (i === w - 1) q(back, [[b, d, t, 0, 0], [b, d, -t, 1 / 16, 0], [b, c, -t, 1 / 16, 1], [b, c, t, 0, 1]]);
  }
  return { front: [{ v: front }], back: [{ v: back }] };
}

/** The end crystal's bob (EndCrystalRenderer.getY): blocks, between -1.4 and -0.6. */
function crystalY(age) {
  const f = Math.sin(age * 0.2) / 2 + 0.5;
  return (f * f + f) * 0.4 - 1.4;
}

/** A thing that is no mob as its renderer draws it, or null when the page has no drawing for its kind (the green box). */
function objectFigure(e) {
  if (!prep) return null;
  const L = MODELS.data && MODELS.data.layers, k = e.kind;
  const g = new THREE.Group();
  let turn = null;
  // a model layer as parts in a group of their own (in host, else in g), through fn; its parts by path, or null
  const layer = (key, tex, fn, host) => {
    if (!L || !L[key]) return null;
    const m = new THREE.Group();
    const parts = layerParts('object:' + key, L[key], fn, modelMaterial(tex), m);
    (host || g).add(m);
    return parts;
  };
  const boat = k.match(/^(\w+?)_(chest_)?(boat|raft)$/), cart = /^(\w+_)?minecart$/.test(k);
  if (k === 'cushion') {
    // turned by 180 - the yaw of the side it faces, flipped round x and a quarter block down: 0 to 0.25 above its foot
    if (layer('minecraft:cushion#main', `textures/entity/cushion/${e.color || 'white'}_cushion.png`, (x, y, z) => [x, 0.25 - y, -z]))
      turn = f => (180 - facingYaw(f.yaw)) * RAD;
  } else if (boat) {
    // up 0.375, turned by 180 - yaw, flipped (-1, -1, 1) and a quarter turn: the model's x runs along the boat
    const chest = !!boat[2], wood = boat[1];
    if (layer((chest ? 'minecraft:chest_boat/' : 'minecraft:boat/') + wood + '#main', `textures/entity/${chest ? 'chest_boat' : 'boat'}/${wood}.png`, (x, y, z) => [-z, 0.375 - y, -x]))
      turn = f => (180 - (f.yaw || 0)) * RAD;
  } else if (cart && L && L['minecraft:' + k + '#main']) {
    // up 0.375, turned by 180 - yaw, tilted by its pitch round z; the cart flipped (-1, -1, 1), its load three quarters
    // of a block big, moved by its display offset (sixteenths, 6 the cart's own, 1 a hopper's) and a quarter turn
    const tilt = new THREE.Group();
    tilt.position.y = 0.375;
    g.add(tilt);
    layer('minecraft:' + k + '#main', 'textures/entity/minecart/minecart.png', (x, y, z) => [-x, -y, z], tilt);
    if (e.block) {
      const load = new THREE.Group(), at = new THREE.Group(), off = e.offset ?? (k === 'hopper_minecart' ? 1 : 6);
      load.scale.setScalar(0.75);
      at.position.set(-0.5, (off - 8) / 16, 0.5);
      at.rotation.y = Math.PI / 2;
      load.add(at);
      tilt.add(load);
      blockObject(e.block).then(b => b && at.add(b));
    }
    turn = f => (180 - (f.yaw || 0)) * RAD;
    g.userData.tick = f => { tilt.rotation.z = -(f.pitch || 0) * RAD; };
  } else if (k === 'item_frame' || k === 'glow_item_frame') {
    // its place is the middle of its box against the wall: the renderer moves it 0.46875 out to the block's middle and
    // turns it so (180 - the side's yaw; up and down a quarter turn round x); the frame is the block model round that
    // middle, the item 0.4375 out (0.5 in a hidden frame), turned by its rotation (eighths), half its size
    const side = e.facing || 'south', D = DIRS[side], up = side === 'up' || side === 'down';
    const wall = new THREE.Group();
    wall.position.set(D[0] * 0.46875, D[1] * 0.46875, D[2] * 0.46875);
    wall.rotation.set(up ? -90 * D[1] * RAD : 0, (up ? 180 : 180 - YROT[side]) * RAD, 0, 'XYZ');
    g.add(wall);
    const map = e.item === 'filled_map';
    if (!e.invisible) blockObject(`minecraft:${k}[map=${map}]`).then(b => { if (b) { b.position.set(-0.5, -0.5, -0.5); wall.add(b); } });
    if (e.item) {
      const hold = new THREE.Group();
      hold.position.z = e.invisible ? 0.5 : 0.4375;
      wall.add(hold);
      if (map) {
        // a map fills the frame (MapRenderer, a block square just off the back); what is drawn on it the page does not
        // know, so its picture stands for it, turned by quarters as the game turns a map
        hold.rotation.z = (e.rotation || 0) % 4 * 90 * RAD;
        const mat = new THREE.MeshBasicMaterial({ map: itemTex('filled_map'), transparent: true, alphaTest: 0.1, side: THREE.DoubleSide });
        mat.color.setScalar(LIGHT.models ?? 1);
        HELD_MATS.add(mat);
        const p = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), mat);
        p.userData.held = true;
        p.position.z = -1 / 128;
        p.rotation.y = Math.PI;
        hold.add(p);
      } else {
        hold.rotation.z = (e.rotation || 0) * 45 * RAD;
        hold.scale.setScalar(0.5);
        itemObject(e.item, k === 'glow_item_frame').then(o => o && hold.add(o));
      }
    }
    turn = () => 0;
  } else if (k === 'painting' && e.variant && e.size) {
    // round its middle, turned by 180 - the yaw of the side it faces
    const [w, h] = e.size, q = paintingQuads(w, h), id = (x, y, z) => [x, y, z];
    g.add(modelMesh('painting:' + w + 'x' + h, q.front, id, 'textures/painting/' + bare(e.variant) + '.png'));
    g.add(modelMesh('painting-back:' + w + 'x' + h, q.back, id, 'textures/painting/back.png'));
    turn = () => (180 - YROT[e.facing || 'south']) * RAD;
  } else if (k === 'end_crystal') {
    // twice its model's size, half a block down (in the model's units); the glass bobs and the three cubes tumble
    const parts = layer('minecraft:end_crystal#main', 'textures/entity/end_crystal/end_crystal.png', (x, y, z) => [2 * x, 2 * (y - 0.5), 2 * z]);
    if (parts) {
      if (e.bottom === false && parts.base) parts.base.visible = false;
      const outer = parts.outer_glass, inner = parts['outer_glass/inner_glass'], cube = parts['outer_glass/inner_glass/cube'];
      const tip = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(Math.SQRT1_2, 0, Math.SQRT1_2), Math.PI / 3);
      const spin = new THREE.Quaternion(), Y = new THREE.Vector3(0, 1, 0), phase = (e.id || 0) * 7;
      g.userData.tick = () => {
        const age = performance.now() / 50 + phase;
        spin.setFromAxisAngle(Y, age * 3 * RAD);
        if (outer) { outer.position.y = outer.userData.rest.y + crystalY(age); outer.quaternion.copy(spin).multiply(tip); }
        for (const c of [inner, cube]) if (c) c.quaternion.copy(tip).multiply(spin);
      };
      turn = () => 0;
    }
  } else if (k === 'leash_knot') {
    if (layer('minecraft:leash_knot#main', 'textures/entity/lead_knot/lead_knot.png', (x, y, z) => [-x, -y, z])) turn = () => 0;
  } else if (k === 'arrow' || k === 'spectral_arrow' || k === 'trident') {
    // turned by yaw - 90 and tilted by the pitch round z (a trident a quarter turn more): their yaw is the way they fly
    const tilt = new THREE.Group();
    g.add(tilt);
    const ok = k === 'trident' ? layer('minecraft:trident#main', 'textures/entity/trident/trident.png', (x, y, z) => [x, y, z], tilt)
      : layer('minecraft:arrow#main', `textures/entity/projectiles/${k === 'arrow' ? 'arrow' : 'arrow_spectral'}.png`, (x, y, z) => [x, y, z], tilt);
    if (ok) {
      turn = f => ((f.yaw || 0) - 90) * RAD;
      g.userData.tick = f => { tilt.rotation.z = ((f.pitch || 0) + (k === 'trident' ? 90 : 0)) * RAD; };
    }
  } else if (k === 'falling_block' || k === 'tnt') {
    // the block round the middle of its foot (a lit TNT's flashing and swelling are left out)
    const state = k === 'tnt' ? 'minecraft:tnt' : e.block;
    if (state) {
      blockObject(state).then(b => { if (b) { b.position.set(-0.5, 0, -0.5); g.add(b); } });
      turn = () => 0;
    }
  } else if (e.item) {
    // what is thrown (snowballs, pearls, potions, fireballs, eyes of ender): its picture turned to the camera, as the
    // game draws it, the size of an item on the ground (a fireball three times, a small one three quarters)
    const big = k === 'fireball' ? 3 : k === 'small_fireball' ? 0.75 : 1;
    const mat = new THREE.SpriteMaterial({ map: itemTex(e.item), alphaTest: 0.1 });
    const s = new THREE.Sprite(mat);
    s.userData.held = true;
    if (k !== 'fireball' && k !== 'small_fireball') { mat.color.setScalar(LIGHT.models ?? 1); HELD_MATS.add(mat); }
    s.scale.setScalar(0.5 * big);
    s.position.y = 0.125 * big;
    g.add(s);
    turn = () => 0;
  }
  if (!turn) { dispose(g); return null; }
  g.userData.turn = turn;
  g.userData.look = lookOf(e);
  g.position.set(e.x - origin.x, e.y, e.z - origin.z);
  g.rotation.y = turn(e);
  return g;
}

/** A model's parts moved as the game moves them (HumanoidModel, QuadrupedModel, simply): the head to where it looks, legs
 *  and arms by the walk, the arm by a blow, the body bent when crouching, red for a moment when hurt. */
function animate(f, dt) {
  if (f.g.userData.tick) f.g.userData.tick(f, dt);
  if (f.g.userData.spin) { f.g.userData.spin.rotation.y += dt * 1.5; f.g.userData.spin.position.y = 0.3 + Math.sin(performance.now() / 400) * 0.06; return; }
  const P = f.g.userData.parts;
  if (!P) return;
  const rad = Math.PI / 180;
  const part = n => P[n];
  const set = (n, x, y, z) => { const m = part(n); if (m) { m.rotation.set(x || 0, y || 0, z || 0); } };
  const head = part('head');
  if (head) {
    let yaw = (f.head ?? f.yaw ?? 0) - (f.body ?? f.yaw ?? 0);
    yaw = ((yaw + 540) % 360) - 180;
    head.rotation.set((f.pitch || 0) * rad, -yaw * rad, 0);
  }
  // the walk: the game's walk animation position goes on by its speed each tick
  f.walkPos = (f.walkPos || 0) + (f.walkSpeed || 0) * 20 * dt;
  const w = f.walkPos * 0.6662, sp = Math.min(1, f.walkSpeed || 0);
  const legs = [['right_leg', 0], ['left_leg', Math.PI], ['right_hind_leg', 0], ['left_hind_leg', Math.PI], ['right_front_leg', Math.PI], ['left_front_leg', 0]];
  for (const [n, ph] of legs) if (part(n)) set(n, Math.cos(w + ph) * 1.4 * sp, 0, 0);
  const swing = f.swing ? Math.sin(Math.sqrt(f.swing) * Math.PI) : 0;
  if (part('right_arm')) set('right_arm', Math.cos(w + Math.PI) * sp - swing * 1.2 + (f.crouch ? 0.4 : 0), 0, 0);
  if (part('left_arm')) set('left_arm', Math.cos(w) * sp + (f.crouch ? 0.4 : 0), 0, 0);
  // crouching: the body bent forward, the head and arms lower, as HumanoidModel does (pixels, y down in the model)
  const body = part('body'), s = 1 / 16;
  if (body) body.rotation.x = f.crouch ? 0.5 : 0;
  for (const [n, dy, dz] of [['head', 4.2, 0], ['body', 3.2, 0], ['right_arm', 3.2, 0], ['left_arm', 3.2, 0], ['right_leg', 0, 4], ['left_leg', 0, 4]]) {
    const m = part(n);
    if (!m || !m.userData.rest || m.parent !== f.g.userData.model) continue;
    m.position.copy(m.userData.rest);
    if (f.crouch && P.body && P.right_arm) { m.position.y -= dy * s; m.position.z -= dz * s; }
  }
  // babies at half size; hurt: red for a moment, as the game tints a mob it hits
  f.g.userData.model.scale.setScalar(f.baby ? 0.5 : 1);
  // hurt: red for a moment; burning: an orange flicker (the game draws flames; this says the same)
  const now = performance.now(), red = f.hurtUntil && now < f.hurtUntil;
  const tint = red ? 'red' : f.fire ? 'fire' : '';
  if (tint !== (f.g.userData.tint || '') || tint === 'fire') {
    const t = f.g.userData.tex;
    if (tint) {
      let mat = f.g.userData.tintMat;
      if (!mat || f.g.userData.tint !== tint) { mat?.dispose(); mat = (t.mat || HIDDEN).clone(); f.g.userData.tintMat = mat; }
      const fl = 0.75 + 0.25 * Math.sin(now / 60);
      mat.color = tint === 'red' ? new THREE.Color(1, 0.45, 0.45) : new THREE.Color(1, 0.62 * fl, 0.25 * fl);
      if (f.g.userData.tint !== tint) f.g.traverse(o => { if (o.isMesh && o.userData.shared && !o.userData.armor) o.material = mat; });
    } else { f.g.traverse(o => { if (o.isMesh && o.userData.shared && !o.userData.armor) o.material = t.mat || HIDDEN; }); f.g.userData.tintMat?.dispose(); f.g.userData.tintMat = null; }
    f.g.userData.tint = tint;
  }
}

function clearFigures() {
  for (const f of figures.values()) { worldGroup.remove(f.g); dispose(f.g); }
  figures.clear();
  me = null;
}

/** The figures glide to where the last news has them; through the buddy's eyes the camera goes along. */
const MOTION = { v: null };
// The buddy's places by the game's own ticks (tk, fifty milliseconds each): the view shows the buddy a little in the past,
// between two of them, so that the figure moves as evenly as in the game, whatever the network or the frames do (two ticks in one
// frame, a message late, two at once). The time stamp of the moment a file was written ran unevenly, and the offset
// between the clocks was pulled to every quick message and let go a millisecond a message, a saw that made the figure jerk
// and the position was corrected too hard. Now the offset is the smallest of
// the last three seconds and the delay what the late ones there needed, and both change by little at a time.
const TRACK = [], LATE = [];
const SYNC = { off: null, delay: 90 };
function track(m) {
  const t = typeof m.tk === 'number' ? m.tk * 50 : typeof m.at === 'number' ? m.at : null;
  if (t === null) return;
  const now = performance.now(), off = now - t;
  const last = TRACK[TRACK.length - 1];
  // the buddy's game started anew, or a jump (a teleport, another dimension): no gliding over it, and the clocks counted anew
  if (last && (t < last.t - 1000 || Math.abs(off - (SYNC.off ?? off)) > 1500)) { TRACK.length = 0; LATE.length = 0; SYNC.off = null; }
  else if (last && Math.hypot(m.x - last.x, m.z - last.z) > 12) TRACK.length = 0;
  LATE.push({ now, off });
  while (LATE.length && now - LATE[0].now > 3000) LATE.shift();
  const least = Math.min(...LATE.map(v => v.off));
  // what the late ones of these seconds needed above the quickest, and a little more: the delay to draw the buddy with
  const over = LATE.map(v => v.off - least).sort((a, b) => a - b), need = over[Math.floor(over.length * 0.95)] + 30;
  if (SYNC.off === null) { SYNC.off = least; SYNC.delay = Math.max(60, Math.min(300, need)); }
  else {
    SYNC.off += Math.max(-2, Math.min(2, least - SYNC.off));
    SYNC.delay += Math.max(-1, Math.min(4, Math.max(60, Math.min(300, need)) - SYNC.delay));
  }
  if (last && t <= last.t) return;
  TRACK.push({ t, x: m.x, y: m.y, z: m.z, yaw: m.yaw || 0, pitch: m.pitch || 0 });
  if (TRACK.length > 80) TRACK.shift();
}
/** The two places of TRACK around the moment to draw, and how far between them (k), or null without two. */
function trackAt() {
  if (TRACK.length < 2 || SYNC.off === null) return null;
  const rt = performance.now() - SYNC.off - SYNC.delay;
  const last = TRACK[TRACK.length - 1];
  if (rt >= last.t) return { a: last, b: last, k: 0, past: rt - last.t };
  for (let i = TRACK.length - 2; i >= 0; i--) {
    const a = TRACK[i], b = TRACK[i + 1];
    if (rt >= a.t) return { a, b, k: (rt - a.t) / Math.max(1, b.t - a.t), past: 0 };
  }
  return { a: TRACK[0], b: TRACK[0], k: 0, past: 0 };
}
/** A new copy of the world has the buddy elsewhere than drawn: the view goes along, as it does at every step the buddy
 *  takes (back in the tab, Take control showed the world loading and then nothing, until Play or Around was clicked:
 *  around the buddy the camera stayed where the buddy had been, 1700 blocks off). */
function caughtUp(was, m) {
  const d = new THREE.Vector3(m.x - was.x, m.y - was.y, m.z - was.z);
  if (!Number.isFinite(d.x) || d.lengthSq() === 0) return;
  if (mode === 'orbit') { camera.position.add(d); orbit.target.add(d); }
  else if (mode === 'eyes') camera.position.add(d);
  // the buddy's places of before are of somewhere else: the buddy and the camera through the buddy's eyes at the copy's place, not gliding there
  const last = TRACK[TRACK.length - 1];
  if (last && Math.hypot(m.x - last.x, m.z - last.z) > 12) {
    TRACK.length = 0;
    drivePos.set(m.x - origin.x, m.y + (m.eye || 1.62), m.z - origin.z);
  }
}
/** Where the buddy is to be drawn now. */
function hisPlace(m) {
  const at = trackAt();
  if (at) {
    const { a, b, k, past } = at;
    if (past) {
      // beyond the newest: carried on by the buddy's motion, a little only
      const v = MOTION.v, ticks = v ? Math.min(150, past) / 50 : 0;
      return v ? [a.x + v.x * ticks, a.y + v.y * ticks, a.z + v.z * ticks] : [a.x, a.y, a.z];
    }
    return [a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k];
  }
  const v = MOTION.v;
  if (!v) return [m.x, m.y, m.z];
  const ticks = Math.min(0.3, (performance.now() - v.at) / 1000) * 20;
  return [m.x + v.x * ticks, m.y + v.y * ticks, m.z + v.z * ticks];
}

/** Where the buddy looks now, as the game turned the buddy, tick by tick: what a page that watches through the buddy's eyes follows
 *  (Take control as a spectator mode). Degrees, as the game's yaw and pitch. */
function hisLook(m) {
  const at = trackAt();
  if (!at) return { yaw: m.yaw || 0, pitch: m.pitch || 0 };
  const { a, b, k } = at, d = ((b.yaw - a.yaw) % 360 + 540) % 360 - 180;
  return { yaw: a.yaw + d * k, pitch: a.pitch + (b.pitch - a.pitch) * k };
}

function glide(dt) {
  const k = Math.min(1, dt * 8);
  for (const f of figures.values()) {
    let [fx, fy, fz] = [f.x, f.y, f.z];
    if (f.me && current) [fx, fy, fz] = hisPlace(current.world.meta);
    const kf = f.me ? (TRACK.length >= 2 ? 1 : Math.min(1, dt * 18)) : k;
    f.g.position.x += (fx - origin.x - f.g.position.x) * kf;
    f.g.position.y += (fy - f.g.position.y) * kf;
    f.g.position.z += (fz - origin.z - f.g.position.z) * kf;
    // a mob turns by its body; what is no mob as its renderer turns it
    const want = f.g.userData.turn ? f.g.userData.turn(f) : -(f.body ?? f.yaw) * Math.PI / 180;
    let d = want - f.g.rotation.y;
    d = Math.atan2(Math.sin(d), Math.cos(d));
    f.g.rotation.y += d * k;
    animate(f, dt);
  }
  if ((mode === 'eyes' || mode === 'drive') && current) {
    const m = current.world.meta;
    // driving, the camera follows the buddy closer: what you steer should show without delay
    const kk = mode === 'drive' ? (TRACK.length >= 2 ? 1 : Math.min(1, dt * 18)) : k;
    const at = mode === 'drive' ? drivePos : camera.position;
    const [px, py, pz] = mode === 'drive' ? hisPlace(m) : [m.x, m.y, m.z];
    at.x += (px - origin.x - at.x) * kk;
    at.y += (py + (m.eye || 1.62) - at.y) * kk;
    at.z += (pz - origin.z - at.z) * kk;
    // where the buddy looks, unless the mouse has been turning the view since
    if (mode === 'eyes' && !fly.turned) {
      let dy = (Math.PI - m.yaw * Math.PI / 180) - fly.yaw;
      dy = Math.atan2(Math.sin(dy), Math.cos(dy));
      fly.yaw += dy * k;
      fly.pitch += (-Math.max(-89.9, Math.min(89.9, m.pitch)) * Math.PI / 180 - fly.pitch) * k;
    }
  }
}

function dispose(g) {
  g.traverse(o => { if (o.userData.shared) return; if (o.userData.held) HELD_MATS.delete(o.material); if (o.geometry) o.geometry.dispose(); if (o.material) { if (o.material.map) o.material.map.dispose(); o.material.dispose(); } });
}

// ---------- keeping up ----------

function parseChanges(buf) {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  if (new TextDecoder().decode(buf.subarray(0, 4)) !== 'MQ3L') throw new Error('world3d: not a change');
  let p = 4;
  const mlen = dv.getInt32(p); p += 4;
  const meta = JSON.parse(new TextDecoder().decode(buf.subarray(p, p + mlen))); p += mlen;
  const n = dv.getInt32(p); p += 4;
  const chunks = [];
  for (let c = 0; c < n; c++) {
    const cx = dv.getInt32(p), cz = dv.getInt32(p + 4), whole = buf[p + 8] === 1, ns = dv.getUint16(p + 9); p += 11;
    const secs = [];
    for (let k = 0; k < ns; k++) {
      // 0 blocks (and light), 1 air, 2 air with light
      const index = buf[p], kind = buf[p + 1]; p += 2;
      let sec = null, light = null;
      if (kind === 0) {
        const blocks = new Uint16Array(4096), biomes = new Uint16Array(64);
        for (let i = 0; i < 4096; i++, p += 2) blocks[i] = dv.getUint16(p);
        for (let i = 0; i < 64; i++, p += 2) biomes[i] = dv.getUint16(p);
        sec = { blocks, biomes };
      }
      if (meta.light && kind !== 1) { const r = readLight(buf, p); p = r.p; light = r.l; }
      secs.push({ index, sec, light });
    }
    chunks.push({ cx, cz, whole, secs });
  }
  return { meta, chunks };
}

async function gunzip(bytes) {
  const s = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  return new Uint8Array(await new Response(s).arrayBuffer());
}

/** One file of changes: false when the page has to take the copy again, 'fresh' when it has to ask for a new one. */
async function apply(d) {
  const m = d.meta, w = current.world;
  // another dimension has another height (the nether from y 0, the overworld from -64): a mod from before 0.4.200 sends
  // none with the reset, and the old one drew the nether 64 blocks too low, its roof a floor under the figures' feet; a new copy
  // has it
  if (m.reset && m.minY === undefined && m.dim !== w.meta.dim) return 'fresh';
  if (m.states_from > prep.states.length || m.biomes_from > prep.biomeIds.length) return false;
  await prep.addStates(m.states.slice(prep.states.length - m.states_from));
  await prep.addBiomes(m.biomes.slice(prep.biomeIds.length - m.biomes_from));
  const touched = new Set();
  if (m.reset) {
    for (const key of [...meshes.keys()]) touched.add(key);
    w.chunks.clear();
    w.be.clear();
    w.meta.dim = m.dim;
    if (m.minY !== undefined) Object.assign(w.meta, { minY: m.minY, sections: m.sections, skylight: m.skylight, ambient: m.ambient });
    // where the buddy is in the new dimension: the newest positions, when they are of it already
    const e = live.ent;
    if (e && e.dim === m.dim) Object.assign(w.meta, { x: e.x, y: e.y, z: e.z, yaw: e.yaw, pitch: e.pitch });
  }
  for (const [x, z] of m.removed || []) { w.chunks.delete(x + ',' + z); w.be.delete(x + ',' + z); touched.add(x + ',' + z); }
  for (const [key, be] of Object.entries(m.be || {})) { if (Object.keys(be).length) w.be.set(key, be); else w.be.delete(key); }
  // A block broken or put down builds its own chunk again at once, and a neighbour only when the block lies at the edge
  // against it; a chunk that came in whole (the buddy walked on) at once too, and its neighbours afterwards, one a frame
  // (LATER): rebuilding five whole chunks for every change, and the neighbours of every new row first, held the buddy's own
  // blocks back behind them: block updates came very late
  const later = new Set(), edits = new Set();
  const SIDES = [[-1, 0, i => (i & 15) === 0], [1, 0, i => (i & 15) === 15], [0, -1, i => ((i >> 4) & 15) === 0], [0, 1, i => ((i >> 4) & 15) === 15]];
  for (const c of d.chunks) {
    const key = c.cx + ',' + c.cz;
    let ch = w.chunks.get(key);
    const fresh = c.whole || !ch, edge = new Set();
    let relit = false;
    if (!fresh) for (const s of c.secs) {
      // light that changed without a block (a chunk's light that came after its blocks, a torch's reach): the faces of the
      // neighbours along the edge read this light too, or some chunks were not lit (well)
      if (!relit && !sameLight(ch.light && ch.light[s.index], s.light || null)) relit = true;
      const old = ch.secs[s.index], nw = s.sec;
      if (!old || !nw) { if (old !== nw) SIDES.forEach((_, k) => edge.add(k)); continue; }
      const a = old.blocks, b = nw.blocks;
      for (let i = 0; i < 4096; i++) if (a[i] !== b[i]) SIDES.forEach((sd, k) => { if (sd[2](i)) edge.add(k); });
    }
    if (fresh) { ch = { cx: c.cx, cz: c.cz, secs: new Array(w.meta.sections).fill(null), light: new Array(w.meta.sections).fill(null) }; w.chunks.set(key, ch); }
    if (!ch.light) ch.light = new Array(w.meta.sections).fill(null);
    for (const s of c.secs) { ch.secs[s.index] = s.sec; ch.light[s.index] = s.light || null; }
    touched.add(key);
    if (!fresh) edits.add(key);
    SIDES.forEach(([dx, dz], k) => { const nk = (c.cx + dx) + ',' + (c.cz + dz); if (fresh || relit) later.add(nk); if (!fresh && edge.has(k)) { touched.add(nk); edits.add(nk); } });
  }
  // the buddy's own changes first, then the nearest
  const mx = Math.floor((live.ent && live.ent.x || w.meta.x || 0) / 16), mz = Math.floor((live.ent && live.ent.z || w.meta.z || 0) / 16);
  const far = k => { const [x, z] = k.split(',').map(Number); return Math.max(Math.abs(x - mx), Math.abs(z - mz)); };
  const order = [...touched].sort((a, b) => (edits.has(b) - edits.has(a)) || far(a) - far(b));
  let n = 0;
  for (const key of order) { rebuild(key); if (++n % 6 === 0) await sleep(0); }
  for (const k of later) if (!touched.has(k) && w.chunks.has(k)) LATER.add(k);
  if (m.reset) { origin = { x: Math.floor(w.meta.x), z: Math.floor(w.meta.z) }; clearFigures(); for (const key of [...meshes.keys()]) rebuild(key); if (mode === 'drive') drive(); else if (mode === 'map') mapView(); else lookAround(); }
  live.seq = m.seq;
  lastChange = Date.now();
  window.__changes = (window.__changes || 0) + 1;
  return true;
}

// the buddy's place and what is around the buddy come on one open connection (server-sent events) as soon as the mod writes them;
// the changes of the world are still asked for, less often
const STREAM = { es: null, ok: false, wake: null };
function stream() {
  if (STREAM.es || typeof EventSource === 'undefined') return;
  const es = STREAM.es = new EventSource('/api/world3d/stream');
  es.onmessage = ev => {
    if (CFG.active && !CFG.active()) return;
    try {
      const ent = JSON.parse(ev.data);
      STREAM.ok = true;
      live.ent = ent;
      if (current && !live.busy && ent.base === live.base) entities(ent);
    } catch { }
  };
  es.onerror = () => { STREAM.ok = false; };
  // a change of the world written: ask for it now, not at the next round
  es.addEventListener('live', () => { if (STREAM.wake) STREAM.wake(); });
}

async function keepUp() {
  stream();
  for (;;) {
    // the panel's view while its window is shut asks for nothing: the mod stops writing half a minute later
    if (CFG.active && !CFG.active()) { live.ok = false; await sleep(1000); continue; }
    // nor while its tab is hidden: the browser runs the page's timers once a minute then, and a copy built that way took
    // minutes, its half-built world on screen when the tab came back; it is asked for when the tab is shown again
    if (document.hidden) { live.ok = false; await new Promise(r => document.addEventListener('visibilitychange', r, { once: true })); continue; }
    let more = false;
    try {
      if (current && !live.busy) {
        const r = await fetch(`/api/world3d/live?since=${live.seq}`, { cache: 'no-store' });
        live.ok = r.ok;
        if (r.ok) {
          const buf = new Uint8Array(await r.arrayBuffer());
          const dv = new DataView(buf.buffer);
          let p = 0;
          const len = dv.getUint32(p); p += 4;
          const ent = JSON.parse(new TextDecoder().decode(buf.subarray(p, p + len))); p += len;
          live.ent = ent;
          if (ent.base && ent.base > live.base) { await show(false); continue; }
          if (ent.base === live.base && !STREAM.ok) entities(ent);
          let files = 0, again = false;
          while (p < buf.length) {
            const n = dv.getUint32(p); p += 4;
            const d = parseChanges(await gunzip(buf.subarray(p, p + n))); p += n;
            files++;
            if (d.meta.base !== live.base) { if (d.meta.base > live.base) again = true; break; }
            if (d.meta.seq <= live.seq) continue;
            const done = d.meta.prev === live.seq && await apply(d);
            if (done !== true) { again = done === 'fresh' ? d.meta.dim : true; break; }
          }
          // a new dimension without its height: a new copy, and a while before the next when the mod had made one just
          // before (the panel asks it at most every ten seconds and then hands back the old one)
          if (typeof again === 'string') { await show(true); if (current && current.world.meta.dim !== again) await sleep(10000); continue; }
          if (again) { await show(false); continue; }
          more = files >= 20;
        }
      }
    } catch (e) {
      live.ok = false;
      console.warn(e);
    }
    // driving, five times a second: the mod writes the buddy's place that often then
    if (!more) await new Promise(r => { const t = setTimeout(r, STREAM.ok ? 400 : mode === 'drive' ? 80 : 500); STREAM.wake = () => { clearTimeout(t); STREAM.wake = null; r(); }; });
  }
}

// ---------- the cameras ----------

let positioned = false, mode = 'orbit', orbitAsked = false;
// driving: 0 through the buddy's eyes, 1 behind, 2 in front looking back (F5, as in the game)
let person = 0;
const drivePos = new THREE.Vector3();
const fly = { yaw: 0, pitch: 0, keys: new Set(), locked: false, turned: false };
// the map in 3D (the panel's map card): seen from the south and above, north up as the flat map; dragged over with the
// left button, turned with the right one, zoomed by the map's buttons, the wheel or a double click (left nearer, right
// further), on the buddy again and north up by its centre button
const MAP_DIST = 64, MAP_PITCH = Math.atan2(0.82, 0.57);
const mapCam = { x: 0, y: 64, z: 0, dist: MAP_DIST, yaw: 0, pitch: MAP_PITCH, follow: true, drag: null, moved: false };

function setMode(m) {
  mode = m;
  canvas.style.cursor = m === 'map' ? 'grab' : '';
  for (const id of ['orbit', 'eyes', 'fly']) el(id)?.classList.toggle('on', id === m);
  orbit.enabled = m === 'orbit';
  // through the buddy's eyes the buddy's own box would stand around the camera
  if (me) me.visible = m !== 'eyes' && m !== 'drive';
  if (el('help')) el('help').style.display = m === 'eyes' || m === 'fly' ? 'block' : 'none';
  if (el('cross')) el('cross').style.display = m === 'orbit' ? 'none' : 'block';
  if (m === 'orbit' && document.pointerLockElement === canvas) document.exitPointerLock();
}

function lookAround() {
  // asked while the world loads: around the buddy once it is there, not the view the page started with
  if (!current) { mode = 'orbit'; orbitAsked = true; return; }
  const m = current.world.meta;
  const tx = m.x - origin.x, ty = m.y + 1, tz = m.z - origin.z;
  orbit.target.set(tx, ty, tz);
  camera.position.set(tx + 14, ty + 12, tz + 14);
  orbit.update();
  setMode('orbit');
}

function hisEyes() {
  if (!current) return;
  const m = current.world.meta;
  camera.position.set(m.x - origin.x, m.y + (m.eye || 1.62), m.z - origin.z);
  fly.yaw = Math.PI - m.yaw * Math.PI / 180;
  fly.pitch = -Math.max(-89.9, Math.min(89.9, m.pitch)) * Math.PI / 180;
  fly.turned = false;
  setMode('eyes');
}

/** Driving the buddy from the panel: the camera at the buddy's eyes, looking where the panel says. */
function drive() {
  if (!current) { mode = 'drive'; return; }
  const m = current.world.meta;
  drivePos.set(m.x - origin.x, m.y + (m.eye || 1.62), m.z - origin.z);
  camera.position.copy(drivePos);
  setMode('drive');
}

/** The map card's view: above and south of the buddy, north up. */
function mapView() {
  mapCam.follow = true;
  mapCam.dist = MAP_DIST;
  mapCam.yaw = 0; mapCam.pitch = MAP_PITCH;
  if (current) { const m = current.world.meta; mapCam.x = m.x - origin.x; mapCam.y = m.y; mapCam.z = m.z - origin.z; }
  setMode('map');
}

function startFly() {
  // fly from where the camera is now, looking the same way
  const e = new THREE.Euler().setFromQuaternion(camera.quaternion, 'YXZ');
  fly.yaw = e.y; fly.pitch = e.x;
  setMode('fly');
}

/** The standalone page's own controls: its buttons, flying with the mouse locked. */
function bindControls() {
  if (el('orbit')) el('orbit').onclick = lookAround;
  if (el('eyes')) el('eyes').onclick = hisEyes;
  if (el('fly')) el('fly').onclick = startFly;
  if (el('refresh')) el('refresh').onclick = () => show(true).catch(fail);
  canvas.addEventListener('click', () => { if (mode !== 'orbit' && mode !== 'drive' && mode !== 'map' && !document.pointerLockElement) canvas.requestPointerLock?.(); });
  document.addEventListener('pointerlockchange', () => { fly.locked = document.pointerLockElement === canvas; });
  document.addEventListener('mousemove', e => {
    if (!fly.locked) return;
    fly.yaw -= e.movementX * 0.0025;
    fly.turned = true;
    fly.pitch = Math.max(-1.55, Math.min(1.55, fly.pitch - e.movementY * 0.0025));
  });
  document.addEventListener('keydown', e => {
    if (mode === 'orbit' || mode === 'drive' || mode === 'map' || e.target.closest?.('input,textarea')) return;
    fly.keys.add(e.code);
    if (['Space', 'KeyW', 'KeyA', 'KeyS', 'KeyD'].includes(e.code)) e.preventDefault();
  });
  document.addEventListener('keyup', e => fly.keys.delete(e.code));
  window.addEventListener('blur', () => fly.keys.clear());
}

// ---------- what the mouse or the crosshair points at ----------

let mouse = null, lastHit = null;

function blockAt(x, y, z) {
  const { world } = current, m = world.meta;
  const si = (y - m.minY) >> 4;
  if (si < 0 || si >= m.sections) return -1;
  const c = world.chunks.get((x >> 4) + ',' + (z >> 4));
  const s = c && c.secs[si];
  return s ? s.blocks[((((y - m.minY) & 15) * 16) + (z & 15)) * 16 + (x & 15)] : -1;
}

const FACE = [['east', 'west'], ['up', 'down'], ['south', 'north']];

/** The block the mouse (or, with the mouse locked or while driving, the crosshair) points at, and the face it hits. */
function pick() {
  if (!current) return null;
  const at = fly.locked || (mode !== 'orbit' && mode !== 'map') ? { x: 0, y: 0 } : mouse;
  if (!at) return null;
  ray.setFromCamera(at, camera);
  // driving seen from behind or in front: what the buddy reaches is still what is in front of the buddy's eyes, as in the game
  if (mode === 'drive' && person) { ray.ray.origin.copy(drivePos); ray.ray.direction.set(0, 0, -1).applyEuler(new THREE.Euler(fly.pitch, fly.yaw, 0, 'YXZ')); }
  const o = ray.ray.origin, d = ray.ray.direction;
  // walk the grid of blocks along the ray (Amanatides and Woo)
  let x = Math.floor(o.x + origin.x), y = Math.floor(o.y), z = Math.floor(o.z + origin.z);
  const px = o.x + origin.x, py = o.y, pz = o.z + origin.z;
  const step = [Math.sign(d.x), Math.sign(d.y), Math.sign(d.z)];
  const tDelta = [Math.abs(1 / d.x), Math.abs(1 / d.y), Math.abs(1 / d.z)];
  const next = (p, c, s) => s > 0 ? c + 1 - p : p - c;
  const tMax = [next(px, x, step[0]) * tDelta[0], next(py, y, step[1]) * tDelta[1], next(pz, z, step[2]) * tDelta[2]];
  // the nearest figure the ray goes through (not the buddy): a block in front of it hides it
  let ent = null;
  const bx = new THREE.Box3(), hitAt = new THREE.Vector3();
  for (const f of figures.values()) {
    if (f.me || f.id === undefined) continue;
    const p = f.g.position, b = f.box;
    if (b) { bx.min.set(p.x + b[0], p.y + b[1], p.z + b[2]); bx.max.set(p.x + b[3], p.y + b[4], p.z + b[5]); }
    else { bx.min.set(p.x - f.w / 2, p.y, p.z - f.w / 2); bx.max.set(p.x + f.w / 2, p.y + f.h, p.z + f.w / 2); }
    if (ray.ray.intersectBox(bx, hitAt)) { const t = hitAt.distanceTo(o); if (!ent || t < ent.dist) ent = { entity: f.id, kind: f.kind, dist: t, fig: f }; }
  }
  // a camera that is not the buddy's eyes can stand in the rock: the map hangs 52 blocks above the buddy, so with the buddy in a cave it is
  // in the stone, which has no faces drawn there and is seen through. The rock it starts in is gone through, up to the
  // first air or water: what the mouse is on is the first face that is drawn (with a buddy mining at y -35, every click
  // on the 3D map hit the block round the camera and offered no Walk there)
  const solid = i => i && !i.none && !i.water && !i.lava;
  let axis = -1, buried = mode !== 'drive' && mode !== 'eyes';
  for (let i = 0; i < 400; i++) {
    const idx = blockAt(x, y, z), info = idx >= 0 ? prep.infos[idx] : null;
    if (buried && !solid(info)) buried = false;
    if (info && !info.none && !buried) {
      const face = axis < 0 ? null : FACE[axis][step[axis] > 0 ? 1 : 0];
      const dist = axis < 0 ? 0 : tMax[axis] - tDelta[axis];
      if (ent && ent.dist < dist) return ent;
      return { x, y, z, info, face, dist };
    }
    axis = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
    if (tMax[axis] > 300) break;
    if (axis === 0) x += step[0]; else if (axis === 1) y += step[1]; else z += step[2];
    tMax[axis] += tDelta[axis];
  }
  return ent;
}

// ---------- the camera behind or in front of the buddy (F5) stops at a block, as the game's does ----------

/** What of a block stops the camera: the box around its model, or nothing for air, fluids and plants (whose faces all
 *  stand slanted, as grass and flowers), which the game's camera goes through. */
function obstacle(info) {
  if (info._box !== undefined) return info._box;
  let box = null;
  if (!info.none && !info.water && !info.lava) {
    let straight = false;
    const lo = [1, 1, 1], hi = [0, 0, 0];
    for (const q of info.options[0] || []) {
      const p = q.pts;
      const u = [p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]], v = [p[3][0] - p[0][0], p[3][1] - p[0][1], p[3][2] - p[0][2]];
      const n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]];
      const len = Math.hypot(...n) || 1;
      if (Math.max(Math.abs(n[0]), Math.abs(n[1]), Math.abs(n[2])) / len > 0.99) straight = true;
      for (const pt of p) for (let i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], pt[i]); hi[i] = Math.max(hi[i], pt[i]); }
    }
    if (straight && hi[0] > lo[0] && hi[1] > lo[1] && hi[2] > lo[2]) box = [lo, hi];
  }
  info._box = box;
  return box;
}

/** How far a ray from (px, py, pz) along d goes before a block stops it, up to max (the scene's coordinates). */
function castBlocks(px, py, pz, d, max) {
  let x = Math.floor(px + origin.x), y = Math.floor(py), z = Math.floor(pz + origin.z);
  const wx = px + origin.x, wz = pz + origin.z, w = [wx, py, wz], dir = [d.x, d.y, d.z];
  const step = [Math.sign(d.x), Math.sign(d.y), Math.sign(d.z)];
  const tDelta = [Math.abs(1 / d.x), Math.abs(1 / d.y), Math.abs(1 / d.z)];
  const next = (p, c, s) => s > 0 ? c + 1 - p : p - c;
  const tMax = [next(wx, x, step[0]) * tDelta[0], next(py, y, step[1]) * tDelta[1], next(wz, z, step[2]) * tDelta[2]];
  for (let i = 0; i < 64; i++) {
    const idx = blockAt(x, y, z), box = idx >= 0 ? obstacle(prep.infos[idx]) : null;
    if (box) {
      // the ray through the model's box in this block (slabs per axis)
      let t0 = 0, t1 = max;
      for (let k = 0; k < 3 && t0 <= t1; k++) {
        const c = [x, y, z][k];
        if (Math.abs(dir[k]) < 1e-9) { if (w[k] < c + box[0][k] || w[k] > c + box[1][k]) t1 = -1; continue; }
        let a = (c + box[0][k] - w[k]) / dir[k], b = (c + box[1][k] - w[k]) / dir[k];
        if (a > b) [a, b] = [b, a];
        t0 = Math.max(t0, a); t1 = Math.min(t1, b);
      }
      if (t0 <= t1) return Math.min(max, t0);
    }
    const axis = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
    if (tMax[axis] > max) break;
    if (axis === 0) x += step[0]; else if (axis === 1) y += step[1]; else z += step[2];
    tMax[axis] += tDelta[axis];
  }
  return max;
}

/** The game's getMaxZoom: eight rays from the corners of a small box around the buddy's eyes, the camera at the shortest. */
function cameraDistance(from, d, max) {
  let best = max;
  for (let i = 0; i < 8; i++) {
    const ox = ((i & 1) * 2 - 1) * 0.1, oy = ((i >> 1 & 1) * 2 - 1) * 0.1, oz = ((i >> 2 & 1) * 2 - 1) * 0.1;
    best = Math.min(best, castBlocks(from.x + ox, from.y + oy, from.z + oz, d, best));
  }
  return best;
}

const escHtml = t => String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;');

/** The block's own data as the game has it: a sign's text, a banner, a head, a spawner (a chest's contents only once
 *  it was opened). */
function blockData(h) {
  const be = current.world.be?.get((h.x >> 4) + ',' + (h.z >> 4));
  return be && be[h.x + ',' + h.y + ',' + h.z];
}

/* What lay in a container the buddy had open (the mod's containers.json and the base's chests, through /api/containers): a chest's
   items never reach the block on the client, so the hover shows the last look. Asked again at most every five seconds,
   while the mouse is on one. */
const CONTAINER = /(^|_)(chest|barrel|shulker_box|hopper|dispenser|dropper|furnace|smoker|crafter|brewing_stand)$/;
let CONT = { at: 0, map: {}, asking: false };
function contentsOf(h) {
  if (!CONTAINER.test(h.info.name) || h.info.name === 'ender_chest') return null;   // an ender chest's items are the buddy's own, kept nowhere
  if (Date.now() - CONT.at > 5000 && !CONT.asking) {
    CONT.asking = true;
    fetch('/api/containers').then(r => r.json()).then(j => { CONT = { at: Date.now(), map: j.containers || {}, asking: false }; showPick(); })
      .catch(() => { CONT.asking = false; CONT.at = Date.now(); });
  }
  return CONT.map[current.world.meta.dim + '|' + h.x + ',' + h.y + ',' + h.z] || false;
}
function contentsHtml(c) {
  if (c === null) return '';
  const box = 'margin-top:6px;padding-top:6px;border-top:1px solid rgba(255,255,255,.12)';
  if (!c) return `<div style="${box};color:var(--soft)">Contents not known: the server sends them only while ${who()} has the container open. Open it once and they show here.</div>`;
  const totals = new Map();
  if (c.slots) for (const [, id, n] of c.slots) totals.set(id, (totals.get(id) || 0) + n);
  else for (const [id, n] of c.items || []) totals.set(id, n);
  const age = Math.max(0, Date.now() - (c.seen || 0)), m = Math.floor(age / 60000);
  const when = m < 1 ? 'just now' : m < 60 ? m + ' min ago' : m < 1440 ? Math.floor(m / 60) + ' h ago' : Math.floor(m / 1440) + ' d ago';
  const used = c.slots ? c.slots.length + (c.size ? ' of ' + c.size + ' slots' : ' slots') : '';
  const rows = [...totals].sort((a, b) => b[1] - a[1]).map(([id, n]) => {
    const pic = CFG.itemIcon ? CFG.itemIcon(id) : '';
    return `<div style="display:flex;gap:6px;align-items:center">${pic ? `<img src="${pic}" alt="" style="width:16px;height:16px;image-rendering:pixelated">` : ''}` +
      `<span style="min-width:2.5em;text-align:right;font-variant-numeric:tabular-nums">${n}</span><span>${escHtml(CFG.itemName ? CFG.itemName(id) : id.replace(/^minecraft:/, '').replace(/_/g, ' '))}</span></div>`;
  });
  return `<div style="${box}"><span style="color:var(--soft)">Contents, seen ${when}${used ? ' · ' + used : ''}</span>` +
    (rows.length ? `<div style="margin-top:4px;max-height:30vh;overflow:auto;display:flex;flex-direction:column;gap:2px">${rows.join('')}</div>` : '<div>Empty</div>') + '</div>';
}

/* The block's data with what lay in it as the game writes a container's items, so a click copies something that can go
   on a test server as it is (/data merge block, or behind the block in /setblock). A slot is [slot, id, count, components?]
   (Containers in the mod); of a double chest each half has its own 27, the right half the menu's first (ChestBlock: RIGHT
   is the combiner's first). */
function withItems(nbt, c, h) {
  if (!c || !Array.isArray(c.slots) || /(^|[{,])\s*Items\s*:/.test(nbt || '')) return nbt;
  let slots = c.slots;
  const half = h.info.props.type;
  if (c.size === 54 && (half === 'left' || half === 'right'))
    slots = slots.filter(sl => half === 'right' ? sl[0] < 27 : sl[0] >= 27).map(sl => [half === 'right' ? sl[0] : sl[0] - 27, ...sl.slice(1)]);
  const items = slots.map(([n, id, count, comp]) => `{Slot:${n}b,id:"${id}",count:${count}${comp ? ',components:' + comp : ''}}`);
  const inner = nbt && nbt.trim() !== '{}' ? nbt.trim().slice(1, -1).trim() : '';
  return '{' + (inner ? inner + ',' : '') + 'Items:[' + items.join(',') + ']}';
}

function showPick() {
  const h = lastHit = pick();
  // the page shows what the mouse is on in a menu there (its data): the crosshair's block waits (that was the flicker)
  let hv = el('hover');
  if (hv && hv.dataset.item === '1') hv = null;
  if (!h) { box.visible = false; if (hv) hv.style.display = 'none'; return; }
  if (h.entity !== undefined) {
    box.visible = false;
    if (!hv) return;
    const f = h.fig, hp = f && typeof f.hp === 'number' ? ` · <span style="color:#ff6b6b">❤</span> ${Math.round(f.hp * 10) / 10}${f.maxHp ? ' / ' + Math.round(f.maxHp) : ''}` : '';
    // an item on the ground by its item; a frame with what is in it, a painting with its picture
    const what = f && (f.item || f.detail);
    const name = what ? (f.kind === 'item' || f.kind === what ? what : f.kind + ': ' + what) : h.kind;
    const html = `<b>${escHtml(String(name).replace(/_/g, ' '))}</b> at ${h.dist.toFixed(1)} blocks${hp}${f && f.fire ? ' · <span style="color:#ff9a3c">burning</span>' : ''}`;
    hv.dataset.nbt = '';
    if (hv.dataset.html !== html) { hv.dataset.html = html; hv.innerHTML = html; }
    hv.style.display = 'block';
    return;
  }
  box.visible = true;
  box.position.set(h.x - origin.x + 0.5, h.y + 0.5, h.z - origin.z + 0.5);
  if (!hv) return;
  const props = Object.entries(h.info.props).filter(([k]) => k !== 'waterlogged' || h.info.props.waterlogged === 'true').map(([k, v]) => `${k}=${v}`).join(', ');
  const be = blockData(h), cont = contentsOf(h);
  const data = withItems(be && be.nbt ? be.nbt : '', cont, h);
  const html = `<b>${h.info.name.replace(/_/g, ' ')}</b> at ${h.x} ${h.y} ${h.z}` + (props ? `<br><span style="color:var(--soft)">${props}</span>` : '') +
    contentsHtml(cont) +
    (data && data !== '{}' ? `<div class="nbt" style="margin-top:4px;color:var(--soft);font:12px/1.35 ui-monospace,monospace;white-space:pre-wrap;word-break:break-all;max-height:30vh;overflow:auto">${escHtml(data.length > 1500 ? data.slice(0, 1500) + '…' : data)}</div>` : '');
  // the whole data for a click that copies it (the page): what is shown is cut at 1500 characters
  hv.dataset.nbt = data && data !== '{}' ? data : '';
  if (hv.dataset.html !== html) { hv.dataset.html = html; hv.innerHTML = html; }
  hv.style.display = 'block';
}

// ---------- the loop ----------

let lastT = performance.now(), lastPick = 0;
const DAY_SKY = new THREE.Color(0x78a7ff), NIGHT_SKY = new THREE.Color(0x0a0d18), NO_SKY = new THREE.Color(0x1a0f0f);
const FPS = { n: 0, since: 0, value: 0 };
function frame(t) {
  const dt = Math.min(0.1, (t - lastT) / 1000);
  lastT = t;
  // frames drawn a second, for the status line (a slow machine makes moving look jerky too)
  FPS.n++;
  if (t - FPS.since >= 1000) { FPS.value = Math.round(FPS.n * 1000 / (t - FPS.since)); FPS.n = 0; FPS.since = t; }
  // the time of day goes on between two messages, twenty ticks a second
  if (LIGHT.clock) {
    const d = LIGHT.sky === false ? 0 : daylight(LIGHT.clock.time + (Date.now() - LIGHT.clock.at) / 50);
    LIGHT.day.value = 0.15 + 0.85 * d;
    sky.copy(LIGHT.sky === false ? NO_SKY : NIGHT_SKY).lerp(DAY_SKY, LIGHT.sky === false ? 0 : d);
    if (scene.fog) scene.fog.color.copy(sky);
    // the game's models (mobs, chests) as bright as daylight on them; their own place's light is not looked at yet
    const l = LIGHT.day.value, k = brighten(Math.max(LIGHT.ambient.value, 0.06 + 0.94 * l / (4 - 3 * l)));
    for (const tm of MODELS.tex.values()) {
      if (!tm.mat) continue;
      if (tm.mat.userData.dye) tm.mat.color.copy(tm.mat.userData.dye).multiplyScalar(k);
      else if (tm.mat.color.r !== k) tm.mat.color.setScalar(k);
    }
    for (const hm of HELD_MATS) if (hm.color.r !== k) hm.color.setScalar(k);
    LIGHT.models = k;
  }
  if (mode === 'orbit') orbit.update();
  else if (mode === 'map') {
    if (mapCam.follow && current) {
      const m = current.world.meta, k = Math.min(1, dt * 6);
      mapCam.x += (m.x - origin.x - mapCam.x) * k; mapCam.y += (m.y - mapCam.y) * k; mapCam.z += (m.z - origin.z - mapCam.z) * k;
    }
    mapCamera();
    if (me) me.visible = true;
  } else if (mode === 'drive') {
    // where the buddy looks: the panel's own sum, at once, so that turning the mouse turns the view without waiting for the buddy; and
    // while the page only watches, the buddy's own look as the game turned it (the panel's look is null then)
    const v = (CFG.look && CFG.look()) || (current ? hisLook(current.world.meta) : null);
    if (v) { fly.yaw = Math.PI - v.yaw * Math.PI / 180; fly.pitch = -Math.max(-89.9, Math.min(89.9, v.pitch)) * Math.PI / 180; }
    camera.rotation.set(fly.pitch, fly.yaw, 0, 'YXZ');
    camera.position.copy(drivePos);
    if (person) {
      const d = new THREE.Vector3();
      camera.getWorldDirection(d);
      // four blocks away, or nearer where a block is in the way (the game's camera does not go through walls)
      if (person === 2) { camera.position.addScaledVector(d, current ? cameraDistance(drivePos, d, 4) : 4); camera.rotation.set(-fly.pitch, fly.yaw + Math.PI, 0, 'YXZ'); }
      else { const back = d.clone().negate(); camera.position.addScaledVector(back, current ? cameraDistance(drivePos, back, 4) : 4); }
    }
    if (me) me.visible = person > 0;
  } else {
    camera.rotation.set(fly.pitch, fly.yaw, 0, 'YXZ');
    const k = fly.keys, speed = (k.has('ControlLeft') || k.has('ControlRight') ? 60 : 20) * dt;
    const fwd = new THREE.Vector3(-Math.sin(fly.yaw), 0, -Math.cos(fly.yaw)), right = new THREE.Vector3(Math.cos(fly.yaw), 0, -Math.sin(fly.yaw));
    const mv = new THREE.Vector3();
    if (k.has('KeyW')) mv.add(fwd);
    if (k.has('KeyS')) mv.sub(fwd);
    if (k.has('KeyD')) mv.add(right);
    if (k.has('KeyA')) mv.sub(right);
    if (k.has('Space')) mv.y += 1;
    if (k.has('ShiftLeft') || k.has('ShiftRight')) mv.y -= 1;
    if (mv.lengthSq() > 0) {
      camera.position.addScaledVector(mv.normalize(), speed);
      if (mode === 'eyes') setMode('fly');
    }
  }
  glide(dt);
  beamTick();
  bitsTick(dt);
  if (LATER.size && current) { const k = LATER.values().next().value; rebuild(k); }
  if (t - lastPick > 80) { lastPick = t; showPick(); }
  if (canvas.clientWidth && (canvas.width !== Math.round(canvas.clientWidth * renderer.getPixelRatio()) || canvas.height !== Math.round(canvas.clientHeight * renderer.getPixelRatio()))) resize();
  if (!CFG.paused || !CFG.paused()) {
    renderer.render(scene, camera);
    // what the buddy holds, through the buddy's own eyes, over the world (the game draws it so too)
    if (mode === 'drive' && !person && current && hands(t, dt)) {
      renderer.autoClear = false;
      renderer.clearDepth();
      renderer.render(HAND.scene, HAND.cam);
      renderer.autoClear = true;
    }
  }
  requestAnimationFrame(frame);
}

/** The map's zoom: 2 nearer, 0.5 further, as the flat map's buttons (four times either way). */
function mapZoom(f) {
  mapCam.dist = Math.max(MAP_DIST / 4, Math.min(MAP_DIST * 4, mapCam.dist / f));
}

/** The map's camera at once from mapCam: its turn (yaw, 0 is north up) and its height above the ground (pitch). */
function mapCamera() {
  const c = Math.cos(mapCam.pitch), d = mapCam.dist;
  camera.position.set(mapCam.x + Math.sin(mapCam.yaw) * c * d, mapCam.y + Math.sin(mapCam.pitch) * d, mapCam.z + Math.cos(mapCam.yaw) * c * d);
  camera.lookAt(mapCam.x, mapCam.y, mapCam.z);
  camera.updateMatrixWorld();
}

/** Where a point of the canvas lies on the ground under the map (the level of the map's middle), or null above the horizon. */
function mapGround(e, y) {
  const r = canvas.getBoundingClientRect();
  ray.setFromCamera({ x: ((e.clientX - r.left) / r.width) * 2 - 1, y: -((e.clientY - r.top) / r.height) * 2 + 1 }, camera);
  const o = ray.ray.origin, d = ray.ray.direction;
  if (d.y > -0.02) return null;
  const t = (y - o.y) / d.y;
  return t > 0 && t < 3000 ? new THREE.Vector3(o.x + d.x * t, y, o.z + d.z * t) : null;
}

/** Zooming by a double click: nearer (2) or further (0.5) with the spot under the mouse kept where it is, as a map does. */
function mapZoomAt(e, f) {
  const before = mapCam.dist;
  mapCamera();
  const p = mapGround(e, mapCam.y);
  mapZoom(f);
  const k = mapCam.dist / before;
  if (k === 1) return;
  if (p) {
    mapCam.x = p.x + (mapCam.x - p.x) * k; mapCam.z = p.z + (mapCam.z - p.z) * k;
    mapCam.follow = false;
  }
  CFG.onMap?.();
}

function fail(e) {
  console.error(e);
  status('Something went wrong: <b>' + String(e && e.message || e).replace(/</g, '&lt;') + '</b>');
}

/**
 * Starts the view on a canvas. cfg: canvas; ids (status, hover, help, cross, orbit, eyes, fly, refresh: the ids of the
 * page's elements, each optional); controls (false when the page steers itself, as the panel does); look (a function
 * that gives {yaw, pitch} in the game's degrees while the panel drives the buddy); paused (a function: true while nothing
 * needs drawing). Returns what the page can ask of it.
 */
export function start3d(cfg) {
  CFG = cfg;
  IDS = cfg.ids || {};
  setBrightness(cfg.brightness);
  canvas = cfg.canvas;
  renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: location.search.includes('shot') });
  renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  scene = new THREE.Scene();
  sky = new THREE.Color(0x78a7ff);
  scene.background = sky;
  camera = new THREE.PerspectiveCamera(70, 1, 0.05, 2000);
  orbit = new OrbitControls(camera, canvas);
  orbit.enableDamping = true;
  orbit.screenSpacePanning = true;
  worldGroup = new THREE.Group();
  scene.add(worldGroup);
  box = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(1.004, 1.004, 1.004)), new THREE.LineBasicMaterial({ color: 0x000000 }));
  box.visible = false;
  scene.add(box);
  ray = new THREE.Raycaster();
  canvas.addEventListener('mousemove', e => {
    const r = canvas.getBoundingClientRect();
    mouse = { x: ((e.clientX - r.left) / r.width) * 2 - 1, y: -((e.clientY - r.top) / r.height) * 2 + 1 };
  });
  canvas.addEventListener('mouseleave', () => { mouse = null; });
  // the map: the left button drags the ground along under the mouse, the right one turns the view round its middle and
  // tilts it; a double click zooms in on the spot, a double right click out. On a touchpad, where a right button held
  // down while the fingers move is no steady thing (it moved at first and then stopped at once), Shift, Option or Ctrl
  // with the left button turns too
  let rightClick = null, leftClick = null;
  canvas.addEventListener('pointerdown', e => {
    if (mode !== 'map' || (e.button !== 0 && e.button !== 2)) return;
    mapCamera();
    const turn = e.button === 2 || e.shiftKey || e.altKey || e.ctrlKey;
    mapCam.drag = { x: e.clientX, y: e.clientY, x0: e.clientX, y0: e.clientY, t0: performance.now(), button: e.button, mask: e.button === 2 ? 2 : 1,
      turn, ground: turn ? null : mapGround(e, mapCam.y) };
    try { canvas.setPointerCapture(e.pointerId); } catch { }
    canvas.style.cursor = 'grabbing';
  });
  canvas.addEventListener('pointermove', e => {
    if (mode !== 'map' || !mapCam.drag) return;
    const g = mapCam.drag, h = Math.max(1, canvas.clientHeight);
    // the button is up while its release never came (a touchpad's right click on a Mac): the drag is over, or the view
    // went on turning with the mouse without a button held
    if (!(e.buttons & g.mask)) { endDrag(null); return; }
    if (g.turn) {
      mapCam.yaw -= 2 * Math.PI * (e.clientX - g.x) / h;
      mapCam.pitch = Math.max(0.17, Math.min(1.54, mapCam.pitch + 2 * Math.PI * (e.clientY - g.y) / h));
      g.x = e.clientX; g.y = e.clientY;
      mapCamera();
      CFG.onMap?.();
      return;
    }
    // the spot that was under the mouse stays under it
    const p = g.ground && mapGround(e, g.ground.y);
    if (!p) { g.ground = mapGround(e, mapCam.y); return; }
    mapCam.x += g.ground.x - p.x; mapCam.z += g.ground.z - p.z;
    mapCamera();
    if (mapCam.follow) { mapCam.follow = false; CFG.onMap?.(); }
  });
  const endDrag = e => {
    const g = mapCam.drag;
    if (!g) return;
    mapCam.drag = null;
    canvas.style.cursor = mode === 'map' ? 'grab' : '';
    // a right click that did not turn: the second one within 400 ms near the first zooms out
    if (g.turn && g.button === 2 && e && e.type === 'pointerup' && Math.hypot(e.clientX - g.x0, e.clientY - g.y0) < 5) {
      const t = performance.now();
      if (rightClick && t - rightClick.t < 400 && Math.hypot(e.clientX - rightClick.x, e.clientY - rightClick.y) < 12) { rightClick = null; mapZoomAt(e, 0.5); }
      else rightClick = { t, x: e.clientX, y: e.clientY };
    }
    // a left click that did not drag: the block under it goes to the page, which offers a walk there, as the flat map
    // does, so that a single click sends the buddy somewhere; a second click within the double click's time makes it a zoom
    // instead. Only a short press counts: one held still for half a second, or a touchpad's press at the end of a gesture,
    // is no click
    if (!g.turn && e && e.type === 'pointerup' && Math.hypot(e.clientX - g.x0, e.clientY - g.y0) < 5 && performance.now() - g.t0 < 500 && CFG.onPick) {
      const r = canvas.getBoundingClientRect(), cx = e.clientX, cy = e.clientY;
      clearTimeout(leftClick);
      leftClick = setTimeout(() => {
        const was = mouse;
        mouse = { x: ((cx - r.left) / r.width) * 2 - 1, y: -((cy - r.top) / r.height) * 2 + 1 };
        const h = pick();
        mouse = was;
        CFG.onPick(h && h.face && h.info ? { x: h.x, y: h.y, z: h.z, face: h.face, clientX: cx, clientY: cy } : null);
      }, 320);
    }
  };
  canvas.addEventListener('pointerup', endDrag);
  canvas.addEventListener('pointercancel', endDrag);
  canvas.addEventListener('lostpointercapture', () => endDrag(null));
  canvas.addEventListener('dblclick', e => { if (mode === 'map') { e.preventDefault(); clearTimeout(leftClick); mapZoomAt(e, 2); } });
  canvas.addEventListener('contextmenu', e => { if (mode === 'map') e.preventDefault(); });
  // the wheel, two fingers on a touchpad and a pinch zoom by how far they go: a touchpad sends a stream of small steps,
  // and twice nearer a step had it at the end of the zoom in a moment
  canvas.addEventListener('wheel', e => {
    if (mode !== 'map') return;
    e.preventDefault();
    const px = e.deltaY * (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 400 : 1);
    if (px) { mapZoom(Math.exp(-px * (e.ctrlKey ? 0.01 : 0.003))); CFG.onMap?.(); }
  }, { passive: false });
  window.addEventListener('resize', resize);
  resize();
  bindControls();
  requestAnimationFrame(frame);
  (async () => {
    await store.open();
    await show(false);
    // what the page asked for while the world was loading wins over what it asked at the start
    if (mode === 'drive' || (mode === 'orbit' && !orbitAsked && cfg.mode === 'drive')) drive();
    else if (mode === 'map' || (mode === 'orbit' && !orbitAsked && cfg.mode === 'map')) mapView();
    else if (new URLSearchParams(location.search).get('view') === 'eyes') hisEyes();
    window.__ready = true;
    keepUp();
  })().catch(e => { fail(e); window.__ready = true; });
  return {
    drive, lookAround, hisEyes, fly: startFly, map: mapView, mapZoom, refresh: () => show(true).catch(fail),
    mapFollow: () => mapCam.follow, mapZoomLevel: () => MAP_DIST / mapCam.dist,
    mapTurned: () => Math.abs(mapCam.yaw) > 1e-6 || Math.abs(mapCam.pitch - MAP_PITCH) > 1e-6,
    mapTurn: () => ({ yaw: mapCam.yaw, pitch: mapCam.pitch }),
    // where the buddy is drawn now (between the buddy's places), for the coordinates over the view
    where: () => current && hisPlace(current.world.meta),
    /** The view to another place in the page (the map card, the drive window): the canvas and its hover text. */
    moveTo: (parent, hoverParent) => { if (canvas.parentNode !== parent) parent.insertBefore(canvas, parent.firstChild); const h = el('hover'); if (h && hoverParent && h.parentNode !== hoverParent) hoverParent.appendChild(h); resize(); },
    hit: () => lastHit,
    mode: () => mode,
    person: n => { if (n === undefined) return person; person = n; },
    /** How bright the view is: "game" (the game's own 50%), "bright" (its 100%) or "full" (as with Night Vision). */
    brightness: setBrightness,
    resize,
  };
}
