/**
 * VoidRP Cosmetics — Blockbench Plugin
 * Works alongside cpm_plugin.js
 *
 * Install: Blockbench → File → Plugins → Load Plugin from File
 * Requires CPM plugin to also be loaded.
 *
 * Output files (both required by the mod):
 *   wardrobe.bbmodel   — read by BbModelParser to extract animation names → slots
 *   wardrobe.cpmproject  — binary CPM model loaded at runtime via ModelFile.load()
 *   Place both in:  config/voidrp-cpm/models/
 *   Then run:       /vc reload
 *
 * Naming convention for animations:
 *   head_* / hat_*                    → slot: head
 *   body_* / chest_* / cape_*         → slot: body
 *   legs_* / pants_* / leggings_*     → slot: legs
 *   feet_* / boots_*                  → slot: feet
 *   wings_* / tail_* / accessory_*    → slot: accessory
 */
(function () {
    'use strict';

    const PLUGIN_ID = 'voidrp_cosmetics';

    const SLOTS = [
        { id: 'head',      label: 'Head',      color: '#4fc3f7', prefixes: ['head', 'hat'] },
        { id: 'body',      label: 'Body',      color: '#81c784', prefixes: ['body', 'chest', 'cape'] },
        { id: 'legs',      label: 'Legs',      color: '#ffb74d', prefixes: ['legs', 'pants', 'leggings'] },
        { id: 'feet',      label: 'Feet',      color: '#ff8a65', prefixes: ['feet', 'boots'] },
        { id: 'accessory', label: 'Accessory', color: '#ce93d8', prefixes: ['wings', 'tail', 'accessory', 'misc'] },
    ];

    const PREFIX_SLOT = {};
    SLOTS.forEach(s => s.prefixes.forEach(p => { PREFIX_SLOT[p] = s; }));

    // Родительские CPM-кости для каждого слота
    const SLOT_BONES = {
        head:      ['head'],
        body:      ['body'],
        legs:      ['left_leg', 'right_leg'],
        feet:      ['left_leg', 'right_leg'],
        accessory: ['body'],
    };

    // Куб-заглушка для каждой кости
    const SLOT_CUBE_DEFAULTS = {
        head:      [{ origin: [0, 24, 0],  from: [-4, 24, -4], to: [ 4, 32,  4] }],
        body:      [{ origin: [0, 24, 0],  from: [-4, 12, -2], to: [ 4, 24,  2] }],
        legs:      [
            { origin: [ 2, 12, 0], from: [ 0,  0, -2], to: [ 4, 12, 2] },
            { origin: [-2, 12, 0], from: [-4,  0, -2], to: [ 0, 12, 2] },
        ],
        feet:      [
            { origin: [ 2,  0, 0], from: [ 0, -4, -2], to: [ 4,  0, 2] },
            { origin: [-2,  0, 0], from: [-4, -4, -2], to: [ 0,  0, 2] },
        ],
        accessory: [{ origin: [0, 24, 0],  from: [-4, 12,  2], to: [ 4, 24,  6] }],
    };

    // ── Helpers ──────────────────────────────────────────────────────────────

    function detectSlot(animName) {
        if (!animName) return null;
        const lower = animName.toLowerCase();
        const u = lower.indexOf('_'), h = lower.indexOf('-');
        let sep = -1;
        if (u >= 0 && h >= 0) sep = Math.min(u, h);
        else if (u >= 0) sep = u;
        else if (h >= 0) sep = h;
        if (sep <= 0) return null;
        const prefix = lower.slice(0, sep);
        return PREFIX_SLOT[prefix] || null;
    }

    function sanitize(raw) {
        return raw.trim().toLowerCase().replace(/\s+/g, '_').replace(/[^a-z0-9_\-]/g, '');
    }

    function isCpmLoaded() {
        return !!BarItems['export_cpmproject'];
    }

    // Move an outliner node from wherever it is into a new parent Group,
    // updating both the data tree and the Three.js mesh hierarchy.
    function moveInto(node, parentGroup) {
        // Remove from current parent in data tree
        const curParent = node.parent;
        if (curParent instanceof Group) {
            curParent.children.splice(curParent.children.indexOf(node), 1);
        } else {
            const ri = Outliner.root.indexOf(node);
            if (ri >= 0) Outliner.root.splice(ri, 1);
        }
        // Detach mesh from old scene parent
        if (node.mesh && node.mesh.parent) node.mesh.parent.remove(node.mesh);
        // Attach to new parent
        node.parent = parentGroup;
        parentGroup.children.push(node);
        if (parentGroup.mesh) parentGroup.mesh.add(node.mesh);
    }

    function triggerCpmExport() {
        const btn = BarItems['export_cpmproject'];
        if (btn) { btn.click(); return true; }
        return false;
    }

    /**
     * Saves the current project as .bbmodel.
     * The mod's BbModelParser reads this file to extract animation names → slot mapping.
     * If the project has an existing save path, writes there silently.
     * Otherwise opens a Save As dialog.
     * Returns true if a path was known (silent save), false if dialog was opened.
     */
    function saveBbmodel() {
        try {
            const savePath = Project && Project.save_path;
            if (savePath && typeof savePath === 'string' && savePath.endsWith('.bbmodel')) {
                // Silent overwrite — project was already saved before
                const fs = require('fs');
                if (Codecs && Codecs.project && typeof Codecs.project.compile === 'function') {
                    const data = Codecs.project.compile({ comment: false });
                    const json = typeof data === 'string' ? data : JSON.stringify(data, null, '\t');
                    fs.writeFileSync(savePath, json, 'utf8');
                    Blockbench.showQuickMessage('💾 .bbmodel saved', 1500);
                    return true;
                }
            }
        } catch (e) { /* fall through to dialog */ }

        // Fallback: open Save As dialog
        try {
            if (BarItems['save_project_as']) {
                BarItems['save_project_as'].click();
                return false;
            }
            if (Codecs && Codecs.project && typeof Codecs.project.export === 'function') {
                Codecs.project.export();
                return false;
            }
        } catch (e) { /* ignore */ }

        Blockbench.showMessageBox({
            title: 'VoidRP — сохраните .bbmodel',
            message: 'Не удалось сохранить .bbmodel автоматически.\n\nСохраните вручную: File → Save As\nЗатем повторите Export.',
            buttons: ['OK'],
        });
        return false;
    }

    // ── Plugin registration ──────────────────────────────────────────────────

    let actionAdd, actionExport, actionValidate, panel;

    Plugin.register(PLUGIN_ID, {
        title: 'VoidRP Cosmetics',
        author: 'VoidRP',
        description: 'Cosmetics helper for VoidRP server. Works alongside CPM plugin — manages animation naming and exports .bbmodel + .cpmproject.',
        version: '1.3.0',
        min_version: '4.2.0',
        variant: 'desktop',

        onload() {

            // ── Action: Add Cosmetic ─────────────────────────────────────────

            actionAdd = new Action(`${PLUGIN_ID}_add`, {
                name: 'Add Cosmetic',
                icon: 'add_circle',
                description: 'Create a VoidRP cosmetic animation with correct naming (requires CPM plugin)',
                condition: () => Format && Modes.animate,
                click() {
                    if (!isCpmLoaded()) {
                        Blockbench.showMessageBox({
                            title: 'VoidRP Cosmetics',
                            message: 'CPM plugin is not loaded.\nPlease install and enable cpm_plugin first.',
                            buttons: ['OK'],
                        });
                        return;
                    }

                    const slotOptions = {};
                    SLOTS.forEach(s => { slotOptions[s.id] = s.label; });

                    new Dialog({
                        id: `${PLUGIN_ID}_add_dialog`,
                        title: 'Add Cosmetic',
                        form: {
                            slot: {
                                label: 'Slot',
                                type: 'select',
                                default: 'head',
                                options: slotOptions,
                            },
                            name: {
                                label: 'Name',
                                type: 'text',
                                placeholder: 'tophat',
                                description: 'Letters, digits, underscore only. e.g. "tophat" → animation "head_tophat"',
                            },
                        },
                        onConfirm(result) {
                            const raw = (result.name || '').trim();
                            if (!raw) {
                                Blockbench.showMessageBox({ title: 'VoidRP', message: 'Name cannot be empty.' });
                                return;
                            }
                            const animName = result.slot + '_' + sanitize(raw);
                            if (Animation.all.find(a => a.name === animName)) {
                                Blockbench.showMessageBox({ title: 'VoidRP', message: `Animation "${animName}" already exists.` });
                                return;
                            }

                            Undo.initEdit({ animations: [], elements: [], outliner: true });

                            const anim = new Animation({ name: animName, loop: 'hold', length: 0.1 });
                            anim.add(false);

                            // CPM checks typeof(anim.cpm_type) === 'string' before export.
                            // If the property is missing it logs "unknown animation type" and
                            // skips the animation in the .cpmproject output.
                            // cpm_type='GESTURE' maps to CPM's GESTURE enum (ordinal 2, Wic)
                            // which is what api.playAnimation() on the server triggers.
                            anim.cpm_type       = 'GESTURE';
                            anim.cpm_additive   = false;
                            anim.cpm_layerCtrl  = false;
                            anim.cpm_priority   = 0;
                            anim.cpm_order      = 0;
                            anim.cpm_isProperty = false;

                            const bones = SLOT_BONES[result.slot] || ['body'];
                            const defs  = SLOT_CUBE_DEFAULTS[result.slot] || SLOT_CUBE_DEFAULTS.body;

                            bones.forEach((boneName, i) => {
                                const parentBone = Group.all.find(g =>
                                    g.name === boneName && !(g.parent instanceof Group)
                                );
                                const d = defs[i] || defs[0];

                                // Create group, then move it inside the CPM parent bone.
                                // Blockbench's init() always adds to Outliner.root first;
                                // moveInto() handles both the data tree and the Three.js mesh hierarchy.
                                const group = new Group({
                                    name: animName,
                                    origin: d.origin.slice(),
                                });
                                group.init();
                                group.visibility = false;
                                if (parentBone) moveInto(group, parentBone);

                                // Create cube, then move it inside the group the same way.
                                // Passing parent: group to the constructor does NOT work —
                                // Blockbench ignores it in init() and always places in root.
                                const cube = new Cube({
                                    name: animName + '_' + boneName + '_cube',
                                    from: d.from.slice(),
                                    to: d.to.slice(),
                                });
                                cube.init();
                                moveInto(cube, group);

                                // Assign first available texture to all faces.
                                const tex = Texture.all[0];
                                if (tex) {
                                    for (const faceKey of Object.keys(cube.faces)) {
                                        cube.faces[faceKey].texture = tex.uuid;
                                    }
                                }

                                // Scale keyframe at t=0 so CPM knows this group is
                                // controlled by the animation (shows it when gesture fires).
                                try {
                                    if (!anim.animators[group.uuid]) {
                                        anim.animators[group.uuid] = new BoneAnimator(group.uuid, anim, group.name);
                                    }
                                    anim.animators[group.uuid].addKeyframe({
                                        channel: 'scale',
                                        time: 0,
                                        data_points: [{ x: 1, y: 1, z: 1 }],
                                        interpolation: 'step',
                                    });
                                } catch(e) { /* BoneAnimator API may differ */ }
                            });

                            Undo.finishEdit('VoidRP: add cosmetic');
                            Canvas.updateAll();
                            // Force the outliner panel to re-render so new elements appear immediately.
                            try { if (Outliner.vue) Outliner.vue.$forceUpdate(); } catch(e) {}

                            Blockbench.showQuickMessage(`✔ ${animName}`, 2000);
                        },
                    }).show();
                },
            });

            // ── Action: Export ───────────────────────────────────────────────
            // The mod requires BOTH files in config/voidrp-cpm/models/:
            //   .bbmodel  — BbModelParser reads this to register animation names → slots
            //   .cpmproject — ModelFile.load() uses this for actual rendering at runtime

            actionExport = new Action(`${PLUGIN_ID}_export`, {
                name: 'Export .bbmodel + .cpmproject (VoidRP)',
                icon: 'file_upload',
                description: 'Save .bbmodel and export .cpmproject — place both in config/voidrp-cpm/models/',
                click() {
                    if (!isCpmLoaded()) {
                        Blockbench.showMessageBox({
                            title: 'VoidRP',
                            message: 'CPM plugin is not loaded. Cannot export .cpmproject.',
                            buttons: ['OK'],
                        });
                        return;
                    }

                    function doExport() {
                        // Косметические группы скрыты по умолчанию — CPM показывает через gesture
                        const toHide = Group.all.filter(g => detectSlot(g.name) && g.visibility !== false);
                        if (toHide.length) {
                            Undo.initEdit({ outliner: true });
                            toHide.forEach(g => { g.visibility = false; });
                            Undo.finishEdit('VoidRP: hide cosmetics for export');
                            Canvas.updateAll();
                        }

                        // Step 1 — save .bbmodel (mod reads this to detect animation names → slots)
                        const hadSavePath = saveBbmodel();

                        // Step 2 — export .cpmproject via CPM plugin
                        // If .bbmodel was saved silently (project had a path), trigger CPM immediately.
                        // If a dialog was opened for .bbmodel, give it a moment before opening CPM dialog.
                        if (hadSavePath) {
                            triggerCpmExport();
                        } else {
                            setTimeout(() => triggerCpmExport(), 800);
                        }
                    }

                    // ── Предэкспортная проверка ──────────────────────────────
                    const warnings = [];

                    const CPM_BONES = ['head', 'body', 'left_arm', 'right_arm', 'left_leg', 'right_leg'];
                    const missingBones = CPM_BONES.filter(b => !Group.all.find(g => g.name === b && !(g.parent instanceof Group)));
                    if (missingBones.length)
                        warnings.push(`⚠ Отсутствуют CPM-кости (${missingBones.join(', ')}).\nОткройте модель через CPM-шаблон.`);

                    // Имена с _ в начале: мод тихо пропускает их (BbModelParser.extractName)
                    const underscoreAnims = Animation.all.filter(a => a.name && a.name.startsWith('_'));
                    if (underscoreAnims.length)
                        warnings.push(`⚠ Анимации с "_" в начале имени (${underscoreAnims.length}) — мод их игнорирует:\n`
                            + underscoreAnims.map(a => `    • ${a.name}`).join('\n'));

                    const invalidAnims = Animation.all.filter(a => !detectSlot(a.name) && !(a.name && a.name.startsWith('_')));
                    if (invalidAnims.length)
                        warnings.push(`⚠ Анимации без VoidRP-имени (${invalidAnims.length}):\n`
                            + invalidAnims.map(a => `    • ${a.name}`).join('\n'));

                    const animNames = new Set(Animation.all.map(a => a.name));
                    const groupNames = new Set(Group.all.map(g => g.name));
                    const animsNoGroup = [...animNames].filter(n => detectSlot(n) && !groupNames.has(n));
                    if (animsNoGroup.length)
                        warnings.push(`⚠ Анимации без группы (CPM не найдёт что показать, ${animsNoGroup.length}):\n`
                            + animsNoGroup.map(n => `    • ${n}`).join('\n'));

                    const groupsNoAnim = [...groupNames].filter(n => detectSlot(n) && !animNames.has(n));
                    if (groupsNoAnim.length)
                        warnings.push(`⚠ Группы без анимации (никогда не покажутся, ${groupsNoAnim.length}):\n`
                            + groupsNoAnim.map(n => `    • ${n}`).join('\n'));

                    const emptyGroups = Group.all.filter(g => detectSlot(g.name) && g.children.length === 0);
                    if (emptyGroups.length)
                        warnings.push(`⚠ Пустые косметические группы (нет кубов, ${emptyGroups.length}):\n`
                            + emptyGroups.map(g => `    • ${g.name}`).join('\n'));

                    if (warnings.length) {
                        Blockbench.showMessageBox({
                            title: 'VoidRP — Проверка перед экспортом',
                            message: warnings.join('\n\n') + '\n\nВсё равно экспортировать?',
                            buttons: ['Экспортировать', 'Отмена'],
                            callback(btn) { if (btn === 0) doExport(); },
                        });
                    } else {
                        doExport();
                    }
                },
            });

            // ── Action: Validate ─────────────────────────────────────────────

            actionValidate = new Action(`${PLUGIN_ID}_validate`, {
                name: 'Validate Cosmetics',
                icon: 'check_circle',
                description: 'Check all animations follow VoidRP slot naming convention',
                click() {
                    const valid = [], invalid = [], skipped = [];
                    Animation.all.forEach(a => {
                        if (a.name && a.name.startsWith('_')) {
                            skipped.push(a.name);
                            return;
                        }
                        const slot = detectSlot(a.name);
                        if (slot) valid.push({ name: a.name, slot });
                        else invalid.push(a.name);
                    });

                    if (!valid.length && !invalid.length && !skipped.length) {
                        Blockbench.showMessageBox({ title: 'VoidRP Cosmetics', message: 'No animations in project.' });
                        return;
                    }

                    const lines = [];
                    if (valid.length) {
                        lines.push(`✔ Valid (${valid.length}):`);
                        valid.forEach(v => lines.push(`  [${v.slot.id.padEnd(9)}] ${v.name}`));
                    }
                    if (skipped.length) {
                        if (lines.length) lines.push('');
                        lines.push(`⊘ Skipped by mod — name starts with "_" (${skipped.length}):`);
                        skipped.forEach(n => lines.push(`  ${n}`));
                    }
                    if (invalid.length) {
                        if (lines.length) lines.push('');
                        lines.push(`⚠ Unknown slot (${invalid.length}):`);
                        invalid.forEach(n => lines.push(`  ${n}`));
                        lines.push('');
                        lines.push('Rename to: head_tophat, body_cape, wings_angel, ...');
                    }

                    Blockbench.showMessageBox({
                        title: 'VoidRP Validation',
                        message: lines.join('\n'),
                        buttons: ['OK'],
                    });
                },
            });

            // ── Panel ────────────────────────────────────────────────────────

            panel = new Panel(`${PLUGIN_ID}_panel`, {
                name: 'VoidRP Cosmetics',
                icon: 'style',
                condition: { modes: ['animate'] },
                default_position: {
                    slot: 'left_bar',
                    float_position: [0, 0],
                    float_size: [280, 460],
                    height: 460,
                },
                component: {
                    data() {
                        return { items: [], cpmOk: false, slots: SLOTS };
                    },
                    computed: {
                        bySlot() {
                            const map = {};
                            SLOTS.forEach(s => { map[s.id] = []; });
                            map._unknown = [];
                            map._skipped = [];
                            this.items.forEach(item => {
                                if (item.skipped) map._skipped.push(item);
                                else map[item.slot ? item.slot.id : '_unknown'].push(item);
                            });
                            return map;
                        },
                        validCount()   { return this.items.filter(i => i.slot && !i.skipped).length; },
                        unknownCount() { return this.items.filter(i => !i.slot && !i.skipped).length; },
                        skippedCount() { return this.items.filter(i => i.skipped).length; },
                    },
                    methods: {
                        refresh() {
                            this.cpmOk = isCpmLoaded();
                            this.items = Animation.all.map(a => ({
                                name: a.name,
                                slot: detectSlot(a.name),
                                skipped: !!(a.name && a.name.startsWith('_')),
                            }));
                        },
                        doAdd()      { actionAdd.click();    setTimeout(() => this.refresh(), 400); },
                        doExport()   { actionExport.click(); },
                        doValidate() { actionValidate.click(); },
                    },
                    mounted() { this.refresh(); },
                    template: `
<div style="display:flex;flex-direction:column;height:100%;padding:8px;box-sizing:border-box;font-size:12px;">

  <!-- CPM status -->
  <div :style="{
    padding:'4px 8px', borderRadius:'4px', marginBottom:'6px', fontSize:'11px',
    background: cpmOk ? 'rgba(76,175,80,0.15)' : 'rgba(244,67,54,0.15)',
    color: cpmOk ? '#81c784' : '#e57373'
  }">
    {{ cpmOk ? '✔ CPM plugin loaded' : '✘ CPM plugin not found' }}
  </div>

  <!-- Output hint -->
  <div style="padding:4px 8px;border-radius:4px;margin-bottom:8px;font-size:10px;
              background:rgba(255,255,255,0.05);color:#888;line-height:1.5;">
    Output: <b style="color:#aaa">config/voidrp-cpm/models/</b><br>
    Needs: <b style="color:#aaa">.bbmodel</b> + <b style="color:#aaa">.cpmproject</b> → <code>/vc reload</code>
  </div>

  <!-- Stats bar -->
  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">
    <span style="font-weight:bold;font-size:13px;">Cosmetics</span>
    <span style="color:#888;font-size:11px;">
      {{ validCount }} ok
      {{ unknownCount ? ' · ' + unknownCount + ' ⚠' : '' }}
      {{ skippedCount ? ' · ' + skippedCount + ' ⊘' : '' }}
    </span>
  </div>

  <!-- List -->
  <div style="flex:1;overflow-y:auto;margin-bottom:8px;">

    <template v-for="slotDef in slots" :key="slotDef.id">
      <template v-if="bySlot[slotDef.id] && bySlot[slotDef.id].length">
        <div :style="{color:slotDef.color,fontSize:'10px',fontWeight:'bold',letterSpacing:'1px',
                      textTransform:'uppercase',marginTop:'10px',marginBottom:'3px'}">
          {{ slotDef.label }}
        </div>
        <div v-for="item in bySlot[slotDef.id]" :key="item.name"
             style="padding:3px 8px;border-radius:3px;margin-bottom:2px;
                    background:rgba(255,255,255,0.04);color:#ddd;">
          {{ item.name }}
        </div>
      </template>
    </template>

    <template v-if="bySlot._skipped && bySlot._skipped.length">
      <div style="color:#888;font-size:10px;font-weight:bold;letter-spacing:1px;
                  text-transform:uppercase;margin-top:10px;margin-bottom:3px;">
        ⊘ Skipped by mod
      </div>
      <div v-for="item in bySlot._skipped" :key="item.name"
           style="padding:3px 8px;border-radius:3px;margin-bottom:2px;
                  background:rgba(255,255,255,0.03);color:#666;">
        {{ item.name }}
      </div>
    </template>

    <template v-if="bySlot._unknown && bySlot._unknown.length">
      <div style="color:#ef5350;font-size:10px;font-weight:bold;letter-spacing:1px;
                  text-transform:uppercase;margin-top:10px;margin-bottom:3px;">
        ⚠ Unknown slot
      </div>
      <div v-for="item in bySlot._unknown" :key="item.name"
           style="padding:3px 8px;border-radius:3px;margin-bottom:2px;
                  background:rgba(239,83,80,0.12);color:#ef5350;">
        {{ item.name }}
      </div>
    </template>

    <div v-if="!items.length"
         style="color:#888;text-align:center;padding:28px 0;font-size:12px;line-height:1.8;">
      No animations yet.<br>
      Click <b>+ Add</b> to create a cosmetic.
    </div>
  </div>

  <!-- Buttons -->
  <div style="display:grid;grid-template-columns:1fr auto auto;gap:4px;">
    <button @click="doAdd"
            :disabled="!cpmOk"
            style="padding:5px;border:none;border-radius:3px;cursor:pointer;font-size:12px;
                   background:#1976d2;color:#fff;">
      + Add
    </button>
    <button @click="refresh"
            style="padding:5px 9px;border:none;border-radius:3px;cursor:pointer;
                   font-size:13px;background:#333;color:#ccc;" title="Refresh">
      ↻
    </button>
    <button @click="doValidate"
            style="padding:5px 9px;border:none;border-radius:3px;cursor:pointer;
                   font-size:13px;background:#333;color:#ccc;" title="Validate">
      ✔
    </button>
  </div>

  <button @click="doExport"
          :disabled="!cpmOk"
          style="margin-top:4px;padding:5px;border:none;border-radius:3px;cursor:pointer;
                 font-size:12px;background:#2e7d32;color:#fff;width:100%;">
    Export .bbmodel + .cpmproject
  </button>

</div>`,
                },
            });

            MenuBar.addAction(actionAdd,      'tools');
            MenuBar.addAction(actionExport,   'tools');
            MenuBar.addAction(actionValidate, 'tools');
        },

        onunload() {
            if (actionAdd)      actionAdd.delete();
            if (actionExport)   actionExport.delete();
            if (actionValidate) actionValidate.delete();
            if (panel)          panel.delete();
        },
    });

})();
