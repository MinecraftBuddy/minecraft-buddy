/* What the buddy does now, in a few words, from Soprano's plan: the rows of task (t) and state (s) the mod sends with an
   order (Alto.plan: tasks, and by / by_tasks while another chain has the wheel) and records in a run's recording.
   One file for the panel (a classic script) and the practice rig's replay page (imported as a module): no import or
   export, it sets globalThis.buddyActivity.

   buddyActivity.read(plan, {step}) gives {now, detail, goal, then}:
   - now: the stage, from the outer task's own state ("Going to the blaze spawner"), else the deepest task that says
     what it does ("Killing a blaze");
   - detail: the deepest row below that adds something ("a piglin admiring the gold");
   - goal: what the outer task collects ("9 blaze rods");
   - then: while another chain interrupts (a wither skeleton, lava), the stage it returns to.
   Rows that only move (getting to a block, approaching, wandering) say nothing by themselves and are left out.
   step: the order's step when Soprano has no plan (the buddy's own steps: a night in bed, digging out). */
(function () {
  const words = id => String(id || '').replace(/^minecraft:/, '').replace(/_/g, ' ').trim();
  const art = w => (/^[aeiou]/i.test(w) ? 'an ' : 'a ') + w;
  const cap = s => s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
  const low = s => s ? s.charAt(0).toLowerCase() + s.slice(1) : s;
  const UNCOUNTED = /(cream|wart|cobblestone|netherrack|obsidian|gold|iron|dirt|sand|gravel|stone|wool|leather|string|powder|dust|redstone|glass|flint|coal|wheat|sugar|paper|clay|glowstone|quartz|lava|water|milk|beef|porkchop|mutton|chicken|cod|salmon|kelp|bamboo|ice|snow|food|scrap|debris|netherite|lapis lazuli|blackstone|basalt|deepslate|terracotta|leggings|boots|shears)$/;
  // an item's id and count as words: blaze_rod, 9 -> "9 blaze rods"
  function thing(id, n) {
    let w = words(id);
    if (n > 1 && !UNCOUNTED.test(w)) w = /(s|x|ch|sh)$/.test(w) ? w + 'es' : w + 's';
    return n == null ? w : n + ' ' + w;
  }
  // Soprano's item lists: [[blaze_rod] x 9], [[acacia_log,oak_log,...] x 4], several of them -> words
  function items(s, count) {
    // (a long list is cut short by the mod: its first ids still say what)
    const all = [...String(s).matchAll(/\[\[([a-z_,]+)(?:\] x (\d+)\])?/g)];
    if (!all.length) return '';
    if (all.length > 1) return 'what dropped';
    let ids = all[0][1].split(',').filter(Boolean);
    const n = +all[0][2] || 0;
    if (!n && ids.length > 1) ids = ids.slice(0, -1); // the last id of a cut list may be cut too
    count = count && n > 0;
    if (ids.length > 1 && ids.every(i => /_(log|stem|wood|hyphae)$/.test(i))) return count ? n + ' logs' : 'logs';
    if (ids.length > 1 && ids.every(i => /_planks$/.test(i))) return count ? n + ' planks' : 'planks';
    return count ? thing(ids[0], n) : thing(ids[0], 2).replace(/^2 /, '');
  }
  // coordinates, ids and Soprano's notation out of a line that has no rule of its own
  const tidy = s => String(s || '').replace(/\s*\bat\s+(?:BlockPos\{[^}]*\}|-?\d+(?:\.\d+)?,\s*-?\d+(?:\.\d+)?,\s*-?\d+(?:\.\d+)?)/g, '')
    .replace(/BlockPos\{[^}]*\}/g, '').replace(/\bdir \w+/g, '').replace(/entity\.minecraft\.([a-z_]+)/g, (m, id) => words(id))
    .replace(/\[\[([a-z_,]+)\] x (\d+)\]/g, (m, id, n) => thing(id.split(',')[0], +n)).replace(/minecraft:/g, '')
    .replace(/\s*\(\d+ zones?\)/g, '').replace(/[\s.…]+$/, '').replace(/\s+/g, ' ').trim();

  // a task's name -> words, or null for a task that only moves or carries out its parent's step
  const NAMES = [
    [/^Collect (\d+) (.+?):\s*[\[{]/, m => 'Collecting ' + m[1] + ' ' + m[2]],
    [/^Collect Cobblestone/, 'Collecting cobblestone'],
    [/^Killing entity\.minecraft\.([a-z_]+)/, m => 'Killing ' + art(words(m[1]))],
    [/^Pickup Dropped Items: (.*)/, m => 'Picking up ' + (items(m[1]) || 'what dropped')],
    [/^Mine And Collect: (.*)/, m => 'Mining ' + (items(m[1]) || 'blocks')],
    [/^Crafting (\d+) ([a-z ]+?)(?::|\s*\[|$)/, m => 'Crafting ' + m[2]],
    [/^Searching chunk for blocks .*nether_bricks/, 'Searching for a fortress'],
    [/^Searching chunk for blocks \{?(.*)/, m => 'Searching for ' + tidy(m[1]).replace(/Block\{([a-z_]+)\}/g, (x, b) => words(b)).replace(/[{}]/g, '')],
    [/^Sweeping the Nether for (.+)/, m => 'Searching the Nether for ' + art(words(m[1]).toLowerCase())],
    [/^Going to dimension: (\w+)/, m => 'Going to the ' + ({OVERWORLD: 'overworld', NETHER: 'Nether', END: 'End'}[m[1]] || words(m[1]).toLowerCase())],
    [/^Building nether portal/, 'Building a nether portal'],
    [/^Construct Nether Portal/, 'Building a nether portal'],
    [/^Entering nether portal/, 'Going through the portal'],
    [/^Giving piglins gold for fire resistance/, 'Bartering with piglins for fire resistance'],
    [/^Trading with piglin/, 'Bartering with piglins'],
    [/^Luring blazes round the corner/, 'Luring blazes round a corner'],
    [/^Building a pillbox/, 'Building a pillbox'],
    [/^Putting out fire/, 'Putting out a fire'],
    [/^Doing stuff in ([a-z_]+)/, m => 'Using the ' + words(m[1])],
    [/^Ensuring inventory is free/, 'Making room in the inventory'],
    [/^Run away from monsters/, 'Running from monsters'],
    [/^Running \d+ blocks from/, 'Running from monsters'],
    [/^Escaping lava/, 'Escaping lava'],
    [/^Healing up before going back in/, 'Healing before going back in'],
    [/^Epic gaemer moment/, 'Breaking a fall'],
    [/^Hunting endermen in \w+ for (\d+) pearls/, m => 'Hunting endermen for ' + m[1] + ' ender pearls'],
    // moving, aiming and the wrappers of a step: their parent says what for
    [/^(Getting to block|Getting to \(|Get to chunk|Approach entity|Wander for|Searching chunks|Interact using|Mining or Collecting|Destroy |Satisfy Mining Req|Collect Recipe Resources|Collect items from|Doing something to closest entity|Craft \d+x\d+ Task|Shimmying|Idle$|…$|Timeout Wander)/, null],
  ];
  // a task's state -> words, or null where it only says that it moves or waits for the game
  const STATES = [
    [/^(Approaching target|Going to entity|Completing goal|Exploring|Wandering|Getting to our goal|Waiting for click|Clicking|Searching within chunks|Getting to block|Staying with the current pursuit|Waiting for calculations|Picking up|Collecting materials|Block in range, mining|Getting unstuck from block|Waiting for the first scan before wandering|Hopping out of the lava)\.*$/, null],
    [/^Waiting for calculations/, null],
    [/^Blaze fight: kill$/, 'Killing blazes'],
    [/^Blaze fight: run$/, 'Running from the blazes'],
    [/^Blaze fight: snowballs$/, 'Throwing snowballs at the blazes'],
    [/^Blaze fight: (\w+)/, m => 'Fighting blazes (' + m[1] + ')'],
    [/^Searching for fortress/, 'Searching for a fortress'],
    [/^Going to blaze spawner/, 'Going to the blaze spawner'],
    [/^Clearing fire around spawner/, 'Putting out the fire round the spawner'],
    [/^Trading gold with piglins for fire resistance/, 'Bartering with piglins for fire resistance'],
    [/^A magma cube for its cream/, 'Hunting a magma cube for its cream'],
    [/^A nether wart for the brew/, 'Getting nether wart for the brew'],
    [/^Waiting near blaze spawner for blazes to spawn/, 'Waiting at the spawner for blazes'],
    [/^Going to nether$/, 'Going to the Nether'],
    [/^Getting diamond pickaxe first/, 'Getting a diamond pickaxe first'],
    [/^Getting ([a-z_]+) x (\d+)$/, m => 'Getting ' + thing(m[1], +m[2])],
    [/^Constructing nether portal/, 'Building a nether portal'],
    [/^wither skeletons at the spawner, waiting/, 'Waiting: wither skeletons at the spawner'],
    [/^On a rim with no way on/, 'Back to firm ground'],
    [/^Building the pillbox, block (\d+ of \d+)/, m => 'Block ' + m[1]],
    [/^No way to get to the target/, 'No way to get there'],
  ];
  function rule(list, s) {
    if (!s) return null;
    for (const [re, out] of list) {
      const m = re.exec(s);
      if (m) return typeof out === 'function' ? out(m) : out;
    }
    return cap(tidy(s)) || null;
  }
  const name = t => rule(NAMES, t);
  const state = s => rule(STATES, s);

  // the same thing said twice ("Killing blazes" and "Killing a blaze"): every word of one, singular, in the other
  const bag = s => String(s).toLowerCase().replace(/[^a-z ]/g, ' ').split(/\s+/).filter(w => w && !/^(a|an|the|for|to|its|of|round|from|with)$/.test(w)).map(w => w.replace(/s$/, '').replace(/e$/, ''));
  function same(a, b) {
    const x = bag(a), y = new Set(bag(b));
    return x.length > 0 && x.every(w => y.has(w));
  }

  // the chains that take the wheel from the order, by Soprano's class name, where their own rows say nothing
  const CHAIN = {MobDefenseChain: 'Fighting off monsters', FoodChain: 'Eating', WorldSurvivalChain: 'Getting out of lava, fire or water',
    MLGBucketFallChain: 'Breaking a fall', DeathMenuChain: 'Getting up after a death', PlayerInteractionFixChain: 'Tidying the inventory',
    GhastShelterChain: 'Taking cover from a ghast'};

  // the order's own step when Soprano has no plan: the planner's ("kill [blaze] for 6 blaze_rod") or the buddy's own
  function step(s) {
    s = String(s || '').trim();
    let m;
    if (!s) return '';
    if ((m = /^take (\d+) (\S+)/.exec(s))) return 'Taking ' + thing(m[2], +m[1]) + ' from a chest';
    if ((m = /^craft \S+ x\d+ -> (\d+) (\S+)/.exec(s))) return 'Crafting ' + thing(m[2], +m[1]);
    if ((m = /^smelt \S+ x\d+ -> (\d+) (\S+)/.exec(s))) return 'Smelting ' + thing(m[2], +m[1]);
    if ((m = /^station (\S+) x\d+ -> (\d+) (\S+)/.exec(s))) return 'Making ' + thing(m[3], +m[2]);
    if ((m = /^mine \[([^\],]+).*\] for (\d+) (\S+)/.exec(s))) return 'Mining ' + words(m[1]) + ' for ' + thing(m[3], +m[2]);
    if ((m = /^kill \[([^\],]+).*\] for (\d+) (\S+)/.exec(s))) return 'Hunting ' + thing(m[1], 2).replace(/^2 /, '') + ' for ' + thing(m[3], +m[2]);
    if ((m = /^barter for (\d+) (\S+)/.exec(s))) return 'Bartering for ' + thing(m[2], +m[1]);
    if ((m = /^(.+) by AltoClef, for (.+)$/.exec(s))) return 'Getting ' + (m[1] === 'food' ? 'food' : thing(m[1], 2).replace(/^2 /, ''));
    // the buddy's own steps (Obtain: shelter, bed, digging out, the furnace, a pick-up)
    if ((m = /^shelter (.+)/.exec(s))) return 'Sheltering ' + m[1];
    if (/^a night in bed/.test(s)) return 'Sleeping in a bed';
    if ((m = /^dig out: (.+)/.exec(s))) return 'Digging out: ' + m[1];
    if (/^out of the water/.test(s)) return 'Getting out of the water';
    if (/^empty the furnace/.test(s)) return 'Emptying the furnace';
    if ((m = /^pick up the (.+)/.exec(s))) return 'Picking up the ' + words(m[1]);
    if (/^the morning down here/.test(s)) return 'Waiting for the morning underground';
    if (/^up to the surface/.test(s)) return 'Going up to the surface';
    return cap(tidy(s.replace(/ -> .*$/, '')));
  }

  function read(plan, opts) {
    opts = opts || {};
    const tasks = (plan && plan.tasks) || [];
    const lv = [];
    tasks.forEach((r, i) => {
      const n = name(r.t), s = state(r.s);
      if (n) lv.push({txt: n, i, k: 'n'});
      if (s) lv.push({txt: s, i, k: 's'});
    });
    let goal = '';
    const g = tasks.length ? /^Collect (\d+) (.+?):\s*[\[{]/.exec(tasks[0].t || '') : null;
    if (g) goal = g[1] + ' ' + g[2];
    // the stage: the outer task's state, else the deepest task with a name of its own
    let at = lv.findIndex(l => l.i === 0 && l.k === 's');
    if (at < 0) for (let j = lv.length - 1; j >= 0; j--) if (lv[j].k === 'n') { at = j; break; }
    if (at < 0) at = lv.length - 1;
    let now = at >= 0 ? lv[at].txt : '', detail = '';
    // (a detail with the stage's own verb says it again: "Picking up the potion: picking up what dropped")
    const verb = s => String(s).split(' ')[0].toLowerCase();
    for (let j = lv.length - 1; j > at; j--) if (!same(lv[j].txt, now) && !same(now, lv[j].txt) && verb(lv[j].txt) !== verb(now)) { detail = lv[j].txt; break; }
    if (!now) now = step(opts.step);
    // the outer task is the goal itself ("Collecting 9 blaze rods"): no goal beside it
    if (goal && same(now, 'Collecting ' + goal)) goal = '';
    const out = {now, detail: low(detail), goal, then: ''};
    // another chain has the wheel: what it does is the stage now, the order's stage what follows
    if (plan && plan.by) {
      const bt = plan.by_tasks || [];
      let by = '', bd = '';
      for (let j = bt.length - 1; j >= 0 && !by; j--) {
        const n = name(bt[j].t);
        if (n) { by = n.replace(/^Killing /, 'Fighting '); bd = state(bt[j].s) || ''; }
      }
      const idle = bt.length > 0 && bt.every(r => /^Idle$/.test(r.t || ''));
      if (!by && !idle) by = CHAIN[plan.by] || cap(words(String(plan.by).replace(/Chain$/, '').replace(/([a-z])([A-Z])/g, '$1_$2')).toLowerCase());
      if (by) return {now: by, detail: low(bd), goal: out.goal, then: low(now)};
    }
    return out;
  }

  // one line: "Killing blazes: on an edge, letting it come · for 9 blaze rods"; another chain first: "…; then …"
  function line(r, opts) {
    if (!r || !r.now) return '';
    opts = opts || {};
    let s = r.now + (r.detail ? ': ' + r.detail : '');
    if (r.then) s += '; then ' + r.then;
    if (r.goal && opts.goal !== false) s += ' · for ' + r.goal;
    return s;
  }

  globalThis.buddyActivity = {read, line, step};
})();
