import { HEIGHT, WIDTH } from '../core/constants.js';
import { bell, easeOut } from '../core/transitions.js';
import { lerp, lerpPosition, unlerp } from '../core/utils.js';
import { parseData, parseGlobalData } from './Deserializer.js';
import { TooltipManager } from './TooltipManager.js';
import { fit, last, setAnimationProgress } from './utils.js';
import { AVATAR_RECT, NAME_RECT, ORGANS, PROTEIN_SEP, PROTEIN_RECT, SCORE_RECT, GAME_ZONE_RECT, ORGAN_ANCHORS, ORGAN_TILE_PADDING, ORGANS_ANIMATIONS_VALUES, SPORE_PARTICLES, ROTATIONS_MAP, ABORPTION_FRAMES, ABSORPTION_ANCHOR, WALL_SPAWN_FRAMES, WALL_SPAWN_ANCHOR, ROOT_STATES } from './assetConstants.js';
import { GRID_LINE_WIDTH, HUD_COLORS } from './gameConstants.js';
import ev from './events.js';
import { initMessages, renderMessageContainer } from './MessageBoxes.js';
import { flagForDestructionOnReinit, getRenderer } from '../core/rendering.js';
const api = {
    setDebugMode: (value) => {
        api.options.debugMode = value;
    },
    options: {
        debugMode: false,
        showOthersMessages: true,
        showMyMessages: true,
        meInGame: false,
    }
};
export { api };
export class ViewModule {
    constructor() {
        window.debug = this;
        this.states = [];
        this.pool = {};
        this.time = 0;
        this.tooltipManager = new TooltipManager();
        this.api = api;
        this.api.setDebugMode = (value) => {
            //hack for hiding ranking
            this.api.options.debugMode = value;
            this.container.parent.children[1].visible = !value;
        };
    }
    static get moduleName() {
        return 'graphics';
    }
    registerTooltip(container, getString) {
        container.interactive = true;
        this.tooltipManager.register(container, getString);
    }
    // Effects
    getFromPool(type) {
        if (!this.pool[type]) {
            this.pool[type] = [];
        }
        for (const e of this.pool[type]) {
            if (!e.busy) {
                e.busy = true;
                e.display.visible = true;
                return e;
            }
        }
        const e = this.createEffect(type);
        this.pool[type].push(e);
        e.busy = true;
        return e;
    }
    createEffect(type) {
        let display = null;
        const organAnimationKey = Object.keys(ORGANS_ANIMATIONS_VALUES).find(k => type.startsWith(k));
        if (type.startsWith('particle')) {
            const idx = parseInt(type.slice(8));
            display = PIXI.Sprite.from(SPORE_PARTICLES[idx]);
            display.anchor.set(0.5);
            this.sporeLayer.addChild(display);
        }
        else if (type === 'tail') {
            display = this.initTail();
            this.tailLayer.addChild(display);
        }
        else if (organAnimationKey != null) {
            const idx = parseInt(type.slice(organAnimationKey.length));
            const values = ORGANS_ANIMATIONS_VALUES[organAnimationKey];
            display = PIXI.AnimatedSprite.fromFrames(values.frames[idx]);
            display.loop = false;
            display.gotoAndStop(0);
            display.anchor.copyFrom(values.anchors ?? { x: 0.5, y: 0.5 });
            this.layersMap[organAnimationKey].addChild(display);
        }
        else if (type === 'absorb') {
            display = PIXI.AnimatedSprite.fromFrames(ABORPTION_FRAMES);
            display.anchor.copyFrom(ABSORPTION_ANCHOR);
            this.absorptionLayer.addChild(display);
        }
        else if (type === 'wall') {
            display = PIXI.AnimatedSprite.fromFrames(WALL_SPAWN_FRAMES);
            display.anchor.copyFrom(WALL_SPAWN_ANCHOR);
            this.wallSpawnLayer.addChild(display);
        }
        else {
            console.error('Unknown effect type', type);
        }
        return { busy: false, display };
    }
    updateScene(previousData, currentData, progress, playerSpeed) {
        const frameChange = (this.currentData !== currentData);
        const fullProgressChange = ((this.progress === 1) !== (progress === 1));
        this.previousData = previousData;
        this.currentData = currentData;
        this.progress = progress;
        this.playerSpeed = playerSpeed || 0;
        this.resetEffects();
        this.updateOrgans();
        this.updateHud();
        this.updateGrid();
        this.updateMovables();
        // Time-saving hack for hiding ranking
        this.container.parent.children[1].visible = !this.api.options.debugMode;
    }
    updateHud() {
        const currentData = this.currentData;
        for (let player of this.globalData.players) {
            const { score, proteins } = this.huds[player.index];
            for (let i = 0; i < 4; ++i) {
                proteins[i].text = currentData.storage[player.index][i].toString();
            }
            score.text = currentData.organs[player.index].length.toString();
        }
    }
    getAnimProgress({ start, end }, progress) {
        return unlerp(start, end, progress);
    }
    animateSpawn(event) {
        const p = this.getAnimProgress(event.animData, this.progress);
        if (p <= 0) {
            return;
        }
        const animation = this.getFromPool(`growth${event.playerIdx}`);
        this.placeInGameZone(animation.display, event.target);
        const growthEndP = 0.3;
        const scaleStartP = 0.25;
        const fadeInEndP = 0.3;
        const growthScaleEndP = 0.4;
        const growthP = unlerp(0, growthEndP, p);
        const scaleP = unlerp(scaleStartP, 1, p);
        const growthScaleP = unlerp(growthEndP, growthScaleEndP, p);
        setAnimationProgress(animation.display, growthP);
        animation.display.scale.set(1 - growthScaleP);
        const scale = this.easeOutElastic(scaleP);
        const alpha = unlerp(scaleStartP, fadeInEndP, p);
        const organ = this.organByTileIdx[this.getTileIdx(event.target)];
        this.updateOrgan(organ, {
            scale,
            alpha,
            organData: {
                playerIdx: event.playerIdx,
                id: event.id,
                type: event.organType,
                direction: event.direction,
                pos: event.target
            }
        });
    }
    animateGrow(event) {
        const p = this.getAnimProgress(event.animData, this.progress);
        if (p <= 0) {
            return;
        }
        this.animateSpawn(event);
        const organ = this.organByTileIdx[this.getTileIdx(event.target)];
        this.updateOrganTail(organ.tail, event.target, event.coord, easeOut(p), event.playerIdx);
    }
    animateCrash(event) {
        const p = this.getAnimProgress(event.animData, this.progress);
        if (p <= 0) {
            return;
        }
        const tileIdx = this.getTileIdx(event.coord);
        const tile = this.tiles[tileIdx];
        if (p < 1) {
            const animation = this.getFromPool('wall');
            this.placeInGameZone(animation.display, event.coord);
            setAnimationProgress(animation.display, p);
            const scale = this.tileSize / 178;
            animation.display.scale.set(scale);
            tile.wall.visible = false;
        }
        else {
            tile.wall.visible = true;
        }
        tile.protein.alpha = 1 - p;
        const growFrom = event.coords.slice(1);
        for (const coord of growFrom) {
            const fakeTail = this.getFromPool('tail');
            const fromOrgan = this.currentData.organByTileIdx[this.getTileIdx(coord)] ?? this.previousData.organByTileIdx[this.getTileIdx(coord)];
            const alpha = 1 - unlerp(0.9, 1, p);
            this.updateOrganTail(fakeTail.display, event.coord, coord, easeOut(p), fromOrgan.playerIdx ?? 0, alpha);
        }
    }
    animateAbsorb(event) {
        const p = this.getAnimProgress(event.animData, this.progress);
        if (p <= 0) {
            return;
        }
        const tileIdx = this.getTileIdx(event.coord);
        const tile = this.tiles[tileIdx];
        const fadeP = unlerp(0.1, 0.6, p);
        tile.protein.alpha = 1 - fadeP;
        if (p >= 1) {
            return;
        }
        const startP = 0.25;
        const animation = this.getFromPool('absorb');
        animation.display.scale.set(this.organScale * 1.5);
        this.placeInGameZone(animation.display, event.coord);
        const absP = unlerp(startP, 1, p);
        setAnimationProgress(animation.display, absP);
        if (p < startP) {
            animation.display.visible = false;
        }
        this.animateRoot(event.coord, 'HARVEST', event.playerIdx);
    }
    animateSpore(event) {
        const p = this.getAnimProgress(event.animData, this.progress);
        if (p <= 0 || p >= 1) {
            return;
        }
        const particleAnimStartP = 0.2;
        const sportAnimEndP = 0.5;
        if (p <= sportAnimEndP) {
            const organ = this.organByTileIdx[this.getTileIdx(event.coord)];
            organ.sprite.visible = false;
            const sporeAnimP = unlerp(0, sportAnimEndP, p);
            const animation = this.getFromPool(`spore${event.playerIdx}`);
            animation.display.scale.set(this.organScale);
            const direction = this.getCardinalDirectionBetween(event.coord, event.target);
            animation.display.rotation = (ROTATIONS_MAP[direction]);
            this.placeInGameZone(animation.display, event.coord);
            setAnimationProgress(animation.display, sporeAnimP);
        }
        if (p >= particleAnimStartP) {
            const particleAnimP = unlerp(particleAnimStartP, 1, p);
            const fx = this.getFromPool(`particle${event.playerIdx}`);
            const direction = this.getCardinalDirectionBetween(event.coord, event.target);
            fx.display.rotation = (ROTATIONS_MAP[direction]);
            const from = event.coord;
            const to = event.target;
            this.placeInGameZone(fx.display, lerpPosition(from, to, particleAnimP));
        }
    }
    animateHarvest(event) {
        const p = this.getAnimProgress(event.animData, this.progress);
        if (p <= 0 || p >= 1) {
            return;
        }
        const organ = this.organByTileIdx[this.getTileIdx(event.coord)];
        const animation = this.getFromPool(`harvest${event.playerIdx}`);
        animation.display.scale.set(this.organScale);
        const direction = this.getCardinalDirectionBetween(event.coord, event.target);
        animation.display.rotation = (ROTATIONS_MAP[direction]);
        this.placeInGameZone(animation.display, event.coord);
        setAnimationProgress(animation.display, p);
        this.updateOrgan(organ, {
            organData: {
                playerIdx: event.playerIdx,
                id: event.id,
                type: 'HARVESTER',
                direction: this.getCardinalDirectionBetween(event.coord, event.target),
                pos: event.coord
            }
        });
    }
    animateAttack(event) {
        const p = this.getAnimProgress(event.animData, this.progress);
        if (p <= 0 || p >= 1) {
            return;
        }
        const organ = this.organByTileIdx[this.getTileIdx(event.coord)];
        organ.sprite.visible = false;
        const animation = this.getFromPool(`attack${event.playerIdx}`);
        animation.display.scale.set(this.organScale);
        const direction = this.getCardinalDirectionBetween(event.coord, event.target);
        animation.display.rotation = (ROTATIONS_MAP[direction]);
        this.placeInGameZone(animation.display, event.coord);
        setAnimationProgress(animation.display, p);
        this.animateRoot(event.coord, 'ATTACK', event.playerIdx);
    }
    animateRoot(fromCoord, state, playerIdx) {
        const rootTileIdx = this.currentData.rootTileIdxByTileIdx[this.getTileIdx(fromCoord)];
        const root = this.currentData.organByTileIdx[rootTileIdx];
        if (root == null) {
            return;
        }
        const sprite = this.organByTileIdx[rootTileIdx].sprite;
        sprite.texture = PIXI.Texture.from(ROOT_STATES[playerIdx][state]);
    }
    getCardinalDirectionBetween(a, b) {
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        if (Math.abs(dx) > Math.abs(dy)) {
            return dx > 0 ? 'E' : 'W';
        }
        else {
            return dy > 0 ? 'S' : 'N';
        }
    }
    animateDeath(event) {
        const p = this.getAnimProgress(event.animData, this.progress);
        if (p <= 0) {
            return;
        }
        const animation = this.getFromPool(`death${event.playerIdx}`);
        animation.display.scale.set(this.organScale);
        this.placeInGameZone(animation.display, event.coord);
        const poofStartP = 0.1;
        const scaleEndP = 0.1;
        const poofP = unlerp(poofStartP, 1, p);
        setAnimationProgress(animation.display, poofP);
        if (p < poofStartP || p >= 1) {
            animation.display.visible = false;
        }
        const scale = lerp(1, 0.8, unlerp(0, scaleEndP, p));
        const alpha = p > 0.11 ? 0 : 1;
        const organ = this.organByTileIdx[this.getTileIdx(event.coord)];
        this.updateOrgan(organ, {
            scale,
            alpha,
            organData: {
                playerIdx: event.playerIdx,
                id: event.id,
                type: event.organType,
                direction: event.direction,
                pos: event.coord
            }
        });
        organ.tail.alpha = lerp(0.7, 0, unlerp(0, scaleEndP, p));
        this.animateRoot(event.coord, 'DEATH', event.playerIdx);
    }
    updateOrganTail(tail, target, from, progress, pIdx, alpha = 1) {
        if (progress <= 0) {
            return;
        }
        tail.visible = true;
        tail.alpha = 0.7 * alpha;
        const parentPos = this.toBoardPos(from);
        const childPos = this.toBoardPos(target);
        const angle = Math.atan2(childPos.y - parentPos.y, childPos.x - parentPos.x);
        const tileOffsetToWall = (this.tileSize / 2) / 5 * 4;
        tail.position.set(parentPos.x + this.tileSizeWithGrid / 2, parentPos.y + this.tileSizeWithGrid / 2);
        const direction = this.getCardinalDirectionBetween(from, target);
        if (direction === 'N') {
            tail.position.y -= tileOffsetToWall;
        }
        else if (direction === 'S') {
            tail.position.y += tileOffsetToWall;
        }
        if (direction === 'E') {
            tail.position.x += tileOffsetToWall;
        }
        else if (direction === 'W') {
            tail.position.x -= tileOffsetToWall;
        }
        tail.rotation = angle;
        const finalWidth = (this.tileSize / 2 - tileOffsetToWall) * 2;
        tail.width = finalWidth * progress;
        tail.height = this.tileSizeWithGrid / 3;
        tail.texture = PIXI.Texture.from(pIdx === 0 ? 'MurOrange' : 'MurBleu');
    }
    updateOrgan(organ, { organData, scale, zIndex, alpha, rotation }) {
        const { sprite, container } = organ;
        if (organData != null) {
            container.rotation = (ROTATIONS_MAP[organData.direction]);
            if (organData.type === 'ROOT') {
                container.rotation += Math.PI / 2;
            }
            if (organData.playerIdx != null) {
                const organType = organData.type;
                const spriteName = ORGANS[organData.playerIdx][organType];
                sprite.texture = PIXI.Texture.from(spriteName);
                sprite.anchor.copyFrom(ORGAN_ANCHORS[organType]);
            }
        }
        if (scale != null) {
            container.scale.set(scale);
        }
        if (zIndex != null) {
            sprite.zIndex = zIndex;
        }
        if (alpha != null) {
            sprite.alpha = alpha;
        }
        if (rotation != null) {
            container.rotation += rotation;
        }
        sprite.visible = true;
        return organ;
    }
    placeInGameZone(display, coord) {
        const pos = this.toBoardPos(coord);
        display.position.set(pos.x + this.tileSizeWithGrid / 2, pos.y + this.tileSizeWithGrid / 2);
    }
    updateOrgans() {
        for (const organ of this.organs) {
            this.updateOrgan(organ, { alpha: 1, rotation: 0, scale: 1, zIndex: 0 });
            organ.sprite.visible = false;
            organ.tail.visible = false;
        }
        for (const message of this.messages.flat()) {
            message.updateText('', 0, 0);
        }
        const currentData = this.currentData;
        const grows = currentData.events.filter(e => e.type === ev.GROW);
        const deaths = currentData.events.filter(e => e.type === ev.DEATH);
        const attacks = currentData.events.filter(e => e.type === ev.ATTACK);
        const harvests = currentData.events.filter(e => e.type === ev.HARVEST);
        const spores = currentData.events.filter(e => e.type === ev.SPORE);
        const spawns = currentData.events.filter(e => e.type === ev.SPAWN_ROOT);
        let nucleusIdx = [0, 0];
        for (let pIdx = 0; pIdx < this.globalData.playerCount; ++pIdx) {
            const player = this.globalData.players[pIdx];
            const data = this.progress < 1 ? this.previousData : currentData;
            for (const organData of data.organs[pIdx]) {
                const tileIdx = this.getTileIdx(organData.pos);
                const organ = this.organByTileIdx[tileIdx];
                this.updateOrgan(organ, { organData });
                if (organData.parentId != null && organData.parentId != 0) {
                    const parentData = data.organById[organData.parentId];
                    this.updateOrganTail(organ.tail, organData.pos, parentData.pos, 1, pIdx);
                }
                else {
                    organ.tail.visible = false;
                    // Update message
                    const text = currentData.messages[pIdx][organData.id] ?? '';
                    if (text !== '') {
                        const messageIdx = Math.min(this.messages[0].length - 1, nucleusIdx[player.index]++);
                        const message = this.messages[player.index][messageIdx];
                        const boardPos = this.toBoardPos(organData.pos);
                        let globalPoint = this.gameZone.toGlobal(boardPos);
                        let containerPoint = this.container.toLocal(globalPoint);
                        message.updateText(text, containerPoint.x, containerPoint.y);
                    }
                }
            }
        }
        for (const event of grows) {
            this.animateGrow(event);
        }
        for (const event of spawns) {
            event.target = event.coord;
            event.organType = 'ROOT';
            this.animateSpawn(event);
        }
        for (const event of attacks) {
            this.animateAttack(event);
        }
        for (const event of deaths) {
            this.animateDeath(event);
        }
        for (const event of harvests) {
            this.animateHarvest(event);
        }
        for (const event of spores) {
            this.animateSpore(event);
        }
    }
    getTileIdx(coord) {
        return coord.y * this.globalData.width + coord.x;
    }
    fromTileIdx(tileIdx) {
        return {
            x: tileIdx % this.globalData.width,
            y: Math.floor(tileIdx / this.globalData.width)
        };
    }
    toBoardPos(coord) {
        return {
            x: coord.x * this.tileSizeWithGrid,
            y: coord.y * this.tileSizeWithGrid
        };
    }
    upThenDown(t) {
        return Math.min(1, bell(t) * 2);
    }
    updateMovables() {
        for (const m of this.movables) {
            const prev = m.getPos(this.previousData);
            const cur = m.getPos(this.currentData);
            let visible = true;
            let alpha = 1;
            if (prev && cur) {
                const pos = lerpPosition(prev, cur, this.progress);
                m.entity.position.copyFrom(pos);
            }
            else if (prev) {
                m.entity.position.copyFrom(prev);
                alpha = 1 - this.progress;
            }
            else if (cur) {
                m.entity.position.copyFrom(cur);
                alpha = this.progress;
            }
            else {
                visible = false;
            }
            m.entity.visible = visible;
            m.entity.alpha = alpha;
        }
    }
    resetEffects() {
        for (const type in this.pool) {
            for (const effect of this.pool[type]) {
                effect.display.visible = false;
                effect.busy = false;
            }
        }
    }
    animateScene(delta) {
        this.time += delta;
        for (const player of this.globalData.players) {
            for (let i = 0; i < 10; ++i) {
                const message = this.messages[player.index][i];
                renderMessageContainer.bind(this)(message, player.index, delta);
            }
        }
    }
    asLayer(func) {
        const layer = new PIXI.Container();
        func.bind(this)(layer);
        return layer;
    }
    reinitScene(container, canvasData) {
        this.time = 0;
        this.oversampling = canvasData.oversampling;
        this.container = container;
        this.pool = {};
        this.canvasData = canvasData;
        this.movables = [];
        this.tileSizeWithGrid = Math.min(GAME_ZONE_RECT.w / this.globalData.width, GAME_ZONE_RECT.h / this.globalData.height);
        this.tileSize = this.tileSizeWithGrid - GRID_LINE_WIDTH;
        this.organSize = this.tileSize - ORGAN_TILE_PADDING * 2;
        this.sporeLayer = new PIXI.Container();
        this.tailLayer = new PIXI.Container();
        this.growthLayer = new PIXI.Container();
        this.harvestLayer = new PIXI.Container();
        this.organLayer = this.asLayer(this.initOrgans);
        const messageLayer = this.asLayer(initMessages);
        this.attackLayer = new PIXI.Container();
        this.absorptionLayer = new PIXI.Container();
        this.wallSpawnLayer = new PIXI.Container();
        this.layersMap = {
            growth: this.growthLayer,
            harvest: this.harvestLayer,
            spore: this.sporeLayer,
            death: this.organLayer,
            attack: this.attackLayer
        };
        const tooltipLayer = this.tooltipManager.reinit();
        tooltipLayer.interactiveChildren = false;
        const gameZone = new PIXI.Container();
        const background = PIXI.Sprite.from('Background_2.jpg');
        const grid = this.asLayer(this.initGrid);
        const hud = this.asLayer(this.initHud);
        gameZone.addChild(grid);
        gameZone.addChild(this.tailLayer);
        gameZone.addChild(this.wallLayer);
        gameZone.addChild(this.wallSpawnLayer);
        gameZone.addChild(this.growthLayer);
        gameZone.addChild(this.sporeLayer);
        gameZone.addChild(this.organLayer);
        gameZone.addChild(this.harvestLayer);
        gameZone.addChild(this.attackLayer);
        gameZone.addChild(this.absorptionLayer);
        gameZone.x = GAME_ZONE_RECT.x;
        gameZone.y = GAME_ZONE_RECT.y;
        const gameWidth = this.globalData.width * this.tileSizeWithGrid;
        const gameHeight = this.globalData.height * this.tileSizeWithGrid;
        gameZone.x += (GAME_ZONE_RECT.w - gameWidth) / 2;
        gameZone.y += (GAME_ZONE_RECT.h - gameHeight) / 2;
        this.gameZone = gameZone;
        // gameZone.addChild(messageLayer)
        container.addChild(background);
        container.addChild(gameZone);
        container.addChild(hud);
        container.addChild(messageLayer);
        container.addChild(tooltipLayer);
        container.interactive = true;
        container.on('mousemove', (event) => {
            this.tooltipManager.moveTooltip(event);
        });
        hud.interactiveChildren = false;
        this.tooltipManager.registerGlobal((data) => {
            const pos = data.getLocalPosition(gameZone);
            const x = Math.floor(pos.x / this.tileSizeWithGrid);
            const y = Math.floor(pos.y / this.tileSizeWithGrid);
            if (x < 0 || x >= this.globalData.width || y < 0 || y >= this.globalData.height) {
                return null;
            }
            const blocks = [];
            const tile = this.currentData.tiles[y * this.globalData.width + x];
            if (tile.organ != null) {
                blocks.push(`Organ ${tile.organ.id}\n${tile.organ.type} ${tile.organ.direction}`);
            }
            blocks.push(`(${x}, ${y})`);
            return blocks.join('\n--------\n');
        });
    }
    placeInHUD(element, { x, y, w, h }, pIdx) {
        fit(element, w, h);
        element.position.set(pIdx ? WIDTH - 1 - x : x, y);
        element.anchor.set(pIdx ? 1 : 0, 0);
    }
    initHud(layer) {
        const background = PIXI.Sprite.from('HUD.png');
        layer.addChild(background);
        this.huds = [];
        for (const player of this.globalData.players) {
            const avatar = PIXI.Sprite.from(player.avatar);
            const name = new PIXI.Text(player.name, {
                fontSize: '48px',
                fill: HUD_COLORS[player.index],
                fontWeight: 'bold'
            });
            const score = new PIXI.Text('0', {
                fontSize: '48px',
                fill: HUD_COLORS[player.index],
                fontWeight: 'bold'
            });
            this.placeInHUD(avatar, AVATAR_RECT, player.index);
            this.placeInHUD(name, NAME_RECT, player.index);
            this.placeInHUD(score, SCORE_RECT, player.index);
            const proteins = [];
            for (let i = 0; i < 4; ++i) {
                const x = i * PROTEIN_SEP + PROTEIN_RECT.x;
                const protein = new PIXI.Text('0', {
                    fontSize: '48px',
                    fill: HUD_COLORS[player.index],
                    fontWeight: 'bold'
                });
                this.placeInHUD(protein, { ...PROTEIN_RECT, x }, player.index);
                layer.addChild(protein);
                proteins.push(protein);
            }
            layer.addChild(avatar, name, score);
            if (player.index === 1) {
                avatar.x -= 2;
                avatar.y += 2;
                proteins.reverse();
            }
            this.huds.push({ avatar, name, score, proteins });
        }
    }
    initTail() {
        const tail = PIXI.Sprite.from('MurOrange');
        tail.anchor.set(0, 0.5);
        return tail;
    }
    initOrgans(layer) {
        this.organByTileIdx = {};
        this.organs = [];
        for (const tileIdx of this.globalData.tileIdxInNeedOfOrgan) {
            const sprite = PIXI.Sprite.from(ORGANS[0].ROOT);
            const tail = this.initTail();
            sprite.anchor.copyFrom(ORGAN_ANCHORS.ROOT);
            fit(sprite, this.organSize, this.organSize);
            this.organScale = sprite.scale.x;
            const container = new PIXI.Container();
            const organ = { sprite, tail, container };
            this.organByTileIdx[tileIdx] = organ;
            this.organs.push(organ);
            this.placeInGameZone(container, this.fromTileIdx(tileIdx));
            container.addChild(sprite);
            layer.addChild(container);
            this.tailLayer.addChild(tail);
        }
    }
    initGrid(layer) {
        this.tiles = [];
        this.wallLayer = new PIXI.Container();
        const gridLines = new PIXI.Graphics();
        gridLines.lineStyle(GRID_LINE_WIDTH, 0x000000, 1);
        gridLines.x = GRID_LINE_WIDTH;
        gridLines.y = GRID_LINE_WIDTH;
        for (let y = 0; y <= this.globalData.height; ++y) {
            gridLines.moveTo(0, y * this.tileSizeWithGrid);
            gridLines.lineTo(this.globalData.width * this.tileSizeWithGrid, y * this.tileSizeWithGrid);
        }
        for (let x = 0; x <= this.globalData.width; ++x) {
            gridLines.moveTo(x * this.tileSizeWithGrid, 0);
            gridLines.lineTo(x * this.tileSizeWithGrid, this.globalData.height * this.tileSizeWithGrid);
        }
        // Render this graphics into a texture: (//TODO: optimze by scaling it down during render)
        const texture = PIXI.RenderTexture.create({ width: WIDTH, height: HEIGHT });
        flagForDestructionOnReinit(texture);
        getRenderer().render(gridLines, texture);
        const gridLineSprite = new PIXI.Sprite(texture);
        gridLineSprite.alpha = 0.2;
        // An offset was needed to fit the lines into the render texture, another to center it around the tiles
        gridLineSprite.position.set(-GRID_LINE_WIDTH - 1, -GRID_LINE_WIDTH - 1);
        layer.addChild(gridLineSprite);
        for (let y = 0; y < this.globalData.height; ++y) {
            for (let x = 0; x < this.globalData.width; ++x) {
                const tileContainer = new PIXI.Container();
                tileContainer.x = this.tileSizeWithGrid * x;
                tileContainer.y = this.tileSizeWithGrid * y;
                const wall = PIXI.Sprite.from('Mur_2');
                wall.width = this.tileSize;
                wall.height = this.tileSize;
                const protein = PIXI.Sprite.from('Prot_A');
                fit(protein, this.tileSize, this.tileSize);
                protein.visible = false;
                protein.anchor.set(0.5);
                protein.position.set(this.tileSizeWithGrid / 2, this.tileSizeWithGrid / 2);
                tileContainer.addChild(wall);
                tileContainer.addChild(protein);
                this.tiles.push({ wall, protein });
                this.wallLayer.addChild(tileContainer);
            }
        }
    }
    updateGrid() {
        let tileIdx = 0;
        const data = this.progress < 1 ? this.previousData : this.currentData;
        for (let y = 0; y < this.globalData.height; ++y) {
            for (let x = 0; x < this.globalData.width; ++x) {
                const tileData = data.tiles[tileIdx];
                const tile = this.tiles[tileIdx];
                tileIdx++;
                tile.wall.visible = tileData.obstacle;
                tile.wall.alpha = 1;
                tile.protein.alpha = 1;
                if (tileData.protein === 'X') {
                    tile.protein.visible = false;
                }
                else {
                    tile.protein.texture = PIXI.Texture.from(`Prot_${tileData.protein}`);
                    tile.protein.visible = true;
                }
            }
        }
        const crashes = this.currentData.events.filter(e => e.type === ev.CRASH);
        const absorbs = this.currentData.events.filter(e => e.type === ev.ABSORB);
        for (const crash of crashes) {
            this.animateCrash(crash);
        }
        for (const absorb of absorbs) {
            this.animateAbsorb(absorb);
        }
    }
    easeOutElastic(x) {
        const c4 = (2 * Math.PI) / 3;
        return x === 0
            ? 0
            : x === 1
                ? 1
                : Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * c4) + 1;
    }
    handleGlobalData(players, raw) {
        const globalData = parseGlobalData(raw);
        api.options.meInGame = !!players.find(p => p.isMe);
        this.globalData = {
            ...globalData,
            players: players,
            playerCount: players.length,
            tileIdxInNeedOfOrgan: new Set()
        };
    }
    handleFrameData(frameInfo, raw) {
        const dto = parseData(raw, this.globalData);
        const prev = last(this.states);
        const lastFrameOrgans = prev ? prev.organs : this.globalData.organs;
        const organById = lastFrameOrgans.flat().reduce((acc, organ) => {
            return { ...acc, [organ.id]: organ };
        }, {});
        const organByTileIdx = lastFrameOrgans.flat().reduce((acc, organ) => {
            const tileIdx = this.getTileIdx(organ.pos);
            return { ...acc, [tileIdx]: organ };
        }, {});
        const rootTileIdxByTileIdx = {};
        for (const organ of lastFrameOrgans.flat()) {
            const tileIdx = this.getTileIdx(organ.pos);
            if (tileIdx in rootTileIdxByTileIdx) {
                continue;
            }
            if (organ.type === 'ROOT') {
                rootTileIdxByTileIdx[tileIdx] = tileIdx;
                continue;
            }
            let root = organ;
            while (root.parentId != null && root.parentId != 0) {
                root = organById[root.parentId];
            }
            rootTileIdxByTileIdx[tileIdx] = this.getTileIdx(root.pos);
        }
        let tiles;
        if (!prev) {
            tiles = this.globalData.tiles.map((t, idx) => ({ ...t, organ: organByTileIdx[idx] }));
        }
        else {
            tiles = prev.tiles.map((t, idx) => ({ ...t, organ: organByTileIdx[idx] }));
        }
        // Record all organ positions
        if (!prev) {
            for (const organ of lastFrameOrgans.flat()) {
                const tileIdx = this.getTileIdx(organ.pos);
                this.globalData.tileIdxInNeedOfOrgan.add(tileIdx);
            }
        }
        for (const coord of dto.events.map(e => e.coords).flat()) {
            const tileIdx = this.getTileIdx(coord);
            this.globalData.tileIdxInNeedOfOrgan.add(tileIdx);
        }
        const eventMapPerPlayer = [{}, {}];
        for (const event of dto.events) {
            if (eventMapPerPlayer[event.playerIdx][event.type] == null) {
                eventMapPerPlayer[event.playerIdx][event.type] = [];
            }
            const eventMap = eventMapPerPlayer[event.playerIdx][event.type];
            eventMap.push(event);
            const updateTiles = (coord, protein, obstacle, organ) => {
                const tileIdx = this.getTileIdx(coord);
                tiles = [...tiles];
                tiles[tileIdx] = { protein, obstacle, organ };
                if (organ != null) {
                    organById[organ.id] = organ;
                    organByTileIdx[tileIdx] = organ;
                }
                else {
                    const curOrgan = organByTileIdx[tileIdx];
                    if (curOrgan != null) {
                        delete organById[curOrgan.id];
                        delete organByTileIdx[tileIdx];
                    }
                }
            };
            if (event.type === ev.CRASH) {
                updateTiles(event.coord, 'X', true, null);
            }
            else if (event.type === ev.GROW) {
                const newOrgan = {
                    id: event.id,
                    pos: event.target,
                    type: event.organType,
                    direction: event.direction,
                    parentId: organByTileIdx[this.getTileIdx(event.coord)].id,
                    playerIdx: event.playerIdx
                };
                rootTileIdxByTileIdx[this.getTileIdx(event.target)] = rootTileIdxByTileIdx[this.getTileIdx(event.coord)];
                updateTiles(event.target, 'X', false, newOrgan);
            }
            else if (event.type === ev.SPAWN_ROOT) {
                const newOrgan = {
                    id: event.id,
                    pos: event.coord,
                    type: 'ROOT',
                    direction: event.direction,
                    playerIdx: event.playerIdx
                };
                rootTileIdxByTileIdx[this.getTileIdx(event.coord)] = this.getTileIdx(event.coord);
                updateTiles(event.coord, 'X', false, newOrgan);
            }
            else if (event.type === ev.DEATH) {
                updateTiles(event.coord, 'X', false, null);
            }
            event.animData.start /= frameInfo.frameDuration;
            event.animData.end /= frameInfo.frameDuration;
        }
        const organs = [[], []];
        Object.values(organById).forEach(organ => {
            organs[organ.playerIdx].push(organ);
        });
        const frameData = {
            ...dto,
            ...frameInfo,
            tiles,
            organById,
            organByTileIdx,
            organs,
            previous: null,
            rootTileIdxByTileIdx
        };
        frameData.previous = last(this.states) ?? frameData;
        this.states.push(frameData);
        return frameData;
    }
}
