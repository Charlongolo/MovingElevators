package com.supermartijn642.movingelevators;

import com.supermartijn642.movingelevators.packets.ElevatorMovementPacket;
import com.supermartijn642.movingelevators.packets.PacketOnElevator;
import net.minecraft.block.Block;
import net.minecraft.block.material.EnumPushReaction;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Created 4/7/2020 by SuperMartijn642
 */
public class ElevatorGroup {

    private static final int RE_SYNC_INTERVAL = 10;

    public final World world;
    public final int x, z;
    public final EnumFacing facing;

    private boolean isMoving = false;
    private int targetY;
    private double lastY;
    private double currentY;
    private double syncCurrentY = Integer.MAX_VALUE;
    private int size = 3;
    private int nextSize = this.size;
    private double speed = 0.2;
    private double nextSpeed = this.speed;
    private IBlockState[][] platform = new IBlockState[this.size][this.size];
    /**
     * The y coordinates of the controllers
     */
    private final ArrayList<Integer> floors = new ArrayList<>();
    private final ArrayList<FloorData> floorData = new ArrayList<>();

    private int syncCounter = 0;

    public ElevatorGroup(World world, int x, int z, EnumFacing facing){
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
            this.platform = new IBlockState[this.size][this.size];
            this.updateGroup();
        }
    }

    private void moveElevator(double oldY, double newY){
        int x = this.x + this.facing.getFrontOffsetX() * (int)Math.ceil(size / 2f) - size / 2;
        int z = this.z + this.facing.getFrontOffsetZ() * (int)Math.ceil(size / 2f) - size / 2;

        AxisAlignedBB box = new AxisAlignedBB(x, Math.min(oldY, newY), z, x + this.size, Math.max(oldY, newY) + 1 + 3 * this.speed, z + this.size);

        List<Entity> entities = this.world.getEntitiesWithinAABB(Entity.class, box, this::canCollideWith);

        for(Entity entity : entities){
            if((newY < oldY && entity.hasNoGravity()) || (entity instanceof EntityPlayer && entity.motionY >= 0 && entity.posY > Math.min(oldY, newY) + 1))
                continue;
            entity.setPosition(entity.posX, newY + 1, entity.posZ);
            entity.onGround = true;
            entity.fall(entity.fallDistance, 1);
            entity.fallDistance = 0;
            entity.motionY = 0;
            if(entity instanceof EntityPlayer){
                FallDamageHandler.resetElevatorTime((EntityPlayer)entity);
                if(this.world.isRemote)
                    MovingElevators.channel.sendToServer(new PacketOnElevator());
            }
        }
    }

    private boolean canCollideWith(Entity entity){
        return !(entity instanceof EntityPlayer && ((EntityPlayer)entity).isSpectator()) && !entity.noClip && entity.getPushReaction() == EnumPushReaction.NORMAL;
    }

    private void stopElevator(){
        this.isMoving = false;

        int startX = this.x + this.facing.getFrontOffsetX() * (int)Math.ceil(size / 2f) - size / 2;
        int startZ = this.z + this.facing.getFrontOffsetZ() * (int)Math.ceil(size / 2f) - size / 2;
        for(int x = 0; x < this.size; x++){
            for(int z = 0; z < this.size; z++){
                BlockPos pos = new BlockPos(startX + x, this.targetY, startZ + z);
                if(!this.world.isAirBlock(pos))
                    this.world.destroyBlock(pos, true);
                this.world.setBlockState(pos, this.platform[x][z]);
            }
        }

        AxisAlignedBB box = new AxisAlignedBB(startX, this.currentY, startZ, startX + this.size, this.currentY + 1, startZ + this.size);

        List<Entity> entities = this.world.getEntitiesWithinAABB(Entity.class, box, this::canCollideWith);

        for(Entity entity : entities){
            entity.setPositionAndUpdate(entity.posX, this.currentY + 1, entity.posZ);
            entity.onGround = true;
            entity.fallDistance = 0;
            entity.motionY = 0;
        }

        if(!this.world.isRemote){
            this.world.updateComparatorOutputLevel(this.getPos(this.targetY + 1), MovingElevators.elevator_block);
            this.updateGroup();
            double x = this.x + this.facing.getFrontOffsetX() * (int)Math.ceil(size / 2f) + 0.5;
            double z = this.z + this.facing.getFrontOffsetZ() * (int)Math.ceil(size / 2f) + 0.5;
            this.world.playSound(null, x, this.targetY + 2.5, z, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.4f, 0.5f);
        }
    }

    private void startElevator(int currentY, int targetY){
        if(this.world == null || this.isMoving)
            return;

        int startX = this.x + this.facing.getFrontOffsetX() * (int)Math.ceil(size / 2f) - size / 2;
        int startZ = this.z + this.facing.getFrontOffsetZ() * (int)Math.ceil(size / 2f) - size / 2;
        for(int x = 0; x < this.size; x++){
            for(int z = 0; z < this.size; z++){
                BlockPos pos = new BlockPos(startX + x, currentY - 1, startZ + z);
                if(this.world.isAirBlock(pos) || this.world.getTileEntity(pos) != null)
                    return;
                IBlockState state = this.world.getBlockState(pos);
                if(state.getBlockHardness(this.world, pos) < 0)
                    return;
                AxisAlignedBB collisionBox = state.getCollisionBoundingBox(this.world, pos);
                if(collisionBox == null || !(collisionBox.maxY == 1.0 &&
                    collisionBox.minX == 0 && collisionBox.maxX == 1.0 &&
                    collisionBox.minZ == 0 && collisionBox.maxZ == 1.0))
                    return;
                this.platform[x][z] = state;
            }
        }

        for(int x = 0; x < this.size; x++){
            for(int z = 0; z < this.size; z++){
                this.world.setBlockState(new BlockPos(startX + x, currentY - 1, startZ + z), Blocks.AIR.getDefaultState());
            }
        }

        this.isMoving = true;
        this.targetY = targetY - 1;
        this.currentY = currentY - 1;
        this.lastY = this.currentY;
        if(!this.world.isRemote){
            this.world.updateComparatorOutputLevel(this.getPos(currentY), MovingElevators.elevator_block);
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
        int floor = this.getFloorNumber(tile.getPos().getY());
        this.floors.remove(floor);
        this.floorData.remove(floor);
        if(this.floors.isEmpty()){
            if(this.isMoving){
                BlockPos spawnPos = this.getPos(tile.getPos().getY()).offset(this.facing, this.size / 2 + 1);
                for(IBlockState[] arr : this.platform){
                    for(IBlockState state : arr){
                        EntityItem entity = new EntityItem(this.world, spawnPos.getX() + 0.5, spawnPos.getY() + 0.5, spawnPos.getZ() + 0.5, new ItemStack(state.getBlock()));
                        this.world.spawnEntity(entity);
                    }
                }
            }
        }else
            this.updateGroup();
    }

    public void add(ElevatorBlockTile tile){
        if(tile == null)
            return;
        int y = tile.getPos().getY();
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

    public void updateFloorData(ElevatorBlockTile tile, String name, EnumDyeColor color){
        int floor = this.getFloorNumber(tile.getPos().getY());
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

    public IBlockState[][] getPlatform(){
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

    public EnumDyeColor getFloorDisplayColor(int floor){
        return this.floorData.get(floor).color;
    }

    public String getFloorDisplayName(int floor){
        return this.floorData.get(floor).name;
    }

    public NBTTagCompound write(){
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("moving", this.isMoving);
        tag.setInteger("size", this.size);
        if(this.isMoving){
            tag.setInteger("targetY", this.targetY);
            tag.setDouble("lastY", this.lastY);
            tag.setDouble("currentY", this.currentY);
            for(int x = 0; x < this.size; x++){
                for(int z = 0; z < this.size; z++){
                    tag.setInteger("platform" + x + "," + z, Block.getStateId(this.platform[x][z]));
                }
            }
        }
        tag.setDouble("speed", this.speed);
        int[] arr = new int[this.floors.size()];
        for(int i = 0; i < this.floors.size(); i++)
            arr[i] = this.floors.get(i);
        tag.setIntArray("floors", arr);
        NBTTagList floorDataTag = new NBTTagList();
        for(FloorData floorDatum : this.floorData)
            floorDataTag.appendTag(floorDatum.write());
        tag.setTag("floorData", floorDataTag);
        return tag;
    }

    public void read(NBTTagCompound tag){
        if(tag.hasKey("moving"))
            this.isMoving = tag.getBoolean("moving");
        if(tag.hasKey("targetY"))
            this.targetY = tag.getInteger("targetY");
        if(tag.hasKey("lastY"))
            this.lastY = tag.getDouble("lastY");
        if(tag.hasKey("currentY"))
            this.currentY = tag.getDouble("currentY");
        if(tag.hasKey("size")){
            this.size = tag.getInteger("size");
            this.nextSize = this.size;
            this.platform = new IBlockState[this.size][this.size];
        }
        if(tag.hasKey("speed")){
            this.speed = tag.getDouble("speed");
            this.nextSpeed = this.speed;
        }
        for(int x = 0; x < this.size; x++){
            for(int z = 0; z < this.size; z++){
                this.platform[x][z] = Block.getStateById(tag.getInteger("platform" + x + "," + z));
            }
        }
        if(tag.hasKey("floors")){
            this.floors.clear();
            for(int y : tag.getIntArray("floors"))
                this.floors.add(y);
        }
        if(tag.hasKey("floorData")){
            this.floorData.clear();
            NBTBase base = tag.getTag("floorData");
            if(base instanceof NBTTagList){
                NBTTagList floorDataTag = (NBTTagList)base;
                for(NBTBase compound : floorDataTag)
                    this.floorData.add(FloorData.read((NBTTagCompound)compound));
            }
        }
    }

    private BlockPos getPos(int y){
        return new BlockPos(this.x, y, this.z);
    }

    private ElevatorBlockTile getTile(int y){
        if(this.world == null)
            return null;
        TileEntity tile = this.world.getTileEntity(this.getPos(y));
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
        ElevatorGroupCapability groups = this.world.getCapability(ElevatorGroupCapability.CAPABILITY, null);
        if(groups != null)
            groups.updateGroup(this);
    }

    private void syncMovement(){
        if(!this.world.isRemote)
            MovingElevators.channel.sendToDimension(new ElevatorMovementPacket(this.x, this.z, this.facing, this.currentY), this.world.provider.getDimension());
    }

    private static class FloorData {

        public String name;
        public EnumDyeColor color;

        public FloorData(String name, EnumDyeColor color){
            this.name = name;
            this.color = color;
        }

        public NBTTagCompound write(){
            NBTTagCompound tag = new NBTTagCompound();
            if(this.name != null)
                tag.setString("name", this.name);
            tag.setInteger("color", this.color.getDyeDamage());
            return tag;
        }

        public static FloorData read(NBTTagCompound tag){
            return new FloorData(tag.hasKey("name") ? tag.getString("name") : null, EnumDyeColor.byDyeDamage(tag.getInteger("color")));
        }
    }
}
