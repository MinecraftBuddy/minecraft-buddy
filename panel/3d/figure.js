/**
 * The buddy as the game's inventory screen draws him: his skin and the armour he wears, the body turned a little and the
 * head looking at the mouse. The player model and the armour model are the game's (HumanoidModel and
 * its armour meshes: the outer layer inflated by one pixel, leggings by half a pixel), drawn orthographic as the game's
 * GUI does; the follow is InventoryScreen's: atan of the mouse's distance over 40 GUI pixels, the body 20 degrees per
 * unit, the head 40 sideways and 20 up and down. And frontView() draws his skin from the front, flat, for the picture
 * beside his name.
 */
import * as THREE from './three.module.min.js';

const D = Math.PI / 180;

/** One box of the model: its faces with their places on the texture (the game's cube unwrap). */
function box(geo, w, h, d, u, v, inflate, tw, th, mirror) {
  const hx = w / 2 + inflate, hy = h / 2 + inflate, hz = d / 2 + inflate;
  const pos = [], uv = [], idx = [];
  const face = (corners, ru, rv, rw, rh, flip) => {
    const n = pos.length / 3;
    let u0 = ru / tw, u1 = (ru + rw) / tw;
    if (flip) [u0, u1] = [u1, u0];
    const v0 = rv / th, v1 = (rv + rh) / th;
    for (const c of corners) pos.push(c[0] * hx, c[1] * hy, c[2] * hz);
    uv.push(u0, v0, u1, v0, u1, v1, u0, v1);
    idx.push(n, n + 3, n + 2, n, n + 2, n + 1);
  };
  // the sides of the unwrap: -x (his right), front, +x (his left), back; mirrored (a left limb from a right one) they swap
  const R = [[-1, 1, -1], [-1, 1, 1], [-1, -1, 1], [-1, -1, -1]], L = [[1, 1, 1], [1, 1, -1], [1, -1, -1], [1, -1, 1]];
  face(mirror ? L : R, mirror ? u + d + w : u, v + d, d, h, mirror);
  face([[-1, 1, 1], [1, 1, 1], [1, -1, 1], [-1, -1, 1]], u + d, v + d, w, h, mirror);
  face(mirror ? R : L, mirror ? u : u + d + w, v + d, d, h, mirror);
  face([[1, 1, -1], [-1, 1, -1], [-1, -1, -1], [1, -1, -1]], u + 2 * d + w, v + d, w, h, mirror);
  face([[-1, 1, -1], [1, 1, -1], [1, 1, 1], [-1, 1, 1]], u + d, v, w, d, mirror);
  face([[-1, -1, 1], [1, -1, 1], [1, -1, -1], [-1, -1, -1]], u + d + w, v, w, d, mirror);
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geo.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  geo.setIndex(idx);
  geo.computeVertexNormals();
  return geo;
}

const LOADER = new THREE.TextureLoader();
function texture(url) {
  return new Promise(ok => LOADER.load(url, t => {
    t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.generateMipmaps = false;
    t.colorSpace = THREE.SRGBColorSpace; t.flipY = false;
    ok(t);
  }, undefined, () => ok(null)));
}

/**
 * A figure on a canvas: update({skin, slim, armor}) with the skin's address (or null for the default), and the armour
 * as {asset: url-of-humanoid, legs: url-of-humanoid_leggings, tint} per slot from head to feet; follow(x, y) with the
 * mouse's place on the page.
 */
export function inventoryFigure(canvas) {
  const renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: false });
  renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  const scene = new THREE.Scene();
  scene.add(new THREE.AmbientLight(0xffffff, 1.6));
  const sun = new THREE.DirectionalLight(0xffffff, 1.4); sun.position.set(-0.3, 1, 1.2); scene.add(sun);
  const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, -100, 100);
  camera.position.set(0, 0, 50);
  const root = new THREE.Group(), head = new THREE.Group();
  scene.add(root);
  root.add(head);
  head.position.set(0, 24, 0);
  let skinKey = null, armorKey = null, parts = [], armour = [], gui = 2, mouse = null, raf = 0;

  const mat = (tex, tint) => new THREE.MeshLambertMaterial({ map: tex, transparent: true, alphaTest: 0.1, side: THREE.DoubleSide, color: tint || 0xffffff });
  const put = (list, parent, geo, material, x, y, z) => { const m = new THREE.Mesh(geo, material); m.position.set(x, y, z); parent.add(m); list.push(m); };
  const clear = list => { for (const m of list) { m.parent.remove(m); m.geometry.dispose(); } list.length = 0; };

  function build(tex, slim, th) {
    clear(parts);
    if (!tex) return;
    const legacy = th === 32, aw = slim ? 3 : 4, ax = 4 + aw / 2, sk = mat(tex);
    const P = (w, h, d, u, v, inf, mir) => box(new THREE.BufferGeometry(), w, h, d, u, v, inf, 64, th, mir);
    put(parts, head, P(8, 8, 8, 0, 0, 0), sk, 0, 4, 0);
    put(parts, head, P(8, 8, 8, 32, 0, 0.5), sk, 0, 4, 0);
    put(parts, root, P(8, 12, 4, 16, 16, 0), sk, 0, 18, 0);
    put(parts, root, P(4, 12, 4, 0, 16, 0), sk, -2, 6, 0);
    put(parts, root, P(aw, 12, 4, 40, 16, 0), sk, -ax, 18, 0);
    if (legacy) {
      put(parts, root, P(4, 12, 4, 0, 16, 0, true), sk, 2, 6, 0);
      put(parts, root, P(aw, 12, 4, 40, 16, 0, true), sk, ax, 18, 0);
    } else {
      put(parts, root, P(8, 12, 4, 16, 32, 0.25), sk, 0, 18, 0);
      put(parts, root, P(4, 12, 4, 0, 32, 0.25), sk, -2, 6, 0);
      put(parts, root, P(aw, 12, 4, 40, 32, 0.25), sk, -ax, 18, 0);
      put(parts, root, P(4, 12, 4, 16, 48, 0), sk, 2, 6, 0);
      put(parts, root, P(4, 12, 4, 0, 48, 0.25), sk, 2, 6, 0);
      put(parts, root, P(aw, 12, 4, 32, 48, 0), sk, ax, 18, 0);
      put(parts, root, P(aw, 12, 4, 48, 48, 0.25), sk, ax, 18, 0);
    }
  }

  // the armour model: 64 by 32, the head and hat, body, arms (the left mirrored) and legs
  function buildArmour(list) {
    clear(armour);
    const A = (w, h, d, u, v, inf, mir) => box(new THREE.BufferGeometry(), w, h, d, u, v, inf, 64, 32, mir);
    list.forEach((piece, slot) => {
      if (!piece || !piece.tex) return;
      const m = mat(piece.tex, piece.tint);
      if (slot === 0) { put(armour, head, A(8, 8, 8, 0, 0, 1), m, 0, 4, 0); put(armour, head, A(8, 8, 8, 32, 0, 1.5), m, 0, 4, 0); }
      if (slot === 1) {
        put(armour, root, A(8, 12, 4, 16, 16, 1), m, 0, 18, 0);
        put(armour, root, A(4, 12, 4, 40, 16, 1), m, -6, 18, 0);
        put(armour, root, A(4, 12, 4, 40, 16, 1, true), m, 6, 18, 0);
      }
      if (slot === 2) {
        put(armour, root, A(8, 12, 4, 16, 16, 0.5), m, 0, 18, 0);
        put(armour, root, A(4, 12, 4, 0, 16, 0.5), m, -2, 6, 0);
        put(armour, root, A(4, 12, 4, 0, 16, 0.5, true), m, 2, 6, 0);
      }
      if (slot === 3) {
        put(armour, root, A(4, 12, 4, 0, 16, 1), m, -2, 6, 0);
        put(armour, root, A(4, 12, 4, 0, 16, 1, true), m, 2, 6, 0);
      }
    });
  }

  function draw() {
    raf = 0;
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w || !h) return;
    if (canvas.width !== Math.round(w * renderer.getPixelRatio()) || canvas.height !== Math.round(h * renderer.getPixelRatio())) renderer.setSize(w, h, false);
    // GUI pixels: the screen's box is 51 wide; a block is 30 of them, a model pixel 30/16
    gui = w / 51;
    const s = 16 / 30 / gui;
    camera.left = -w / 2 * s; camera.right = w / 2 * s; camera.top = h * s; camera.bottom = 0;
    camera.position.y = -3.5; camera.updateProjectionMatrix();
    let yaw = 0, hy = 0, hp = 0;
    if (mouse) {
      const r = canvas.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height * 0.3;
      const a = Math.atan((mouse.x - cx) / (40 * gui)), b = Math.atan((mouse.y - cy) / (40 * gui));
      yaw = a * 20 * D; hy = a * 40 * D - yaw; hp = b * 20 * D;
    }
    root.rotation.y = yaw; head.rotation.set(hp, hy, 0, 'YXZ');
    renderer.render(scene, camera);
  }
  const redraw = () => { if (!raf) raf = requestAnimationFrame(draw); };

  return {
    async update(me) {
      const sk = me && (me.skinUrl || me.defaultUrl) || null;
      const key = sk + '|' + !!(me && me.slim);
      if (key !== skinKey) {
        skinKey = key;
        const t = sk ? await texture(sk) : null;
        build(t, me && me.slim, t && t.image ? (t.image.height === 32 ? 32 : 64) : 64);
      }
      const ak = JSON.stringify((me && me.armor) || []);
      if (ak !== armorKey) {
        armorKey = ak;
        const list = await Promise.all(((me && me.armor) || []).map(async p => p && p.url ? { tex: await texture(p.url), tint: p.tint } : null));
        buildArmour(list);
      }
      redraw();
    },
    follow(x, y) { mouse = { x, y }; redraw(); },
    redraw,
  };
}

/** His skin from the front, flat, on a 2D canvas 16 by 32 pixels: head and hat, body and jacket, arms, legs. */
export function frontView(canvas, img, slim) {
  canvas.width = 16; canvas.height = 32;
  const g = canvas.getContext('2d');
  g.imageSmoothingEnabled = false;
  g.clearRect(0, 0, 16, 32);
  const legacy = img.height === 32, aw = slim ? 3 : 4;
  const blit = (sx, sy, w, h, dx, dy, mirror) => {
    if (!mirror) { g.drawImage(img, sx, sy, w, h, dx, dy, w, h); return; }
    g.save(); g.translate(dx + w, dy); g.scale(-1, 1); g.drawImage(img, sx, sy, w, h, 0, 0, w, h); g.restore();
  };
  blit(4, 20, 4, 12, 4, 20);                                 // his right leg
  if (legacy) blit(4, 20, 4, 12, 8, 20, true); else blit(20, 52, 4, 12, 8, 20);
  blit(20, 20, 8, 12, 4, 8);                                 // body
  blit(44, 20, aw, 12, 4 - aw, 8);                           // right arm
  if (legacy) blit(44, 20, aw, 12, 12, 8, true); else blit(36, 52, aw, 12, 12, 8);
  blit(8, 8, 8, 8, 4, 0);                                    // face
  if (!legacy) {
    blit(4, 36, 4, 12, 4, 20); blit(4, 52, 4, 12, 8, 20);    // trousers
    blit(20, 36, 8, 12, 4, 8);                               // jacket
    blit(44, 36, aw, 12, 4 - aw, 8); blit(52, 52, aw, 12, 12, 8);
  }
  blit(40, 8, 8, 8, 4, 0);                                   // hat
}
