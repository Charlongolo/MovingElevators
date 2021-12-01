package com.supermartijn642.movingelevators;

import com.supermartijn642.movingelevators.packets.ElevatorMovementPacket;
import com.supermartijn642.movingelevators.packets.PacketOnElevator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Created 4/7/2020 by SuperMartijn642
 */
public class ElevatorGroup {

    private static final int RE_SYNC_INTERVAL = 10;

    public final Level world;
    public final int x, z;
    public final Direction facing;

    private boolean isMoving = false;
    private int targetY;
    private double lastY;
    private double currentY;
    private double syncCurrentY = Integer.MAX_VALUE;
    private int size = 3;
    private int nextSize = this.size;
    private double speed = 0.2;
    private double nextSpeed = this.speed;
    private BlockState[][] platform = new BlockState[this.size][this.size];
    /**
     * The y coordinates of the controllers
     */
    private final ArrayList<Integer> floors = new ArrayList<>();
    private final ArrayList<FloorData> floorData = new ArrayList<>();

    private int syncCounter = 0;

    public ElevatorGroup(Level world, int x, int z, Direction facing){
        this.world = world;
        this.x = x;
        this.z = z;
        this.facing = facing;
    }

    public void update(){
        if(this.isMoving){
            this.lastY = this.currentY;
            if(this.currentY == this.targetY)
                this.stopElevator();
            else if(Math.abs(this.targetY - this.currentY) < speed){
                this.currentY = this.targetY;
                this.moveElevator(this.lastY, this.currentY);
            }else{
                if(this.syncCurrentY != Integer.MAX_VALUE){
                    this.currentY = this.syncCurrentY;
                    this.syncCurrentY = Integer.MAX_VALUE;
                }else
                    this.currentY += Math.signum(this.targetY - this.currentY) * speed;
                this.moveElevator(this.lastY, this.currentY);
            }
            if(this.syncCounter >= RE_SYNC_INTERVAL){
                this.syncMovement();
                this.syncCounter = 0;
            }
            this.syncCounter++;
        }else if(this.nextSize != this.size || this.nextSpeed != this.speed){
            this.size = this.nextSize;
            this.speed = this.nextSpeed;
            this.platform = new BlockState[this.size][this.size];
            this.updateGroup();
        }
    }

    private void moveElevator(double oldY, double newY){
        int x = this.x + this.facing.getStepX() * (int)Math.ceil(size / 2f) - size / 2;
        int z = this.z + this.facing.getStepZ() * (int)Math.ceil(size / 2f) - size / 2;

        AABB box = new AABB(x, Math.min(oldY, newY), z, x + this.size, Math.max(oldY, newY) + 1 + 3 * this.speed, z + this.size);

        List<? extends Entity> entities = this.world.getEntitiesOfClass(Entity.class, box, this::canCollideWith);

        for(Entity entity : entities){
            if((newY < oldY && entity.isNoGravity()) || (entity instanceof Player && entity.getDeltaMovement().y >= 0 && entity.getY() > Math.min(oldY, newY) + 1))
                continue;
            entity.setPos(entity.getX(), newY + 1, entity.getZ());
            entity.setOnGround(true);
            entity.causeFallDamage(entity.fallDistance, 1, DamageSource.FALL);
            entity.fallDistance = 0;
            entity.setDeltaMovement(entity.getDeltaMovement().x, 0, entity.getDeltaMovement().z);
            if(entity instanceof Player){
                FallDamageHandler.resetElevatorTime((Player)entity);
                if(this.world.isClientSide)
                    MovingElevators.CHANNEL.sendToServer(new PacketOnElevator());
            }
        }
    }

    private boolean canCollideWith(Entity entity){
        return !entity.isSpectator() && !entity.noPhysics && entity.getPistonPushReaction() == PushReaction.NORMAL;
    }

    private void stopElevator(){
        this.isMoving = false;

        int startX = this.x + this.facing.getStepX() * (int)Math.ceil(size / 2f) - size / 2;
        int startZ = this.z + this.facing.getStepZ() * (int)Math.ceil(size / 2f) - size / 2;
        for(int x = 0; x < this.size; x++){
            for(int z = 0; z < this.size; z++){
                BlockPos pos = new BlockPos(startX + x, this.targetY, startZ + z);
                if(!this.world.isEmptyBlock(pos))
                    this.world.destroyBlock(pos, true);
                this.world.setBlockAndUpdate(pos, this.platform[x][z]);
            }
        }

        AABB box = new AABB(startX, this.currentY, startZ, startX + this.size, this.currentY + 1, startZ + this.size);

        List<? extends Entity> entities = this.world.getEntitiesOfClass(Entity.class, box, this::canCollideWith);

        for(Entity entity : entities){
            entity.teleportTo(entity.getX(), this.currentY + 1, entity.getZ());
            entity.setOnGround(true);
            entity.fallDistance = 0;
            entity.setDeltaMovement(entity.getDeltaMovement().x, 0, entity.getDeltaMovement().z);
        }

        if(!this.world.isClientSide){
            this.world.updateNeighbourForOutputSignal(this.getPos(this.targetY + 1), MovingElevators.elevator_block);
            this.updateGroup();
            double x = this.x + this.facing.getStepX() * (int)Math.ceil(size / 2f) + 0.5;
            double z = this.z + this.facing.getStepZ() * (int)Math.ceil(size / 2f) + 0.5;
            this.world.playSound(null, x, this.targetY + 2.5, z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.4f, 0.5f);
            this.syncCounter = 0;
        }
    }

    private void startElevator(int currentY, int targetY){
        if(this.world == null || this.isMoving)
            return;

        int startX = this.x + this.facing.getStepX() * (int)Math.ceil(size / 2f) - size / 2;
        int startZ = this.z + this.facing.getStepZ() * (int)Math.ceil(size / 2f) - size / 2;
        for(int x = 0; x < this.size; x++){
            for(int z = 0; z < this.size; z++){
                BlockPos pos = new BlockPos(startX + x, currentY - 1, startZ + z);
                if(this.world.isEmptyBlock(pos) || this.world.getBlockEntity(pos) != null)
                    return;
                BlockState state = this.world.getBlockState(pos);
                if(state.getDestroySpeed(this.world, pos) < 0)
                    return;
                if(!(state.getShape(this.world, pos).max(Direction.Axis.Y) == 1.0 &&
                    state.getShape(this.world, pos).min(Direction.Axis.X) == 0 && state.getShape(this.world, pos).max(Direction.Axis.X) == 1.0 &&
                    state.getShape(this.world, pos).min(Direction.Axis.Z) == 0 && state.getShape(this.world, pos).max(Direction.Axis.Z) == 1.0))
                    return;
                this.platform[x][z] = state;
            }
        }

        for(int x = 0; x < this.size; x++){
            for(int z = 0; z < this.size; z++){
                this.world.setBlockAndUpdate(new BlockPos(startX + x, currentY - 1, startZ + z), Blocks.AIR.defaultBlockState());
            }
        }

        this.isMoving = true;
        this.targetY = targetY - 1;
        this.currentY = currentY - 1;
        this.lastY = this.currentY;
        if(!this.world.isClientSide){
            this.world.updateNeighbourForOutputSignal(this.getPos(currentY), MovingElevators.elevator_block);
            this.updateGroup();
        }
    }

    public void onButtonPress(boolean isUp, boolean isDown, int yLevel){
        if(this.isMoving || !this.floors.contains(yLevel))
            return;

        ElevatorBlockTile tile = this.getTile(yLevel);
        if(tile == null)
            return;

        if(isUp){
            if(tile.hasPlatform()){
                for(int floor = this.floors.indexOf(yLevel) + 1; floor < this.floors.size(); floor++){
                    ElevatorBlockTile tile2 = this.getTile(this.floors.get(floor));
                    if(tile2 != null){
                        if(tile2.hasSpaceForPlatform())
                            this.startElevator(yLevel, this.floors.get(floor));
                        return;
                    }
                }
            }
        }else if(isDown){
            if(tile.hasPlatform()){
                for(int floor = this.floors.indexOf(yLevel) - 1; floor >= 0; floor--){
                    ElevatorBlockTile tile2 = this.getTile(this.floors.get(floor));
                    if(tile2 != null){
                        if(tile2.hasSpaceForPlatform())
                            this.startElevator(yLevel, this.floors.get(floor));
                        return;
                    }
                }
            }
        }else{
            if(tile.hasSpaceForPlatform()){
                this.floors.sort(Comparator.comparingInt(a -> Math.abs(a - yLevel)));
                for(int y : this.floors){
                    if(y != yLevel){
                        ElevatorBlockTile tile2 = this.getTile(y);
                        if(tile2 != null && tile2.hasPlatform()){
                            this.floors.sort(Integer::compare);
                            this.startElevator(y, yLevel);
                            return;
                        }
                    }
                }
                this.floors.sort(Integer::compare);
            }
        }
    }

    public void onDisplayPress(int yLevel, int floorOffset){
        if(this.isMoving || !this.floors.contains(yLevel))
            return;

        int floor = this.floors.indexOf(yLevel);
        if(floorOffset == 0){
            this.onButtonPress(false, false, yLevel);
            return;
        }

        int toFloor = floor + floorOffset;
        if(toFloor < 0 || toFloor >= this.floors.size())
            return;

        ElevatorBlockTile tile = this.getTile(yLevel);
        int toY = this.floors.get(toFloor);
        ElevatorBlockTile toTile = this.getTile(toY);
        if(tile != null && toTile != null && tile.hasPlatform() && toTile.hasSpaceForPlatform())
            this.startElevator(yLevel, toY);
    }

    public void remove(ElevatorBlockTile tile){
        int floor = this.getFloorNumber(tile.getBlockPos().getY());
        this.floors.remove(floor);
        this.floorData.remove(floor);
        if(this.floors.isEmpty()){
            if(this.isMoving){
                BlockPos spawnPos = this.getPos(tile.getBlockPos().getY()).relative(this.facing, this.size / 2 + 1);
                for(BlockState[] arr : this.platform){
                    for(BlockState state : arr){
                        ItemEntity entity = new ItemEntity(this.world, spawnPos.getX() + 0.5, spawnPos.getY() + 0.5, spawnPos.getZ() + 0.5, new ItemStack(state.getBlock()));
                        this.world.addFreshEntity(entity);
                    }
                }
            }
        }else
            this.updateGroup();
    }

    public void add(ElevatorBlockTile tile){
        if(tile == null)
            return;
        int y = tile.getBlockPos().getY();
        if(this.floors.contains(y))
            return;
        FloorData floorData = new FloorData(tile.getFloorName(), tile.getDisplayLabelColor());
        for(int i = 0; i < this.floors.size(); i++){
            if(y < this.floors.get(i)){
                this.floors.add(i, y);
                this.floorData.add(i, floorData);
                break;
            }
        }
        if(!this.floors.contains(y)){
            this.floors.add(y);
            this.floorData.add(floorData);
        }
        this.updateGroup();
    }

    public void updateFloorData(ElevatorBlockTile tile, String name, DyeColor color){
        int floor = this.getFloorNumber(tile.getBlockPos().getY());
        if(floor == -1)
            return;
        FloorData data = this.floorData.get(floor);
        if(!Objects.equals(name, data.name) || color != data.color){
            data.name = name;
            data.color = color;
            this.updateGroup();
        }
    }

    public boolean isMoving(){
        return this.isMoving;
    }

    public double getLastY(){
        return this.lastY;
    }

    public double getCurrentY(){
        return this.currentY;
    }

    public void updateCurrentY(double y){
        if(this.isMoving && (this.currentY < this.lastY ? y < this.currentY : y > this.currentY))
            this.syncCurrentY = y;
    }

    public BlockState[][] getPlatform(){
        return this.platform;
    }

    public int getSize(){
        return this.size;
    }

    public void setSize(int size){
        this.nextSize = size;
    }

    public double getSpeed(){
        return this.speed;
    }

    public void setSpeed(double speed){
        this.nextSpeed = speed;
    }

    public DyeColor getFloorDisplayColor(int floor){
        return this.floorData.get(floor).color;
    }

    public String getFloorDisplayName(int floor){
        return this.floorData.get(floor).name;
    }

    public CompoundTag write(){
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("moving", this.isMoving);
        tag.putInt("size", this.size);
        if(this.isMoving){
            tag.putInt("targetY", this.targetY);
            tag.putDouble("lastY", this.lastY);
            tag.putDouble("currentY", this.currentY);
            for(int x = 0; x < this.size; x++){
                for(int z = 0; z < this.size; z++){
                    tag.putInt("platform" + x + "," + z, Block.getId(this.platform[x][z]));
                }
            }
        }
        tag.putDouble("speed", this.speed);
        tag.putIntArray("floors", this.floors);
        ListTag floorDataTag = new ListTag();
        for(FloorData floorDatum : this.floorData)
            floorDataTag.add(floorDatum.write());
        tag.put("floorData", floorDataTag);
        return tag;
    }

    public void read(CompoundTag tag){
        if(tag.contains("moving"))
            this.isMoving = tag.getBoolean("moving");
        if(tag.contains("targetY"))
            this.targetY = tag.getInt("targetY");
        if(tag.contains("lastY"))
            this.lastY = tag.getDouble("lastY");
        if(tag.contains("currentY"))
            this.currentY = tag.getDouble("currentY");
        if(tag.contains("size")){
            this.size = tag.getInt("size");
            this.nextSize = this.size;
            this.platform = new BlockState[this.size][this.size];
        }
        if(tag.contains("speed")){
            this.speed = tag.getDouble("speed");
            this.nextSpeed = this.speed;
        }
        for(int x = 0; x < this.size; x++){
            for(int z = 0; z < this.size; z++){
                this.platform[x][z] = Block.stateById(tag.getInt("platform" + x + "," + z));
            }
        }
        if(tag.contains("floors")){
            this.floors.clear();
            for(int y : tag.getIntArray("floors"))
                this.floors.add(y);
        }
        if(tag.contains("floorData")){
            this.floorData.clear();
            ListTag floorDataTag = (ListTag)tag.get("floorData");
            for(Tag compound : floorDataTag)
                this.floorData.add(FloorData.read((CompoundTag)compound));
        }
    }

    private BlockPos getPos(int y){
        return new BlockPos(this.x, y, this.z);
    }

    private ElevatorBlockTile getTile(int y){
        if(this.world == null)
            return null;
        BlockEntity tile = this.world.getBlockEntity(this.getPos(y));
        return tile instanceof ElevatorBlockTile ? (ElevatorBlockTile)tile : null;
    }

    public int getFloorCount(){
        return this.floors.size();
    }

    public int getFloorNumber(int y){
        return this.floors.indexOf(y);
    }

    public int getFloorYLevel(int floor){
        return this.floors.get(floor);
    }

    public ElevatorBlockTile getTileForFloor(int floor){
        if(floor < 0 || floor >= this.floors.size())
            return null;
        return this.getTile(this.floors.get(floor));
    }

    private void updateGroup(){
        this.world.getCapability(ElevatorGroupCapability.CAPABILITY).ifPresent(groups -> groups.updateGroup(this));
    }

    private void syncMovement(){
        if(!this.world.isClientSide)
            MovingElevators.CHANNEL.send(PacketDistributor.DIMENSION.with(this.world::dimension), new ElevatorMovementPacket(this.x, this.z, this.facing, this.currentY));
    }

    private static class FloorData {

        public String name;
        public DyeColor color;

        public FloorData(String name, DyeColor color){
            this.name = name;
            this.color = color;
        }

        public CompoundTag write(){
            CompoundTag tag = new CompoundTag();
            if(this.name != null)
                tag.putString("name", this.name);
            tag.putInt("color", this.color.getId());
            return tag;
        }

        public static FloorData read(CompoundTag tag){
            return new FloorData(tag.contains("name") ? tag.getString("name") : null, DyeColor.byId(tag.getInt("color")));
        }
    }
}
