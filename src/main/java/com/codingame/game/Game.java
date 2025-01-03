
package com.codingame.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import com.codingame.event.Animation;
import com.codingame.event.EventData;
import com.codingame.game.action.Action;
import com.codingame.game.action.ActionType;
import com.codingame.game.grid.Coord;
import com.codingame.game.grid.Direction;
import com.codingame.game.grid.Grid;
import com.codingame.game.grid.Protein;
import com.codingame.game.grid.Tile;
import com.codingame.game.pathfinding.PathFinder;
import com.codingame.game.pathfinding.PathFinder.PathFinderResult;
import com.codingame.gameengine.core.GameManager;
import com.codingame.gameengine.core.MultiplayerGameManager;
import com.codingame.gameengine.module.endscreen.EndScreenModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class Game {
    public static final int PROTEIN_COUNT = 4;
    public static final int MIN_STARTING_PROTEINS = 3;
    public static final int MAX_STARTING_PROTEINS = 10;
    public static final int PROTEIN_PER_ABSORB = 3;
    public static boolean SHOW_WALL_POINTS = false;

    public static boolean SPORE_OVER_OBSTACLES = false;

    @Inject private MultiplayerGameManager<Player> gameManager;
    @Inject private EndScreenModule endScreenModule;
    @Inject private Animation animation;
    @Inject private PathFinder pathfinder;

    List<Player> players;
    Map<Integer, Organ> organById;
    Map<Coord, Organ> organByCoord;
    Random random;
    Grid grid;
    int turn;

    int[][] deathTypes;
    int[][] growthTypes;
    int[][] harvestedProteinsCounter;
    int[][] absorbedProteinsCounter;
    int[] cascadeChildrenDeathsCounter;
    int[] proteinTheftsCounter;
    int[] totalSporeDistance;
    int mutualKillsCounter;
    int mutualSpawnsCounter;
    int[] wallPoints;
    int deniedSporeCounter;
    int doubleDeniedSporesCounter;

    public void init() {
        SHOW_WALL_POINTS = Boolean.valueOf(System.getProperty("wall.points", "false"));
        if (!SHOW_WALL_POINTS) {
            SHOW_WALL_POINTS = Boolean.valueOf(gameManager.getGameParameters().getOrDefault("SHOW_WALL_POINTS", "false").toString());
        }

        turn = 0;
        organById = new HashMap<>();
        organByCoord = new HashMap<>();
        random = gameManager.getRandom();
        players = gameManager.getPlayers();

        int leagueLevel = gameManager.getLeagueLevel();
        if (leagueLevel <= 4) {
            initTutorial(leagueLevel);
        } else {
            grid = GridMaker.initGrid(random);
            initPlayers();
        }

        deathTypes = new int[][] { { 0, 0, 0, 0, 0 }, { 0, 0, 0, 0, 0 } };
        growthTypes = new int[][] { { 0, 0, 0, 0, 0 }, { 0, 0, 0, 0, 0 } };
        harvestedProteinsCounter = new int[][] { { 0, 0, 0, 0 }, { 0, 0, 0, 0 } };
        absorbedProteinsCounter = new int[][] { { 0, 0, 0, 0 }, { 0, 0, 0, 0 } };
        cascadeChildrenDeathsCounter = new int[] { 0, 0 };
        proteinTheftsCounter = new int[] { 0, 0 };
        totalSporeDistance = new int[] { 0, 0 };
        mutualKillsCounter = 0;
        mutualSpawnsCounter = 0;
        wallPoints = new int[] { 0, 0 };
        deniedSporeCounter = 0;
        doubleDeniedSporesCounter = 0;
    }

    private void initTutorial(int leagueLevel) {
        grid = GridMaker.initTutorialGrid(leagueLevel, random);

        // Initial storage
        for (Player player : players) {
            player.storage.put(Protein.A, 10);
            if (leagueLevel >= 2) {
                player.storage.put(Protein.C, 1);
                player.storage.put(Protein.D, 1);
            }
            if (leagueLevel >= 3) {
                player.storage.put(Protein.A, 50);
                player.storage.put(Protein.B, 50);
                player.storage.put(Protein.C, 50);
                player.storage.put(Protein.D, 0);
            }
            if (leagueLevel >= 4) {
                player.storage.put(Protein.A, 6);
                player.storage.put(Protein.B, 2);
                player.storage.put(Protein.C, 2);
                player.storage.put(Protein.D, 3);
            }
        }

        // Initial organism
        Coord spawn = grid.spawn;
        for (Player player : players) {
            Organ root = new Organ(player, OrganType.ROOT, Direction.NORTH);

            if (player.getIndex() == 1) {
                if (leagueLevel == 3) {
                    spawn = grid.opposite(spawn);
                } else {
                    spawn = spawn.add(0, 4);
                }
            }
            placeOrgan(root, spawn);
        }
    }

    private void removeOrgan(Organ organ, int frameTime) {
        Player player = organ.owner;
        Tile tile = grid.get(organ.pos);

        tile.clear();
        player.removeOrgan(organ);

        animation.setFrameTime(frameTime);
        launchDeathEvent(organ);

        organById.remove(organ.id);
        organByCoord.remove(organ.pos);

        cascadeChildrenDeathsCounter[player.getIndex()] += organ.children.size();

        List<Organ> removed = new ArrayList<>();
        removed.addAll(organ.children);
        removed.forEach(o -> this.removeOrgan(o, frameTime + Animation.TENTH));

        if (organ.parent != null) {
            organ.parent.children.remove(organ);
        }

        deathTypes[player.getIndex()][organ.getType().getIndex()]++;
    }

    private void placeOrgan(Organ organ, Coord coord) {
        Player player = organ.owner;
        Tile tile = grid.get(coord);
        Protein protein = tile.getProtein();

        organ.setPos(coord);
        tile.placeOrgan(organ);
        player.addOrgan(organ);
        player.absorb(protein);
        if (protein != null) {
            absorbedProteinsCounter[player.getIndex()][protein.getIndex()] += PROTEIN_PER_ABSORB;
            if (
                grid.getNeighbours(coord).stream()
                    .map(neighCoord -> grid.get(neighCoord).getOrgan())
                    .flatMap(Optional::stream)
                    .anyMatch(
                        neighOrgan -> neighOrgan.isHarvester() && !player.equals(neighOrgan.getOwner()) && coord.equals(neighOrgan.getFacedCoord())
                    )
            ) {
                proteinTheftsCounter[player.getIndex()]++;
            }
            launchAbsorbEvent(organ);
        }
        organById.put(organ.id, organ);
        organByCoord.put(coord, organ);
    }

    private void initPlayers() {
        // Initial storage
        for (int i = 0; i < PROTEIN_COUNT; ++i) {
            int startingProteinCount = random.nextInt(MIN_STARTING_PROTEINS, MAX_STARTING_PROTEINS + 1);
            for (Player player : players) {
                player.storage.put(Protein.fromIndex(i), startingProteinCount);
            }
        }

        // Initial organism
        Coord spawn = grid.spawn;
        for (Player player : players) {
            Organ root = new Organ(player, OrganType.ROOT, Direction.NORTH);
            if (player.getIndex() == 1) {
                spawn = grid.opposite(spawn);
            }
            placeOrgan(root, spawn);
        }
    }

    public void resetGameTurnData() {
        animation.reset();
        players.forEach(Player::reset);
    }

    private void launchCrashEvent(Coord coord, List<Coord> growFrom) {
        EventData e = new EventData();
        e.type = EventData.CRASH;
        e.coord = coord;
        e.growFrom = growFrom;
        animation.startAnim(e, Animation.THIRD);
    }

    private void launchAbsorbEvent(Organ organ) {
        EventData e = new EventData();
        e.type = EventData.ABSORB;
        e.coord = organ.pos;
        e.id = organ.id;
        e.playerIdx = organ.getOwner().getIndex();
        animation.startAnim(e, Animation.WHOLE);
    }

    private void launchGrowEvent(Organ parent, Organ child) {
        EventData e = new EventData();
        e.type = EventData.GROW;
        e.coord = parent.getPos();
        e.target = child.getPos();
        e.organType = child.type;
        e.direction = child.direction;
        e.playerIdx = parent.getOwner().getIndex();
        e.id = child.getId();
        animation.startAnim(e, Animation.WHOLE);
    }

    private void launchSporeEvent(Coord target, Organ organ) {
        EventData e = new EventData();
        e.type = EventData.SPORE;
        e.coord = organ.getPos();
        e.target = target;
        e.id = organ.id;
        e.playerIdx = organ.getOwner().getIndex();
        int dist = Math.abs(organ.getPos().manhattanTo(target));
        animation.startAnim(e, Animation.TENTH * dist);
    }

    private void launchAttackEvent(Coord target, Organ organ) {
        EventData e = new EventData();
        e.type = EventData.ATTACK;
        e.coord = organ.getPos();
        e.target = target;
        e.id = organ.getId();
        e.playerIdx = organ.getOwner().getIndex();
        animation.startAnim(e, Animation.THIRD);
    }

    private void launchDeathEvent(Organ organ) {
        EventData e = new EventData();
        e.type = EventData.DEATH;
        e.coord = organ.getPos();
        e.id = organ.getId();
        e.organType = organ.type;
        e.direction = organ.direction;
        e.playerIdx = organ.getOwner().getIndex();
        animation.startAnim(e, Animation.WHOLE);
    }

    private void launchHarvestEvent(Coord target, Organ organ) {
        EventData e = new EventData();
        e.type = EventData.HARVEST;
        e.coord = organ.getPos();
        e.target = target;
        e.id = organ.getId();
        e.playerIdx = organ.getOwner().getIndex();
        animation.startAnim(e, 400);
    }

    private void launchSpawnNucleusEvent(Organ organ) {
        EventData e = new EventData();
        e.type = EventData.SPAWN_NUCLEUS;
        e.coord = organ.getPos();
        e.direction = organ.direction;
        e.id = organ.getId();
        e.playerIdx = organ.getOwner().getIndex();
        animation.startAnim(e, Animation.WHOLE);
    }

    public void doActions() {
        Map<Coord, List<Growth>> growthsByCoord = new HashMap<>();

        for (Player player : players) {
            for (Action action : player.actions) {
                if (action.getType() == ActionType.GROWTH || action.getType() == ActionType.SPORE) {
                    Coord target = action.getTarget();
                    OrganType organType = action.getOrganType();
                    Integer fromId = action.getFromId();

                    boolean canGrow = checkCanGrowWithPathfinding(
                        player, target, organType, fromId, action.getType() == ActionType.SPORE, action
                    );

                    if (canGrow) {
                        // because pathfinding can change from/target
                        target = action.getTarget();
                        organType = action.getOrganType();
                        fromId = action.getFromId();

                        int rootId = getRootId(getOrganById(fromId));
                        payForOrgan(player, organType);
                        player.setActed(rootId);
                        growthsByCoord.compute(target, (k, v) -> {
                            List<Growth> list = v == null ? new ArrayList<>() : v;
                            list.add(new Growth(player, action, action.getType() == ActionType.SPORE));
                            return list;
                        });
                        handleMessage(action, player, rootId);
                    }
                } else {
                    handleMessage(action, player, null);
                }
            }
            player.assignMessages();
        }

        int frameTimeBeforeSpores = animation.getFrameTime();
        growthsByCoord.forEach((coord, growths) -> {
            for (Growth growth : growths) {
                animation.setFrameTime(frameTimeBeforeSpores);
                if (growth.isSpore()) {
                    launchSporeEvent(growth.action().getTarget(), getOrganById(growth.action().getFromId()));
                    animation.catchUp();
                }
            }
        });

        animation.catchUp();

        growthsByCoord.forEach((coord, growths) -> {
            // Wall created by cell collision
            if (growths.size() > 1) {
                grid.get(coord).setObstacle();
                launchCrashEvent(coord, getWouldbeParentCoords(growths));
                mutualSpawnsCounter++;
                boolean[] selfCollided = getSelfCollisions(growths);
                for (int i = 0; i < gameManager.getPlayerCount(); ++i) {
                    if (selfCollided[i]) {
                        wallPoints[i]++;
                    }
                }

                long nbSporesBlocked = growths.stream().filter(growth -> growth.isSpore()).count();
                if (nbSporesBlocked == 1) {
                    deniedSporeCounter++;
                } else if (nbSporesBlocked == 2) {
                    doubleDeniedSporesCounter++;
                }
                return;
            }
            Growth growth = growths.get(0);
            Organ organ = new Organ(growth.player(), growth.action().getOrganType(), growth.action().getDirection());
            placeOrgan(organ, coord);
            if (!organ.isNucleus()) {
                Organ parent = organById.get(growth.action().getFromId());
                connect(parent, organ);
                launchGrowEvent(parent, organ);
            } else {
                launchSpawnNucleusEvent(organ);
            }

            if (growth.isSpore()) {
                totalSporeDistance[growth.player().getIndex()] += coord.manhattanTo(getOrganById(growth.action().getFromId()).getPos());
            }
            growthTypes[growth.player().getIndex()][growth.action().getOrganType().getIndex()]++;
        });
        animation.catchUp();
    }

    private boolean[] getSelfCollisions(List<Growth> growths) {
        boolean[] collisions = new boolean[2];

        growths.stream()
            .collect(Collectors.groupingBy(Growth::player, Collectors.counting()))
            .forEach((player, count) -> {
                if (count > 1) {
                    collisions[player.getIndex()] = true;
                }
            });

        return collisions;

    }

    private void handleMessage(Action action, Player player, Integer rootId) {
        String message = action.getMessage();
        if (message != null) {
            String trimmed = message.trim();
            if (trimmed.length() > 48) {
                trimmed = trimmed.substring(0, 46) + "...";
            }
            if (trimmed.length() > 0) {
                if (action.getType() == ActionType.GROWTH || action.getType() == ActionType.SPORE) {
                    player.assignMessage(rootId, trimmed);
                } else {
                    player.addMessage(trimmed);
                }
            }
        }

    }

    private List<Coord> getWouldbeParentCoords(List<Growth> growths) {
        return growths.stream()
            .filter(growth -> !growth.isSpore())
            .map(growth -> growth.action().getFromId())
            .map(organById::get)
            .map(Organ::getPos)
            .toList();
    }

    public void connect(Organ parent, Organ child) {
        parent.children.add(child);
        child.setParent(parent);

    }

    public void performGameUpdate(int turn) {
        this.turn = turn;
        doActions();
        doHarvests();

        for (Player p : players) {
            String summary = p.getProteinSummary();
            if (!summary.isEmpty()) {
                gameManager.addToGameSummary(summary);
            }

        }

        doAttacks();
        if (isGameOver()) {
            gameManager.endGame();
        }
        computeEvents();
    }

    private void computeEvents() {
        int minTime = 500;

        animation.catchUp();

        int frameTime = Math.max(
            animation.getFrameTime(),
            minTime
        );
        gameManager.setFrameDuration(frameTime);

    }

    private void doHarvests() {
        for (Player player : players) {
            Set<Harvest> harvests = new HashSet<>();
            for (Organ o : player.organs) {
                if (o.isHarvester()) {
                    Coord harvesting = o.getFacedCoord();
                    Tile t = grid.get(harvesting);
                    if (t.isValid() && t.getProtein() != null) {
                        if (!harvests.stream().anyMatch(h -> h.coord().equals(harvesting))) {
                            harvests.add(new Harvest(t, o, harvesting));
                        }
                    }
                }
            }
            for (Harvest h : harvests) {
                Protein protein = h.tile().getProtein();
                player.harvestProtein(protein);
                harvestedProteinsCounter[player.getIndex()][protein.getIndex()]++;
                launchHarvestEvent(h.coord(), h.organ());
            }
        }
        animation.catchUp();
    }

    private void doAttacks() {
        Set<Integer> dyingIds = new HashSet<>();
        for (Organ o : organById.values()) {
            if (o.isTentacle()) {
                Coord attacking = o.getFacedCoord();
                Organ target = organByCoord.get(attacking);
                if (target != null && target.owner != o.owner) {
                    dyingIds.add(target.getId());
                    launchAttackEvent(target.getPos(), o);
                    if (target.isTentacle() && o.equals(organByCoord.get(target.getFacedCoord()))) {
                        mutualKillsCounter++;
                    }
                }
            }
        }
        animation.catchUp();

        int frameTime = animation.getFrameTime();

        for (Integer id : dyingIds) {
            Organ organ = organById.get(id);
            //TODO: its possible that a node gets killed both by death of parent and by tentacle.
            // Needs handling for animation, the killed by tentacle should happen first
            if (organ != null) {
                removeOrgan(organ, frameTime);
            }
        }
    }

    private void payForOrgan(Player player, OrganType type) {
        int[] cost = type.getCost();
        for (int i = 0; i < PROTEIN_COUNT; ++i) {
            Protein protein = Protein.values()[i];
            player.payProtein(protein, cost[i]);
        }
    }

    private boolean canBuy(Player player, OrganType organType) {
        return player.storage.get(Protein.A) >= organType.getCost()[0]
            && player.storage.get(Protein.B) >= organType.getCost()[1]
            && player.storage.get(Protein.C) >= organType.getCost()[2]
            && player.storage.get(Protein.D) >= organType.getCost()[3];
    }

    private void reportPlayerError(String message) {
        gameManager.addToGameSummary(
            GameManager.formatErrorMessage(message)
        );
    }

    private boolean checkCanGrowWithPathfinding(
        Player player, Coord target, OrganType organType, Integer fromId, boolean isSporeCommand, Action action
    ) {

        // Cost
        if (!canBuy(player, organType)) {
            reportPlayerError(
                String.format("%s cannot grow a %s, not enough proteins", player.getNicknameToken(), organType.toString())
            );
            return false;
        }

        // Correct organ id
        Organ fromOrgan = getOrganById(fromId);
        if (fromOrgan == null) {
            reportPlayerError(
                String.format("%s cannot grow from id=%d, that id does not exist", player.getNicknameToken(), fromId)
            );
            return false;
        }
        int fromRootId = getRootId(fromOrgan);

        // Organism ownership
        if (fromOrgan.owner != player) {
            reportPlayerError(
                String.format("%s cannot grow from id=%d, not part of owned organisms", player.getNicknameToken(), fromId)
            );
            return false;
        }

        // Create ROOT only with SPORE command
        if (organType == OrganType.ROOT && !isSporeCommand) {
            reportPlayerError(
                String.format("%s cannot grow NUCLEUS", player.getNicknameToken())
            );
            return false;
        }

        // Create ROOT only from SPORER
        if (organType == OrganType.ROOT && fromOrgan.getType() != OrganType.SPORER) {
            reportPlayerError(
                String.format("%s cannot spore NUCLEUS from an organ that is not a SPORER", player.getNicknameToken())
            );
            return false;
        }

        // Target within map
        if (!grid.get(target).isValid()) {
            reportPlayerError(
                String.format("%s cannot grow outside of map at %s", player.getNicknameToken(), target.toString())
            );
            return false;
        }

        // Target is not part of the organism
        if (grid.get(target).hasOrgan() && getRootId(grid.get(target).getOrgan().get()) == fromRootId) {
            reportPlayerError(
                String.format("%s cannot grow onto organ at %s", player.getNicknameToken(), target.toString())
            );
            return false;
        }

        String pathfindingSummaryText = null;

        // Parent location
        if (organType != OrganType.ROOT && fromOrgan.pos.manhattanTo(target) != 1) {
            // Try pathfinding
            List<Coord> restricted = grid.cells.entrySet().stream()
                .filter(e -> e.getValue().hasOrgan() && getRootId(e.getValue().getOrgan().get()) != fromRootId)
                .map(e -> e.getKey())
                .collect(Collectors.toList());

            PathFinderResult pfr = pathfinder.setGrid(grid)
                .restrict(restricted)
                .from(fromOrgan.pos)
                .to(target)
                .findPath();
            List<Coord> wholePath = pfr.path;

            boolean foundPath = false;

            if (wholePath.size() > 1) {
                for (int i = wholePath.size() - 2; i >= 0; i--) {
                    Tile current = grid.get(wholePath.get(i));
                    if (current.hasOrgan() && getRootId(current.getOrgan().get()) == fromRootId) {
                        if (!grid.get(wholePath.get(i + 1)).hasOrgan()) {
                            pathfindingSummaryText = String.format(
                                "%s will grow from %s to %s because %s is too far from id=%d",
                                player.getNicknameToken(),
                                current.getOrgan().get().getPos().toString(), wholePath.get(i + 1).toString(), target.toString(), fromId
                            );

                            action.setCoord(wholePath.get(i + 1));
                            action.setFromId(current.getOrgan().get().getId());
                            target = action.getTarget();
                            fromId = action.getFromId();
                            fromOrgan = getOrganById(fromId);

                            foundPath = true;
                            break;
                        }
                    }
                }
            }

            if (!foundPath) {
                reportPlayerError(
                    String.format(
                        "%s cannot grow in direction of %s from %s", player.getNicknameToken(), target.toString(), fromOrgan.pos.toString()
                    )
                );
                return false;
            }
        }

        // Sporer line-of-sight
        int withinSporeRangeCode = withinSporerRange(fromOrgan.pos, fromOrgan.direction, target);
        if (organType == OrganType.ROOT && withinSporeRangeCode != SPORE_OK) {
            if (withinSporeRangeCode == SPORE_HIT_WALL) {
                reportPlayerError(
                    String.format(
                        "%s cannot spore from %s to %s because an obstacle is in the way",
                        player.getNicknameToken(),
                        fromOrgan.pos.toString(),
                        target.toString()
                    )
                );
            } else if (withinSporeRangeCode == SPORE_MISALIGNED) {
                reportPlayerError(
                    String.format(
                        "%s %s is not to the %s of %s", player.getNicknameToken(), target.toString(), fromOrgan.direction,
                        fromOrgan.pos.toString()
                    )
                );
            } else {
                reportPlayerError(
                    String.format(
                        "%s cannot spore from %s to %s because it is unreachable",
                        player.getNicknameToken(),
                        fromOrgan.pos.toString(),
                        target.toString()
                    )
                );
            }
            return false;
        }

        // Target is free of obstacles
        if (grid.get(target).isObstacle()) {
            reportPlayerError(
                String.format("%s cannot grow onto obstacle at %s", player.getNicknameToken(), target.toString())
            );
            return false;
        }

        // Target is free of organs
        if (grid.get(target).hasOrgan()) {
            reportPlayerError(
                String.format("%s cannot grow onto organ at %s", player.getNicknameToken(), target.toString())
            );
            return false;
        }

        // Target is free of attack
        Player foe = getOpponent(player);
        List<Coord> neighs = grid.getNeighbours(target);
        for (Coord neigh : neighs) {
            Tile t = grid.get(neigh);

            if (t.hasTentacle(foe, target)) {
                reportPlayerError(
                    String.format("%s cannot grow in front of tentacle at %s", player.getNicknameToken(), target.toString())
                );
                return false;
            }
        }

        // Organism already acted
        int rootId = getRootId(fromOrgan);
        if (fromOrgan.owner.hasAlreadyActed(rootId)) {
            reportPlayerError(
                String.format("%s cannot use same organism multiple times", player.getNicknameToken())
            );
            return false;
        }

        if (pathfindingSummaryText != null) {
            gameManager.addToGameSummary(pathfindingSummaryText);
        }

        return true;

    }

    private Player getOpponent(Player player) {
        for (Player p : players) {
            if (p != player) {
                return p;
            }
        }
        return null;
    }

    private int getRootId(Organ organ) {
        Organ current = organ;
        while (current.parent != null) {
            current = current.parent;
        }
        return current.id;
    }

    private static int SPORE_OK = 0;
    private static int SPORE_HIT_WALL = 1;
    private static int SPORE_MISALIGNED = 2;
    private static int SPORE_NOT_FOUND = 3;

    private int withinSporerRange(Coord origin, Direction direction, Coord target) {
        int dx = direction.coord.getX();
        int dy = direction.coord.getY();
        boolean inDirection = dx == Math.signum(target.getX() - origin.getX())
            && dy == Math.signum(target.getY() - origin.getY());

        if (SPORE_OVER_OBSTACLES) {
            return inDirection ? SPORE_OK : SPORE_MISALIGNED;
        } else if (inDirection) {
            Coord cur = origin;
            while (!cur.equals(target)) {
                cur = cur.add(direction.coord);
                if (cur.equals(target)) {
                    return SPORE_OK;
                }
                Tile t = grid.get(cur);
                if (t.isObstacle() || t.hasOrgan()) {
                    return SPORE_HIT_WALL;
                }
            }
        }
        return inDirection ? SPORE_NOT_FOUND : SPORE_MISALIGNED;
    }

    private Organ getOrganById(Integer id) {
        return organById.get(id);
    }

    public boolean isGameOver() {
        boolean gridFull = grid.getCoords().stream().map(grid::get).allMatch(t -> t.hasOrgan() || t.isObstacle());
        boolean noProteinsLeft = !playersHaveEnoughProteinsToProgress();
        boolean aPlayerHasDied = hasAPlayerDied();
        boolean maxTurnsReached = this.turn >= 100;

        if (aPlayerHasDied || gameManager.getActivePlayers().size() < 2) {
            gameManager.addToGameSummary(GameManager.formatSuccessMessage("\nGame over: only 1 player remains."));
        } else if (noProteinsLeft) {
            gameManager.addToGameSummary(GameManager.formatSuccessMessage("\nGame over: not enough proteins to continue evolving."));
        } else if (gridFull) {
            gameManager.addToGameSummary(GameManager.formatSuccessMessage("\nGame over: not enough space to continue evolving."));
        } else if (maxTurnsReached) {
            gameManager.addToGameSummary(GameManager.formatSuccessMessage("\nGame over: max turns reached."));
        }

        return aPlayerHasDied || noProteinsLeft || gridFull || maxTurnsReached || gameManager.getActivePlayers().size() < 2;
    }

    private boolean hasAPlayerDied() {
        return players.stream().anyMatch(player -> player.organs.isEmpty());
    }

    private boolean playerHasEnoughProteinsToProgress(Player player) {
        if (playerIsHarvesting(player)) {
            return true;
        }
        for (OrganType type : OrganType.values()) {
            if (canBuy(player, type)) {
                return true;
            }
        }
        return false;
    }

    private boolean playerIsHarvesting(Player player) {
        return player.organs.stream().anyMatch(o -> {
            if (o.type == OrganType.HARVESTER) {
                Coord target = o.getFacedCoord();
                if (grid.get(target).hasProtein()) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean playersHaveEnoughProteinsToProgress() {
        boolean bothStopped = players.stream().allMatch(player -> !playerHasEnoughProteinsToProgress(player));
        if (bothStopped) {
            return false;
        }

        for (Player player : players) {
            if (!playerHasEnoughProteinsToProgress(player) && playerIsInLastPlace(player)) {
                return false;
            }
        }
        return true;
    }

    private int getScore(Player player) {
        return player.organs.size();
    }

    private boolean playerIsInLastPlace(Player player) {
        Player foe = getOpponent(player);
        return getScore(player) < getScore(foe);
    }

    public static String getExpected(String command) {
        if (command.toLowerCase().startsWith("grow")) {
            return "GROW <id> <x> <y> <type> (<dir>)";
        }
        if (command.toLowerCase().startsWith("spore")) {
            return "SPORE <id> <x> <y>";
        }
        return "GROW | SPORE | WAIT";
    }

    public void onEnd() {

        for (Player p : players) {
            p.setScore(p.isActive() ? p.organs.size() : -1);
        }

        endScreenModule.setTitleRankingsSprite("logo.png");
        if (players.get(0).getScore() == players.get(1).getScore()) {
            // Tie break
            final String[] scoreTexts = new String[2];
            final int[] scores = new int[2];
            players.stream()
                .forEach(player -> {
                    int realScore = player.getScore();
                    player.setScore(player.getProteinTotal());

                    scores[player.getIndex()] = player.getScore();
                    scoreTexts[player.getIndex()] = String.format("%d (and %d proteins)", realScore, player.getProteinTotal());
                    if (SHOW_WALL_POINTS) {
                        scoreTexts[player.getIndex()] += " | %d sidequest points".formatted(wallPoints[player.getIndex()]);
                    }
                });
            endScreenModule.setScores(scores, scoreTexts);
        } else {
            int[] scores = players.stream()
                .mapToInt(
                    player -> player.getScore()
                ).toArray();

            if (SHOW_WALL_POINTS) {
                final String[] scoreTexts = new String[2];
                players.stream()
                    .forEach(player -> {
                        scoreTexts[player.getIndex()] = String
                            .format("%d points | %d sidequest points", player.getScore(), wallPoints[player.getIndex()]);
                    });
                endScreenModule.setScores(scores, scoreTexts);
            } else {
                endScreenModule.setScores(scores);
            }
        }

        gameManager.putMetadata("deathTypes_root_0", deathTypes[0][0]);
        gameManager.putMetadata("deathTypes_basic_0", deathTypes[0][1]);
        gameManager.putMetadata("deathTypes_tentacle_0", deathTypes[0][2]);
        gameManager.putMetadata("deathTypes_harvester_0", deathTypes[0][3]);
        gameManager.putMetadata("deathTypes_sporer_0", deathTypes[0][4]);
        gameManager.putMetadata("growthTypes_root_0", growthTypes[0][0]);
        gameManager.putMetadata("growthTypes_basic_0", growthTypes[0][1]);
        gameManager.putMetadata("growthTypes_tentacle_0", growthTypes[0][2]);
        gameManager.putMetadata("growthTypes_harvester_0", growthTypes[0][3]);
        gameManager.putMetadata("growthTypes_sporer_0", growthTypes[0][4]);

        gameManager.putMetadata("deathTypes_root_1", deathTypes[1][0]);
        gameManager.putMetadata("deathTypes_basic_1", deathTypes[1][1]);
        gameManager.putMetadata("deathTypes_tentacle_1", deathTypes[1][2]);
        gameManager.putMetadata("deathTypes_harvester_1", deathTypes[1][3]);
        gameManager.putMetadata("deathTypes_sporer_1", deathTypes[1][4]);
        gameManager.putMetadata("growthTypes_root_1", growthTypes[1][0]);
        gameManager.putMetadata("growthTypes_basic_1", growthTypes[1][1]);
        gameManager.putMetadata("growthTypes_tentacle_1", growthTypes[1][2]);
        gameManager.putMetadata("growthTypes_harvester_1", growthTypes[1][3]);
        gameManager.putMetadata("growthTypes_sporer_1", growthTypes[1][4]);

        gameManager.putMetadata("harvestedProteins_A_0", harvestedProteinsCounter[0][0]);
        gameManager.putMetadata("harvestedProteins_B_0", harvestedProteinsCounter[0][1]);
        gameManager.putMetadata("harvestedProteins_C_0", harvestedProteinsCounter[0][2]);
        gameManager.putMetadata("harvestedProteins_D_0", harvestedProteinsCounter[0][3]);
        gameManager.putMetadata("absorbedProteins_A_0", absorbedProteinsCounter[0][0]);
        gameManager.putMetadata("absorbedProteins_B_0", absorbedProteinsCounter[0][1]);
        gameManager.putMetadata("absorbedProteins_C_0", absorbedProteinsCounter[0][2]);
        gameManager.putMetadata("absorbedProteins_D_0", absorbedProteinsCounter[0][3]);

        gameManager.putMetadata("harvestedProteins_A_1", harvestedProteinsCounter[1][0]);
        gameManager.putMetadata("harvestedProteins_B_1", harvestedProteinsCounter[1][1]);
        gameManager.putMetadata("harvestedProteins_C_1", harvestedProteinsCounter[1][2]);
        gameManager.putMetadata("harvestedProteins_D_1", harvestedProteinsCounter[1][3]);
        gameManager.putMetadata("absorbedProteins_A_1", absorbedProteinsCounter[1][0]);
        gameManager.putMetadata("absorbedProteins_B_1", absorbedProteinsCounter[1][1]);
        gameManager.putMetadata("absorbedProteins_C_1", absorbedProteinsCounter[1][2]);
        gameManager.putMetadata("absorbedProteins_D_1", absorbedProteinsCounter[1][3]);

        gameManager.putMetadata("cascadeChildrenDeaths_0", cascadeChildrenDeathsCounter[0]);
        gameManager.putMetadata("cascadeChildrenDeaths_1", cascadeChildrenDeathsCounter[1]);
        gameManager.putMetadata("proteinThefts_0", proteinTheftsCounter[0]);
        gameManager.putMetadata("proteinThefts_1", proteinTheftsCounter[1]);
        gameManager.putMetadata("totalSporeDistance_0", totalSporeDistance[0]);
        gameManager.putMetadata("totalSporeDistance_1", totalSporeDistance[1]);
        gameManager.putMetadata("organismsAtGameEnd_0", players.get(0).roots.size());
        gameManager.putMetadata("organismsAtGameEnd_1", players.get(1).roots.size());

        gameManager.putMetadata("mutualKills", mutualKillsCounter / 2);
        gameManager.putMetadata("mutualSpawns", mutualSpawnsCounter);
        gameManager.putMetadata("deniedSpore", deniedSporeCounter);
        gameManager.putMetadata("doubleDeniedSpores", doubleDeniedSporesCounter);
        gameManager.putMetadata("gameLength", this.turn);

        gameManager.putMetadata("wallPoints_0", wallPoints[0]);
        gameManager.putMetadata("wallPoints_1", wallPoints[1]);

        Player winner = null;
        if (players.get(0).getScore() > players.get(1).getScore()) {
            winner = players.get(0);
        } else if (players.get(0).getScore() < players.get(1).getScore()) {
            winner = players.get(1);
        }
        if (winner != null) {
            gameManager.addTooltip(winner, winner.getNicknameToken() + " wins!");
        }
    }

    public List<EventData> getViewerEvents() {
        return animation.getViewerEvents();
    }

};