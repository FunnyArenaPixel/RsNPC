package com.smallaswater.npc.entitys;

import cn.lanink.gamecore.utils.EntityUtils;
import cn.lanink.gamecore.utils.NukkitTypeUtils;
import cn.lanink.gamecore.utils.packet.ProtocolVersion;
import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockLiquid;
import cn.nukkit.entity.EntityHuman;
import cn.nukkit.entity.data.EntityMetadata;
import cn.nukkit.entity.data.Skin;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.*;
import com.smallaswater.npc.RsNPC;
import com.smallaswater.npc.data.RsNpcConfig;
import com.smallaswater.npc.route.Node;
import com.smallaswater.npc.route.RouteFinder;
import com.smallaswater.npc.variable.VariableManage;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityRsNPC extends EntityHuman {

    @Getter
    private final RsNpcConfig config;
    private int emoteSecond = 0;
    private int nextRouteIndex = 0;
    @Getter
    private final LinkedList<Node> nodes = new LinkedList<>();
    private Node nowNode;
    @Setter
    private boolean lockRoute = false;

    private Vector3 lastPos;
    private int lastUpdateNodeTick;

    private RouteFinder nowRouteFinder;
    @Setter
    private int pauseMoveTick = 0;

    // ---- v2.5.3: NPC头顶显示按玩家设置裁剪(与 NsGameBase 大厅浮空字共用 lobby.ft.* 键) ----
    /** 玩家名 -> nametag 是否应对其隐藏;仅用于周期重估时的状态翻转检测 */
    private final Map<String, Boolean> nameTagHidden = new ConcurrentHashMap<>();
    /** 反射缓存: cn.nsgamebase.api.GbPlayerDataApi#look(String, String);失败后不再重试 */
    private static Method NSGB_LOOK;
    private static boolean NSGB_LOOKUP_FAILED;

    /**
     * RsNPC实体在创建时必须传入RsNPCConfig参数，保留此方法仅为兼容核心创建实体方法
     */
    @Deprecated
    public EntityRsNPC(FullChunk chunk, CompoundTag nbt) {
        this(chunk, nbt, null);
    }

    public EntityRsNPC(@NonNull FullChunk chunk, @NonNull CompoundTag nbt, RsNpcConfig config) {
        super(chunk, nbt);
        this.config = config;
        if (this.config == null) {
            this.close();
            return;
        }
        this.setNameTagAlwaysVisible(config.isNameTagAlwaysVisible());
        this.setNameTagVisible();
        this.setNameTag(config.getShowName());
        this.setMaxHealth(20);
        this.setHealth(20.0F);
        this.getInventory().setItemInHand(config.getHand());
        this.getInventory().setArmorContents(config.getArmor());

        //以下内容在initEntity()中执行，需要在获取到config后再执行一次
        if (config.isEnableCustomCollisionSize()) {
            this.dataProperties.putFloat(EntityUtils.getEntityField("DATA_BOUNDING_BOX_HEIGHT", DATA_BOUNDING_BOX_HEIGHT), this.getHeight());
            this.dataProperties.putFloat(EntityUtils.getEntityField("DATA_BOUNDING_BOX_WIDTH", DATA_BOUNDING_BOX_WIDTH), this.getWidth());
            this.dataProperties.putInt(EntityUtils.getEntityField("DATA_HEALTH", DATA_HEALTH), (int) this.getHealth());
        }
    }

    @Override
    public float getWidth() {
        if (this.config != null && this.config.isEnableCustomCollisionSize()) {
            return this.config.getCustomCollisionSizeWidth();
        }
        return super.getWidth();
    }

    @Override
    public float getLength() {
        if (this.config != null && this.config.isEnableCustomCollisionSize()) {
            return this.config.getCustomCollisionSizeLength();
        }
        return super.getLength();
    }

    @Override
    public float getHeight() {
        if (this.config != null && this.config.isEnableCustomCollisionSize()) {
            return this.config.getCustomCollisionSizeHeight();
        }
        return super.getHeight();
    }

    @Override
    public int getNetworkId() {
        if (this.config == null) {
            return super.getNetworkId();
        }
        return this.config.getNetworkId();
    }

    @Override
    protected float getBaseOffset() {
        if (this.getNetworkId() == -1) {
            return super.getBaseOffset();
        }
        return 0.0F;
    }

    @Override
    public boolean onUpdate(int currentTick) {
        if (this.config == null) {
            this.close();
            return false;
        }

        // v2.5.3: 每10tick(0.5秒)重估各玩家 nametag 可见性(距离+遮挡),状态翻转才重发包
        if (currentTick % 10 == 0 && !this.getViewers().isEmpty()) {
            this.updateNameTagVisibility();
        }

        //旋转
        if (this.config.getWhirling() != 0) {
            this.yaw += this.config.getWhirling();
        }else {
            //寻路
            if (!this.config.getRoute().isEmpty() && this.pauseMoveTick <= 0) {
                this.processMove(currentTick);
            } else {
                //看向玩家
                if (currentTick%2 == 0 && this.config.isLookAtThePlayer() && !this.getViewers().isEmpty()) {
                    this.seePlayer();
                }

                if (this.pauseMoveTick > 0) {
                    this.pauseMoveTick--;
                }

                //表情
                if (this.config.isEnableEmote() && !this.config.getEmoteIDs().isEmpty()) {
                    if (currentTick % 20 == 0) {
                        this.emoteSecond++;
                    }
                    if (this.emoteSecond >= this.config.getShowEmoteInterval()) {
                        this.emoteSecond = 0;
                        EmotePacket packet = new EmotePacket();
                        packet.runtimeId = this.getId();
                        packet.emoteID = this.config.getEmoteIDs().get(RsNPC.RANDOM.nextInt(this.config.getEmoteIDs().size()));
                        packet.flags = 0x3; // FLAG_SERVER | FLAG_MUTE_ANNOUNCEMENT
                        if (ProtocolInfo.CURRENT_PROTOCOL >= ProtocolVersion.v1_20_0_23) {
                            packet.xuid = "";
                            packet.platformId = "";
                        }
                        Server.broadcastPacket(this.getViewers().values(), packet);
                    }
                }
            }
        }
        
        return super.onUpdate(currentTick);
    }

    private void processMove(int currentTick) {
        if (this.nodes.isEmpty()) {
            Vector3 next = this.config.getRoute().get(this.nextRouteIndex);
            if (this.config.isEnablePathfinding()) {
                if (!this.lockRoute) {
                    this.setLockRoute(true);
                    this.nextRouteIndex++;
                    if (this.nextRouteIndex >= this.config.getRoute().size()) {
                        this.nextRouteIndex = 0;
                    }
                    this.nowRouteFinder = new RouteFinder(this.getLevel(), this, next);
                } else if (this.nowRouteFinder != null && this.nowRouteFinder.isProcessingComplete()) {
                    this.nodes.addAll(this.nowRouteFinder.getNodes());
                    this.setLockRoute(false);
                }
            }else {
                this.nodes.add(new Node(next));
                this.nextRouteIndex++;
                if (this.nextRouteIndex >= this.config.getRoute().size()) {
                    this.nextRouteIndex = 0;
                }
            }
        }

        if (!this.nodes.isEmpty()) {
            if (this.nowNode == null || this.distance(this.nowNode.getVector3()) <= 0.35/*((this.getWidth()) / 2 + 0.05)*/) {
                this.nowNode = this.nodes.poll();
                this.lastUpdateNodeTick = currentTick;
            }
            if (this.nowNode != null) {
                Vector3 vector3 = this.nowNode.getVector3();

                if (currentTick - this.lastUpdateNodeTick > 100) {
                    if (this.distance(lastPos) < 0.1) {
                        this.setPosition(vector3);
                        return;
                    }
                    this.lastUpdateNodeTick = currentTick;
                }

                this.lastPos = this.getLocation();
                double x = vector3.x - this.x;
                double z = vector3.z - this.z;
                double diff = Math.abs(x) + Math.abs(z);
                this.motionY = this.config.getBaseMoveSpeed() * vector3.y - this.y;
                if (this.getLevelBlock() instanceof BlockLiquid) {
                    this.motionX = this.config.getBaseMoveSpeed() * 0.05 * (x / diff);
                    this.motionZ = this.config.getBaseMoveSpeed() * 0.05 * (z / diff);
                } else {
                    this.motionX = this.config.getBaseMoveSpeed() * 0.15 * (x / diff);
                    this.motionZ = this.config.getBaseMoveSpeed() * 0.15 * (z / diff);
                }
                this.move(this.motionX, this.motionY, this.motionZ);

                //视角计算
                if (currentTick % 4 == 0) {
                    if (this.nodes.size() >= 2) {
                        vector3 = this.nodes.get(1).getVector3();
                    }
                    double dx = this.x - vector3.x;
                    double dz = this.z - vector3.z;
                    double yaw = Math.asin(dx / Math.sqrt(dx * dx + dz * dz)) / Math.PI * 180.0D;
                    if (dz > 0.0D) {
                        yaw = -yaw + 180.0D;
                    }
                    this.yaw = yaw;
                    this.headYaw = yaw;
                    this.pitch = 0;
                }
            }
        }
    }

    private void seePlayer() {
        RsNPC.THREAD_POOL_EXECUTOR.execute(() -> {
            LinkedList<Player> npd = new LinkedList<>(this.getViewers().values());
            npd.sort((p1, p2) -> Double.compare(this.distance(p1) - this.distance(p2), 0.0D));
            Player player = npd.poll();
            if (player != null) {
                double dx = this.x - player.x;
                double dy = this.y - player.y;
                double dz = this.z - player.z;
                double yaw = Math.asin(dx / Math.sqrt(dx * dx + dz * dz)) / Math.PI * 180.0D;
                double pitch = Math.round(Math.asin(dy / Math.sqrt(dx * dx + dz * dz + dy * dy)) / Math.PI * 180.0D);
                if (dz > 0.0D) {
                    yaw = -yaw + 180.0D;
                }
                this.yaw = yaw;
                this.headYaw = yaw;
                this.pitch = pitch;
            }
        });
    }

    @Override
    public void addMovement(double x, double y, double z, double yaw, double pitch, double headYaw) {
        if (this.getNetworkId() == -1) {
            this.level.addPlayerMovement(this, x, y, z, yaw, pitch, headYaw);
        } else {
            this.level.addEntityMovement(this, x, y, z, yaw, pitch, headYaw);
        }
    }

    @Override
    public void spawnTo(Player player) {
        if (this.getNetworkId() == -1) {
            super.spawnTo(player);
            this.sendData(player);

            //网易版玩家皮肤重发逻辑仅适用于 Nukkit-MOT（getGameVersion、sendSkin 为 MOT 独有 API）
            if (NukkitTypeUtils.getNukkitType() == NukkitTypeUtils.NukkitType.MOT
                    && ProtocolInfo.CURRENT_PROTOCOL >= ProtocolInfo.v1_21_100) {
                if (player.getGameVersion().isNetEase()) {
                    this.getServer().getScheduler().scheduleDelayedTask(RsNPC.getInstance(), () -> {
                        this.sendSkin(null);
                    }, 20, true);
                }
            }

            return;
        }

        if (!this.hasSpawned.containsKey(player.getLoaderId()) && this.chunk != null && player.usedChunks.containsKey(Level.chunkHash(this.chunk.getX(), this.chunk.getZ()))) {
            this.hasSpawned.put(player.getLoaderId(), player);
            player.dataPacket(this.createAddEntityPacket());
            this.sendData(player);
        }
        if (this.riding != null) {
            this.riding.spawnTo(player);
            SetEntityLinkPacket pkk = new SetEntityLinkPacket();
            pkk.vehicleUniqueId = this.riding.getId();
            pkk.riderUniqueId = this.getId();
            pkk.type = 1;
            pkk.immediate = 1;
            player.dataPacket(pkk);
        }
    }

    @Override
    public void setSkin(Skin skin) {
        Skin oldSkin = this.getSkin();
        super.setSkin(skin);
        this.sendSkin(oldSkin);
    }

    protected void sendSkin(Skin oldSkin) {
        PlayerSkinPacket packet = new PlayerSkinPacket();
        packet.skin = this.getSkin();
        packet.newSkinName = this.getSkin().getSkinId();
        packet.oldSkinName = oldSkin != null ? oldSkin.getSkinId() : "old";
        packet.uuid = this.getUniqueId();
        HashSet<Player> players = new HashSet<>(this.getViewers().values());
        if (!players.isEmpty()) {
            Server.broadcastPacket(players, packet);
        }
    }

    @Override
    public void sendData(Player player, EntityMetadata data) {
        SetEntityDataPacket pk = new SetEntityDataPacket();
        pk.eid = this.getId();
        // 每玩家发快照,绝不回写共享 dataProperties:
        // hidden 玩家会写空 nametag,checkEntity 每 60tick setNameTag 又写回,
        // 共享值在 空/真名 间振荡 → 新进入玩家 spawn 时交替看到无名 NPC
        pk.metadata = (data == null ? this.dataProperties : data).clone();
        pk.metadata.putString(
                EntityUtils.getEntityField("DATA_NAMETAG", DATA_NAMETAG),
                this.isNameTagHiddenFor(player)
                        ? ""
                        : VariableManage.stringReplace(player, this.getNameTag(), this.getConfig())
        );
        player.dataPacket(pk);
    }

    @Override
    public void sendData(Player[] players, EntityMetadata data) {
        EntityMetadata base = (data == null ? this.dataProperties : data).clone();
        for (Player player : players) {
            SetEntityDataPacket pk = new SetEntityDataPacket();
            pk.eid = this.getId();
            pk.metadata = base.clone();
            pk.metadata.putString(
                    EntityUtils.getEntityField("DATA_NAMETAG", DATA_NAMETAG),
                    this.isNameTagHiddenFor(player)
                            ? ""
                            : VariableManage.stringReplace(player, this.getNameTag(), this.getConfig())
            );
            player.dataPacket(pk);
        }
    }

    // ================================================================
    //  v2.5.3 nametag 可见性裁剪:与 NsGameBase 大厅浮空字同款规则
    //  (超距隐藏+视线被实心非透明方块遮挡隐藏,玩家可在主菜单"我的设置-性能"调整;
    //  NsGameBase 未安装或 API 不可用时按默认 24 格/启用遮挡判定)
    // ================================================================

    /** 周期重估:可见性状态翻转的玩家才重发 nametag 数据包,同时清理已离开视距的缓存 */
    private void updateNameTagVisibility() {
        for (Player player : new ArrayList<>(this.getViewers().values())) {
            boolean hidden = this.computeNameTagHidden(player);
            Boolean old = this.nameTagHidden.put(player.getName(), hidden);
            if (old == null || old != hidden) {
                this.sendData(player);
            }
        }
        this.nameTagHidden.keySet().removeIf(name -> {
            for (Player p : this.getViewers().values()) {
                if (p.getName().equals(name)) {
                    return false;
                }
            }
            return true;
        });
    }

    /** 该玩家的 nametag 是否应隐藏(实时计算;sendData 发包前都会经过这里) */
    private boolean isNameTagHiddenFor(Player player) {
        return this.computeNameTagHidden(player);
    }

    /** 不同世界=隐藏;距离超限(可关)=隐藏;遮挡检测默认关闭(lobby.ft.occl 1=启用) */
    private boolean computeNameTagHidden(Player player) {
        if (player.getLevel() != this.getLevel()) {
            return true;
        }
        String name = player.getName();
        if (lookupNsgb(name, "lobby.ft.dist.off") != 1) {
            int cfg = lookupNsgb(name, "lobby.ft.dist");
            double dist = cfg > 0 ? cfg : DEFAULT_VISIBILITY_DISTANCE;
            if (player.distanceSquared(this) > dist * dist) {
                return true;
            }
        }
        if (lookupNsgb(name, "lobby.ft.occl") == 1) {
            return !this.hasLineOfSight(player);
        }
        return false;
    }

    private static final double DEFAULT_VISIBILITY_DISTANCE = 24.0;
    /** 视线采样步长(格),与 NsGameBase 浮空字/起床战争实现一致 */
    private static final double OCCLUSION_STEP = 0.5;

    /**
     * 视线检查:玩家眼睛 -> NPC 头顶,0.5格步进采样;
     * 实心且不透明的方块视为遮挡(玻璃等透明方块不算),端点本身不参与判定。
     */
    private boolean hasLineOfSight(Player player) {
        Vector3 from = new Vector3(player.x, player.y + player.getEyeHeight(), player.z);
        Vector3 to = new Vector3(this.x, this.y + this.getEyeHeight(), this.z);
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < OCCLUSION_STEP) {
            return true;
        }
        double steps = Math.ceil(distance / OCCLUSION_STEP);
        double stepX = dx / steps;
        double stepY = dy / steps;
        double stepZ = dz / steps;
        for (int i = 1; i < steps; i++) {
            int id = this.getLevel().getBlockIdAt(
                    (int) Math.floor(from.x + stepX * i),
                    (int) Math.floor(from.y + stepY * i),
                    (int) Math.floor(from.z + stepZ * i));
            if (id != Block.AIR && Block.isBlockSolidById(id) && !Block.isBlockTransparentById(id)) {
                return false;
            }
        }
        return true;
    }

    /** 反射读取 NsGameBase 玩家变量(lobby.ft.*);未安装/异常返回 0(=默认全启用) */
    private static int lookupNsgb(String name, String key) {
        if (NSGB_LOOKUP_FAILED) {
            return 0;
        }
        try {
            if (NSGB_LOOK == null) {
                Class<?> cls = Class.forName("cn.nsgamebase.api.GbPlayerDataApi");
                NSGB_LOOK = cls.getMethod("look", String.class, String.class);
            }
            Object r = NSGB_LOOK.invoke(null, name, key);
            return r instanceof Number ? ((Number) r).intValue() : 0;
        } catch (Throwable t) {
            NSGB_LOOKUP_FAILED = true;
            return 0;
        }
    }

    //为了兼容PM1E，我们不使用setCanBeSavedWithChunk()方法
    //canBeSavedWithChunk 为 Nukkit-MOT/PM1E 的方法名，canSaveToStorage 为 NukkitX 的方法名
    //不加 @Override，以便同一份源码在两种核心下都能编译
    public boolean canBeSavedWithChunk() {
        return false;
    }

    public boolean canSaveToStorage() {
        return false;
    }
}