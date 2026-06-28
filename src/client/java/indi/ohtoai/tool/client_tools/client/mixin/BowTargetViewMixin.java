package indi.ohtoai.tool.client_tools.client.mixin;

import indi.ohtoai.tool.client_tools.client.bow.BowTargetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Overrides {@link Entity#getViewVector(float)} for the local player
 * when bow auto-aim targeting is active.
 *
 * <p>This is the single choke-point through which both the camera
 * ({@code Camera.setup()}) and arrow spawning read the player's look
 * direction.  By overwriting it we guarantee the correct aim is used
 * regardless of what entity fields ({@code yRot}, {@code yHeadRot},
 * etc.) have been modified by vanilla mouse / tick logic.
 */
@Mixin(Entity.class)
public class BowTargetViewMixin {

    /**
     * @author client-tools
     * @reason Override view direction for /cbow target auto-aim.
     *         Falls through to vanilla when targeting is inactive.
     */
    @Overwrite
    public Vec3 getViewVector(float partialTick) {
        if ((Object) this == Minecraft.getInstance().player
            && BowTargetManager.getInstance().isActive()) {

            float yaw = BowTargetManager.getInstance().getDesiredYaw();
            float pitch = BowTargetManager.getInstance().getDesiredPitch();

            // Reproduce vanilla calculateViewVector(pitch, yaw):
            float pitchRad = pitch * ((float) Math.PI / 180F);
            float yawRad = -yaw * ((float) Math.PI / 180F);
            float cosYaw = (float) Math.cos(yawRad);
            float sinYaw = (float) Math.sin(yawRad);
            float cosPitch = (float) Math.cos(pitchRad);
            float sinPitch = (float) Math.sin(pitchRad);
            return new Vec3(
                sinYaw * cosPitch,
                -sinPitch,
                cosYaw * cosPitch
            );
        }

        // Vanilla fallback
        return calculateViewVector(
            getViewXRot(partialTick),
            getViewYRot(partialTick)
        );
    }

    // Shadow the protected helper so we can call it in the fallback.
    @org.spongepowered.asm.mixin.Shadow
    protected Vec3 calculateViewVector(float xRot, float yRot) {
        throw new AbstractMethodError("Mixin shadow");
    }

    @org.spongepowered.asm.mixin.Shadow
    public float getViewXRot(float partialTick) {
        throw new AbstractMethodError("Mixin shadow");
    }

    @org.spongepowered.asm.mixin.Shadow
    public float getViewYRot(float partialTick) {
        throw new AbstractMethodError("Mixin shadow");
    }
}
