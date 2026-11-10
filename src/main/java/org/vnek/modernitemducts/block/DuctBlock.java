package org.vnek.modernitemducts.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.vnek.modernitemducts.Registration;
import org.vnek.modernitemducts.attachment.Attachment;
import org.vnek.modernitemducts.item.DuctBlockItem;
import org.vnek.modernitemducts.item.WrenchItem;
import org.vnek.modernitemducts.menu.AttachmentMenu;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An item duct. The block state only carries what the model needs (per-face arm type and the
 * routing weight); all logic lives in {@link DuctBlockEntity}.
 */
public class DuctBlock extends BaseEntityBlock {
    public static final EnumProperty<DuctWeight> WEIGHT = EnumProperty.create("weight", DuctWeight.class);
    public static final EnumProperty<ConnectionType>[] SIDES = new EnumProperty[6];

    static {
        for (Direction d : Direction.values()) {
            SIDES[d.ordinal()] = EnumProperty.create(d.getSerializedName(), ConnectionType.class, c -> c != ConnectionType.INPUT);
        }
    }

    // Geometry in block units (spec §12).
    public static final double CORE_LO = 0.3, CORE_HI = 0.7;
    public static final double ATT_LO = 0.2, ATT_HI = 0.8, ATT_DEPTH = 0.25;
    private static final VoxelShape CORE = Shapes.create(CORE_LO, CORE_LO, CORE_LO, CORE_HI, CORE_HI, CORE_HI);

    private final Map<BlockState, VoxelShape> stateShapes = new ConcurrentHashMap<>();
    public final DuctType type;

    public DuctBlock(DuctType type) {
        super(Properties.of().mapColor(MapColor.METAL).strength(1.0f, 2.0f).sound(SoundType.STONE)
                .noOcclusion().isViewBlocking((s, l, p) -> false).isSuffocating((s, l, p) -> false)
                .pushReaction(PushReaction.BLOCK)
                .lightLevel(s -> type == DuctType.FAST ? 4 : 0));
        this.type = type;
        BlockState def = stateDefinition.any().setValue(WEIGHT, DuctWeight.NORMAL);
        for (EnumProperty<ConnectionType> p : SIDES) def = def.setValue(p, ConnectionType.NONE);
        registerDefaultState(def);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WEIGHT);
        builder.add(SIDES);
    }

    // ------------------------------------------------------------------ geometry

    /** Axis-aligned box hugging face {@code d}, {@code depth} deep, spanning lo..hi across. */
    public static AABB faceBox(Direction d, double lo, double hi, double depth) {
        return switch (d) {
            case DOWN -> new AABB(lo, 0, lo, hi, depth, hi);
            case UP -> new AABB(lo, 1 - depth, lo, hi, 1, hi);
            case NORTH -> new AABB(lo, lo, 0, hi, hi, depth);
            case SOUTH -> new AABB(lo, lo, 1 - depth, hi, hi, 1);
            case WEST -> new AABB(0, lo, lo, depth, hi, hi);
            case EAST -> new AABB(1 - depth, lo, lo, 1, hi, hi);
        };
    }

    public static AABB armBox(Direction d) {
        return faceBox(d, CORE_LO, CORE_HI, CORE_LO);
    }

    public static AABB attachmentBox(Direction d) {
        return faceBox(d, ATT_LO, ATT_HI, ATT_DEPTH);
    }

    private VoxelShape stateShape(BlockState state) {
        return stateShapes.computeIfAbsent(state, s -> {
            VoxelShape shape = CORE;
            for (Direction d : Direction.values()) {
                if (s.getValue(SIDES[d.ordinal()]) != ConnectionType.NONE) shape = Shapes.or(shape, Shapes.create(armBox(d)));
            }
            return shape.optimize();
        });
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        VoxelShape shape = stateShape(state);
        if (level.getBlockEntity(pos) instanceof DuctBlockEntity be) {
            VoxelShape att = be.attachmentShape();
            if (!att.isEmpty()) shape = Shapes.or(shape, att);
        }
        return shape;
    }

    public enum PartType { CORE, ARM, ATTACHMENT }

    public record Part(PartType type, Direction face) {}

    /** Works out which sub-part of the duct a hit (in block-local coordinates) landed on. */
    public static Part hitPart(DuctBlockEntity be, Vec3 local, Direction clickedFace) {
        final double eps = 0.002;
        for (Direction d : Direction.values()) {
            if (be.getAttachment(d) != null && attachmentBox(d).inflate(eps).contains(local)) return new Part(PartType.ATTACHMENT, d);
        }
        BlockState state = be.getBlockState();
        for (Direction d : Direction.values()) {
            if (state.hasProperty(SIDES[d.ordinal()]) && state.getValue(SIDES[d.ordinal()]) != ConnectionType.NONE
                    && armBox(d).inflate(eps).contains(local) && !insideCore(local)) {
                return new Part(PartType.ARM, d);
            }
        }
        return new Part(PartType.CORE, clickedFace);
    }

    private static boolean insideCore(Vec3 v) {
        double lo = CORE_LO + 0.001, hi = CORE_HI - 0.001;
        return v.x > lo && v.x < hi && v.y > lo && v.y < hi && v.z > lo && v.z < hi;
    }

    /** Face an attachment placed by clicking this duct should go on. */
    public static Direction targetFace(DuctBlockEntity be, Vec3 local, Direction clickedFace) {
        return hitPart(be, local, clickedFace).face();
    }

    // ------------------------------------------------------------------ interaction

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof DuctBlockEntity be)) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        Part part = hitPart(be, local, hit.getDirection());

        if (WrenchItem.isWrench(held)) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (player.isShiftKeyDown()) {
                level.destroyBlock(pos, true, player);
            } else if (part.type() == PartType.ATTACHMENT) {
                be.wrenchAttachment(part.face());
            } else {
                be.toggleBlocked(part.face(), player);
            }
            return InteractionResult.CONSUME;
        }

        if (part.type() == PartType.ATTACHMENT) {
            if (!level.isClientSide && player instanceof ServerPlayer sp) {
                Direction face = part.face();
                Attachment att = be.getAttachment(face);
                NetworkHooks.openScreen(sp, new AttachmentMenu.Provider(be, face),
                        buf -> AttachmentMenu.writeOpenData(buf, pos, face, att));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos, Player player) {
        if (target instanceof BlockHitResult bhr && level.getBlockEntity(pos) instanceof DuctBlockEntity be) {
            Part part = hitPart(be, bhr.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()), bhr.getDirection());
            if (part.type() == PartType.ATTACHMENT) return be.getAttachment(part.face()).toItem();
        }
        return DuctBlockItem.withWeight(asItem(), state.getValue(WEIGHT));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos fromPos, boolean moving) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DuctBlockEntity be) be.requestScan();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof DuctBlockEntity be) be.dropContents();
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    // ------------------------------------------------------------------ misc block properties

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DuctBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // All real work happens in the per-level DuctManager; the ticker only guarantees registration.
        return level.isClientSide ? null : createTickerHelper(type, Registration.DUCT_BE.get(), (l, p, s, be) -> be.ensureRegistered());
    }
}
