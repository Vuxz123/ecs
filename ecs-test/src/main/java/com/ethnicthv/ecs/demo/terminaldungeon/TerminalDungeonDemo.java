package com.ethnicthv.ecs.demo.terminaldungeon;

import com.ethnicthv.ecs.ECS;
import com.ethnicthv.ecs.core.api.archetype.IGeneratedQuery;
import com.ethnicthv.ecs.core.archetype.ArchetypeWorld;
import com.ethnicthv.ecs.core.archetype.EntityCommandBuffer;
import com.ethnicthv.ecs.core.system.BaseSystem;
import com.ethnicthv.ecs.core.system.ExecutionMode;
import com.ethnicthv.ecs.core.system.SystemGroup;
import com.ethnicthv.ecs.core.system.SystemManager;
import com.ethnicthv.ecs.core.system.UpdateMode;
import com.ethnicthv.ecs.core.system.annotation.Id;
import com.ethnicthv.ecs.core.system.annotation.Query;
import java.lang.foreign.Arena;
import java.util.Locale;
import java.util.Scanner;

public class TerminalDungeonDemo {
    private static final SystemGroup PLAYER_GROUP = new SystemGroup("TerminalPlayer", 1100, UpdateMode.FIXED);
    private static final SystemGroup ENEMY_GROUP = new SystemGroup("TerminalEnemy", 1200, UpdateMode.FIXED);

    public static void main(String[] args) {
        TerminalGameState state = new TerminalGameState(24, 12);

        try (Arena commandArena = Arena.ofConfined();
             EntityCommandBuffer ecb = new EntityCommandBuffer(commandArena);
             ECS ecs = ECS.builder()
                 .addSystem(new TerminalPlayerSystem(state, ecb), PLAYER_GROUP)
                 .addSystem(new TerminalEnemySystem(state), ENEMY_GROUP)
                 .build();
             Scanner scanner = new Scanner(System.in)) {

            seedWorld(ecs, state);

            ArchetypeWorld world = ecs.getWorld();
            SystemManager systemManager = ecs.getSystemManager();

            while (!state.shouldStop) {
                updateOutcome(world, state);
                render(world, state);
                if (state.gameOver) {
                    break;
                }

                String line = scanner.hasNextLine() ? scanner.nextLine().trim().toLowerCase(Locale.ROOT) : "q";
                char command = line.isEmpty() ? '.' : line.charAt(0);
                if ("wasd.q".indexOf(command) < 0) {
                    state.message = "Use W/A/S/D to move, '.' to wait, or Q to quit.";
                    continue;
                }
                if (command == 'q') {
                    state.shouldStop = true;
                    state.message = "You leave the dungeon.";
                    continue;
                }

                state.beginTurn(command);
                systemManager.updateGroup(PLAYER_GROUP, 1f);
                ecb.playback(world);
                updateOutcome(world, state);
                if (state.gameOver) {
                    continue;
                }

                systemManager.updateGroup(ENEMY_GROUP, 1f);
                updateOutcome(world, state);
            }

            render(world, state);
        }
    }

    private static void seedWorld(ECS ecs, TerminalGameState state) {
        int player = ecs.createEntity();
        ecs.addComponent(player, DungeonPosition.class, (DungeonPositionHandle h) -> {
            h.setX(2);
            h.setY(2);
        });
        ecs.addComponent(player, PlayerState.class, (PlayerStateHandle h) -> {
            h.setHp(6);
            h.setScore(0);
        });

        spawnEnemy(ecs, state.width - 4, 2, 1);
        spawnEnemy(ecs, state.width - 5, state.height - 3, 1);
        spawnEnemy(ecs, state.width / 2, state.height - 3, 2);

        spawnTreasure(ecs, 5, 4, 2);
        spawnTreasure(ecs, state.width - 6, 4, 3);
        spawnTreasure(ecs, state.width / 2, 2, 4);

        state.message = "Collect all treasure and survive the goblins.";
    }

    private static void spawnEnemy(ECS ecs, int x, int y, int damage) {
        int entity = ecs.createEntity();
        ecs.addComponent(entity, DungeonPosition.class, (DungeonPositionHandle h) -> {
            h.setX(x);
            h.setY(y);
        });
        ecs.addComponent(entity, EnemyState.class, (EnemyStateHandle h) -> h.setDamage(damage));
    }

    private static void spawnTreasure(ECS ecs, int x, int y, int value) {
        int entity = ecs.createEntity();
        ecs.addComponent(entity, DungeonPosition.class, (DungeonPositionHandle h) -> {
            h.setX(x);
            h.setY(y);
        });
        ecs.addComponent(entity, TreasureState.class, (TreasureStateHandle h) -> h.setValue(value));
    }

    private static void updateOutcome(ArchetypeWorld world, TerminalGameState state) {
        PlayerSnapshot player = readPlayer(world);
        if (player == null || player.hp() <= 0) {
            state.gameOver = true;
            state.won = false;
            if (state.message == null || state.message.isBlank()) {
                state.message = "The goblins overwhelm you.";
            }
            return;
        }

        int enemies = world.query().with(EnemyState.class).build().count();
        int treasure = world.query().with(TreasureState.class).build().count();
        if (enemies == 0 && treasure == 0) {
            state.gameOver = true;
            state.won = true;
            state.message = "The room is clear. You win.";
        }
    }

    private static PlayerSnapshot readPlayer(ArchetypeWorld world) {
        int xIndex = world.getComponentManager().getDescriptor(DungeonPosition.class).getFieldIndex("x");
        int yIndex = world.getComponentManager().getDescriptor(DungeonPosition.class).getFieldIndex("y");
        int hpIndex = world.getComponentManager().getDescriptor(PlayerState.class).getFieldIndex("hp");
        int scoreIndex = world.getComponentManager().getDescriptor(PlayerState.class).getFieldIndex("score");
        PlayerSnapshot[] result = new PlayerSnapshot[1];

        world.query()
            .with(DungeonPosition.class)
            .with(PlayerState.class)
            .build()
            .forEachEntity((entityId, handles, archetype) -> result[0] = new PlayerSnapshot(
                entityId,
                handles[0].getInt(xIndex),
                handles[0].getInt(yIndex),
                handles[1].getInt(hpIndex),
                handles[1].getInt(scoreIndex)
            ));

        return result[0];
    }

    private static void render(ArchetypeWorld world, TerminalGameState state) {
        PlayerSnapshot player = readPlayer(world);
        int enemyCount = world.query().with(EnemyState.class).build().count();
        int treasureCount = world.query().with(TreasureState.class).build().count();

        char[][] board = new char[state.height][state.width];
        for (int y = 0; y < state.height; y++) {
            for (int x = 0; x < state.width; x++) {
                board[y][x] = '.';
            }
        }

        plotTreasure(world, board);
        plotEnemies(world, board);
        if (player != null) {
            board[player.y()][player.x()] = '@';
        }

        System.out.print("\u001b[H\u001b[2J");
        System.out.flush();
        System.out.println("Terminal Dungeon");
        if (player != null) {
            System.out.printf("HP: %d  Score: %d  Enemies: %d  Treasure: %d  Turn: %d%n",
                player.hp(), player.score(), enemyCount, treasureCount, state.turn);
        }
        System.out.println("Controls: W/A/S/D move, . wait, Q quit");
        System.out.println(state.message);
        System.out.println();

        String border = "#".repeat(state.width + 2);
        System.out.println(border);
        for (int y = 0; y < state.height; y++) {
            System.out.println("#" + new String(board[y]) + "#");
        }
        System.out.println(border);

        if (state.gameOver) {
            System.out.println(state.won ? "Victory." : "Defeat.");
        }
    }

    private static void plotTreasure(ArchetypeWorld world, char[][] board) {
        int xIndex = world.getComponentManager().getDescriptor(DungeonPosition.class).getFieldIndex("x");
        int yIndex = world.getComponentManager().getDescriptor(DungeonPosition.class).getFieldIndex("y");

        world.query()
            .with(DungeonPosition.class)
            .with(TreasureState.class)
            .build()
            .forEachEntity((entityId, handles, archetype) ->
                board[handles[0].getInt(yIndex)][handles[0].getInt(xIndex)] = '$');
    }

    private static void plotEnemies(ArchetypeWorld world, char[][] board) {
        int xIndex = world.getComponentManager().getDescriptor(DungeonPosition.class).getFieldIndex("x");
        int yIndex = world.getComponentManager().getDescriptor(DungeonPosition.class).getFieldIndex("y");

        world.query()
            .with(DungeonPosition.class)
            .with(EnemyState.class)
            .build()
            .forEachEntity((entityId, handles, archetype) ->
                board[handles[0].getInt(yIndex)][handles[0].getInt(xIndex)] = 'g');
    }

    private record PlayerSnapshot(int entityId, int x, int y, int hp, int score) {}
}

final class TerminalGameState {
    final int width;
    final int height;
    char pendingCommand = '.';
    String message = "";
    int turn;
    boolean gameOver;
    boolean won;
    boolean shouldStop;

    TerminalGameState(int width, int height) {
        this.width = width;
        this.height = height;
    }

    void beginTurn(char command) {
        pendingCommand = command;
        turn++;
        message = switch (command) {
            case '.' -> "You wait and listen.";
            default -> "";
        };
    }

    void appendMessage(String extra) {
        if (extra == null || extra.isBlank()) {
            return;
        }
        if (message == null || message.isBlank()) {
            message = extra;
            return;
        }
        message = message + " " + extra;
    }
}

class TerminalPlayerSystem extends BaseSystem {
    private final TerminalGameState state;
    private final EntityCommandBuffer ecb;

    IGeneratedQuery q;

    private int positionXIndex;
    private int positionYIndex;
    private int valueIndex;

    TerminalPlayerSystem(TerminalGameState state, EntityCommandBuffer ecb) {
        this.state = state;
        this.ecb = ecb;
    }

    @Override
    public void onAwake(ArchetypeWorld world) {
        super.onAwake(world);
        positionXIndex = world.getComponentManager().getDescriptor(DungeonPosition.class).getFieldIndex("x");
        positionYIndex = world.getComponentManager().getDescriptor(DungeonPosition.class).getFieldIndex("y");
        valueIndex = world.getComponentManager().getDescriptor(TreasureState.class).getFieldIndex("value");
    }

    @Override
    public void onUpdate(float deltaTime) {
        q.runQuery();
    }

    @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = {DungeonPosition.class, PlayerState.class})
    private void query(
        @Id int entityId,
        @com.ethnicthv.ecs.core.system.annotation.Component(type = DungeonPosition.class) DungeonPositionHandle position,
        @com.ethnicthv.ecs.core.system.annotation.Component(type = PlayerState.class) PlayerStateHandle player
    ) {
        int dx = 0;
        int dy = 0;
        switch (state.pendingCommand) {
            case 'w' -> dy = -1;
            case 's' -> dy = 1;
            case 'a' -> dx = -1;
            case 'd' -> dx = 1;
            case '.' -> {
                return;
            }
            default -> {
                return;
            }
        }

        int targetX = position.getX() + dx;
        int targetY = position.getY() + dy;
        if (targetX < 0 || targetX >= state.width || targetY < 0 || targetY >= state.height) {
            state.appendMessage("You bump into the wall.");
            return;
        }

        Integer enemyId = findEntityAt(EnemyState.class, targetX, targetY);
        if (enemyId != null) {
            ecb.asParallelWriter(world).destroyEntity(enemyId);
            position.setX(targetX);
            position.setY(targetY);
            player.setScore(player.getScore() + 1);
            state.appendMessage("You cut down a goblin.");
            return;
        }

        Integer treasureId = findEntityAt(TreasureState.class, targetX, targetY);
        if (treasureId != null) {
            int value = readTreasureValue(treasureId);
            ecb.asParallelWriter(world).destroyEntity(treasureId);
            position.setX(targetX);
            position.setY(targetY);
            player.setScore(player.getScore() + value);
            state.appendMessage("You pick up " + value + " gold.");
            return;
        }

        position.setX(targetX);
        position.setY(targetY);
        state.appendMessage("You move deeper into the room.");
    }

    private Integer findEntityAt(Class<?> kind, int x, int y) {
        Integer[] result = new Integer[1];
        world.query()
            .with(DungeonPosition.class)
            .with(kind)
            .build()
            .forEachEntity((entityId, handles, archetype) -> {
                if (result[0] != null) {
                    return;
                }
                if (handles[0].getInt(positionXIndex) == x && handles[0].getInt(positionYIndex) == y) {
                    result[0] = entityId;
                }
            });
        return result[0];
    }

    private int readTreasureValue(int entityId) {
        int[] value = {0};
        world.query()
            .with(TreasureState.class)
            .build()
            .forEachEntity((currentId, handles, archetype) -> {
                if (currentId == entityId) {
                    value[0] = handles[0].getInt(valueIndex);
                }
            });
        return value[0];
    }
}

class TerminalEnemySystem extends BaseSystem {
    private final TerminalGameState state;

    IGeneratedQuery q;

    private int positionXIndex;
    private int positionYIndex;
    private int hpIndex;

    TerminalEnemySystem(TerminalGameState state) {
        this.state = state;
    }

    @Override
    public void onAwake(ArchetypeWorld world) {
        super.onAwake(world);
        positionXIndex = world.getComponentManager().getDescriptor(DungeonPosition.class).getFieldIndex("x");
        positionYIndex = world.getComponentManager().getDescriptor(DungeonPosition.class).getFieldIndex("y");
        hpIndex = world.getComponentManager().getDescriptor(PlayerState.class).getFieldIndex("hp");
    }

    @Override
    public void onUpdate(float deltaTime) {
        q.runQuery();
    }

    @Query(fieldInject = "q", mode = ExecutionMode.SEQUENTIAL, with = {DungeonPosition.class, EnemyState.class})
    private void query(
        @Id int entityId,
        @com.ethnicthv.ecs.core.system.annotation.Component(type = DungeonPosition.class) DungeonPositionHandle position,
        @com.ethnicthv.ecs.core.system.annotation.Component(type = EnemyState.class) EnemyStateHandle enemy
    ) {
        PlayerSnapshot player = locatePlayer();
        if (player == null) {
            return;
        }

        int currentX = position.getX();
        int currentY = position.getY();
        int[] step = chooseStep(currentX, currentY, player.x(), player.y());
        int targetX = currentX + step[0];
        int targetY = currentY + step[1];

        if (targetX == player.x() && targetY == player.y()) {
            damagePlayer(enemy.getDamage());
            state.appendMessage("A goblin hits you for " + enemy.getDamage() + ".");
            return;
        }

        if (targetX < 0 || targetX >= state.width || targetY < 0 || targetY >= state.height) {
            return;
        }
        if (isOccupiedByOtherEnemy(entityId, targetX, targetY) || isTreasureAt(targetX, targetY)) {
            return;
        }

        position.setX(targetX);
        position.setY(targetY);
    }

    private PlayerSnapshot locatePlayer() {
        PlayerSnapshot[] result = new PlayerSnapshot[1];
        world.query()
            .with(DungeonPosition.class)
            .with(PlayerState.class)
            .build()
            .forEachEntity((entityId, handles, archetype) -> result[0] = new PlayerSnapshot(
                entityId,
                handles[0].getInt(positionXIndex),
                handles[0].getInt(positionYIndex),
                handles[1].getInt(hpIndex)
            ));
        return result[0];
    }

    private int[] chooseStep(int enemyX, int enemyY, int playerX, int playerY) {
        int dx = Integer.compare(playerX, enemyX);
        int dy = Integer.compare(playerY, enemyY);
        if (Math.abs(playerX - enemyX) >= Math.abs(playerY - enemyY)) {
            return new int[]{dx, dx == 0 ? dy : 0};
        }
        return new int[]{dy == 0 ? dx : 0, dy};
    }

    private boolean isOccupiedByOtherEnemy(int selfId, int x, int y) {
        boolean[] occupied = {false};
        world.query()
            .with(DungeonPosition.class)
            .with(EnemyState.class)
            .build()
            .forEachEntity((entityId, handles, archetype) -> {
                if (entityId == selfId || occupied[0]) {
                    return;
                }
                if (handles[0].getInt(positionXIndex) == x && handles[0].getInt(positionYIndex) == y) {
                    occupied[0] = true;
                }
            });
        return occupied[0];
    }

    private boolean isTreasureAt(int x, int y) {
        boolean[] occupied = {false};
        world.query()
            .with(DungeonPosition.class)
            .with(TreasureState.class)
            .build()
            .forEachEntity((entityId, handles, archetype) -> {
                if (occupied[0]) {
                    return;
                }
                if (handles[0].getInt(positionXIndex) == x && handles[0].getInt(positionYIndex) == y) {
                    occupied[0] = true;
                }
            });
        return occupied[0];
    }

    private void damagePlayer(int amount) {
        world.query()
            .with(PlayerState.class)
            .build()
            .forEachEntity((entityId, handles, archetype) ->
                handles[0].setInt(hpIndex, handles[0].getInt(hpIndex) - amount));
    }

    private record PlayerSnapshot(int entityId, int x, int y, int hp) {}
}
