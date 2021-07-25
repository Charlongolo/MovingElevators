package com.supermartijn642.movingelevators;

import com.supermartijn642.movingelevators.base.ElevatorInputTile;
import com.supermartijn642.movingelevators.base.MEBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.DyeColor;
import net.minecraft.item.DyeItem;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

/**
 * Created 4/8/2020 by SuperMartijn642
 */
public class DisplayBlock extends MEBlock {

    public static final int BUTTON_COUNT = 3;
    public static final int BUTTON_COUNT_BIG = 7;
    public static final float BUTTON_HEIGHT = 4 / 32f;

    public DisplayBlock(){
        super("display_block", DisplayBlockTile::new);
    }

    @Override
    protected void onRightClick(BlockState state, World worldIn, BlockPos pos, PlayerEntity player, Hand handIn, BlockRayTraceResult rayTraceResult){
        if(worldIn.isClientSide)
            return;

        TileEntity tile = worldIn.getBlockEntity(pos);
        if(!(tile instanceof DisplayBlockTile))
            return;
        DisplayBlockTile displayTile = (DisplayBlockTile)tile;

        if(displayTile.getFacing() == rayTraceResult.getDirection()){
            int displayCat = displayTile.getDisplayCategory();

            Vector3d hitVec = rayTraceResult.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
            double hitHorizontal = rayTraceResult.getDirection().getAxis() == Direction.Axis.Z ? hitVec.x : hitVec.z;
            double hitY = hitVec.y;

            if(hitHorizontal > 2 / 32d && hitHorizontal < 30 / 32d){
                BlockPos inputTilePos = null;
                int button_count = -1;
                int height = -1;

                if(displayCat == 1){ // single
                    if(hitY > 2 / 32d && hitY < 30 / 32d){
                        inputTilePos = pos.below();
                        button_count = BUTTON_COUNT;
                        height = 1;
                    }
                }else if(displayCat == 2){ // bottom
                    if(hitY > 2 / 32d){
                        inputTilePos = pos.below();
                        button_count = BUTTON_COUNT_BIG;
                        height = 2;
                    }
                }else if(displayCat == 3){ // top
                    if(hitY < 30 / 32d){
                        inputTilePos = pos.below(2);
                        button_count = BUTTON_COUNT_BIG;
                        height = 2;
                        hitY++;
                    }
                }

                if(inputTilePos == null)
                    return;

                tile = worldIn.getBlockEntity(inputTilePos);
                if(tile instanceof ElevatorInputTile && ((ElevatorInputTile)tile).hasGroup()){
                    ElevatorInputTile inputTile = (ElevatorInputTile)tile;

                    ElevatorGroup group = inputTile.getGroup();
                    int index = inputTile.getGroup().getFloorNumber(inputTile.getFloorLevel());
                    int below = index;
                    int above = group.getFloorCount() - index - 1;
                    if(below < above){
                        below = Math.min(below, button_count);
                        above = Math.min(above, button_count * 2 - below);
                    }else{
                        above = Math.min(above, button_count);
                        below = Math.min(below, button_count * 2 - above);
                    }
                    int startIndex = index - below;
                    int total = below + 1 + above;

                    int floorOffset = (int)Math.floor((hitY - (height - total * BUTTON_HEIGHT) / 2d) / BUTTON_HEIGHT) + startIndex - index;

                    if(player == null || player.getItemInHand(handIn).isEmpty() || !(player.getItemInHand(handIn).getItem() instanceof DyeItem))
                        inputTile.getGroup().onDisplayPress(inputTile.getFloorLevel(), floorOffset);
                    else{
                        DyeColor color = ((DyeItem)player.getItemInHand(handIn).getItem()).getDyeColor();
                        int floor = group.getFloorNumber(inputTile.getFloorLevel()) + floorOffset;
                        ElevatorBlockTile elevatorTile = group.getTileForFloor(floor);
                        if(elevatorTile != null)
                            elevatorTile.setDisplayLabelColor(color);
                    }
                }
            }
        }
    }
}
