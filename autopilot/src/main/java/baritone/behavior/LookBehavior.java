/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.behavior.look.ForkableRandom;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import baritone.utils.ExperimentalMovement;

public final class LookBehavior extends Behavior implements ILookBehavior {

    /**
     * The current look target, may be {@code null}.
     */
    private Target target;

    /**
     * The rotation known to the server. Returned by {@link #getEffectiveRotation()} for use in {@link IPlayerContext}.
     */
    private Rotation serverRotation;

    /**
     * The last player rotation. Used to restore the player's angle when using free look.
     *
     * @see Settings#freeLook
     */
    private Rotation prevRotation;

    private final AimProcessor processor;

    private final CombatAim combatAim;

    private float moveYaw;
    private int moveCount;

    private final Deque<Float> smoothYawBuffer;
    private final Deque<Float> smoothPitchBuffer;

    // who set this tick's target, and the last target that really got put on the player and who set that one. only the
    // bridge stall dump reads them (BridgeStallLog), a stuck aim with no name on it is a guess
    private static final StackWalker WALKER = StackWalker.getInstance();
    private String targetBy;
    private Rotation applied;
    private String appliedBy;

    public LookBehavior(Baritone baritone) {
        super(baritone);
        this.processor = new AimProcessor(baritone.getPlayerContext());
        this.combatAim = new CombatAim(baritone.getPlayerContext(), this);
        this.smoothYawBuffer = new ArrayDeque<>();
        this.smoothPitchBuffer = new ArrayDeque<>();
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract) {
        this.target = new Target(rotation, Target.Mode.resolve(ctx, blockInteract), false);
        this.targetBy = caller();
    }

    // the three frames above updateTarget. one frame says Movement.update for every movement, three say which one
    private static String caller() {
        return WALKER.walk(frames -> frames.skip(2).limit(3)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .reduce((a, b) -> a + " < " + b).orElse("?"));
    }

    // for the bridge stall dump: this tick's target so far (set before whoever asks) and the last one that was applied
    public String lookTrace() {
        String now = this.target == null ? "none" : this.target.rotation + " " + this.target.mode + (this.target.combat ? " combat" : "") + " by " + this.targetBy;
        return "target " + now + ", last applied " + (this.applied == null ? "none" : this.applied + " by " + this.appliedBy);
    }

    // melee aim asks go through here, see CombatAim
    public CombatAim combat() {
        return this.combatAim;
    }

    @Override
    public IAimProcessor getAimProcessor() {
        return this.processor;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN) {
            this.processor.tick();
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (event.getState() == EventState.PRE) {
            resolveCombat();
        }

        if (this.target == null) {
            return;
        }

        switch (event.getState()) {
            case PRE: {
                if (this.target.mode == Target.Mode.NONE) {
                    // Just return for PRE, we still want to set target to null on POST
                    return;
                }

                this.prevRotation = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
                final Rotation actual = peek(this.target);
                ctx.player().setYRot(actual.getYaw());
                ctx.player().setXRot(actual.getPitch());
                this.applied = actual;
                this.appliedBy = this.targetBy;
                break;
            }
            case POST: {
                // Reset the player's rotations back to their original values
                if (this.prevRotation != null) {
                    this.smoothYawBuffer.addLast(this.target.rotation.getYaw());
                    while (this.smoothYawBuffer.size() > Baritone.settings().smoothLookTicks.value) {
                        this.smoothYawBuffer.removeFirst();
                    }
                    this.smoothPitchBuffer.addLast(this.target.rotation.getPitch());
                    while (this.smoothPitchBuffer.size() > Baritone.settings().smoothLookTicks.value) {
                        this.smoothPitchBuffer.removeFirst();
                    }
                    if (this.target.mode == Target.Mode.SERVER) {
                        ctx.player().setYRot(this.prevRotation.getYaw());
                        ctx.player().setXRot(this.prevRotation.getPitch());
                    } else if (ctx.player().isFallFlying() ? Baritone.settings().elytraSmoothLook.value : Baritone.settings().smoothLook.value) {
                        ctx.player().setYRot((float) this.smoothYawBuffer.stream().mapToDouble(d -> d).average().orElse(this.prevRotation.getYaw()));
                        if (ctx.player().isFallFlying()) {
                            ctx.player().setXRot((float) this.smoothPitchBuffer.stream().mapToDouble(d -> d).average().orElse(this.prevRotation.getPitch()));
                        }
                    }
                    //ctx.player().xRotO = prevRotation.getPitch();
                    //ctx.player().yRotO = prevRotation.getYaw();
                    this.prevRotation = null;
                }
                // The target is done being used for this game tick, so it can be invalidated
                this.target = null;
                this.targetBy = null;
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) {
            return;
        }

        final ServerboundMovePlayerPacket packet = (ServerboundMovePlayerPacket) event.getPacket();
        if (packet instanceof ServerboundMovePlayerPacket.Rot || packet instanceof ServerboundMovePlayerPacket.PosRot) {
            this.serverRotation = new Rotation(packet.getYRot(0.0f), packet.getXRot(0.0f));
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        this.serverRotation = null;
        this.target = null;
        this.combatAim.reset();
    }

    // the fighters' asks for this tick, turned into the one look. last in line on purpose: a block look or a movement that
    // already set a target this tick keeps it, and a walking path keeps the head (movement goes where the yaw points)
    // unless it's the stall breaker asking (CombatAim.wantOverPath)
    private void resolveCombat() {
        if (this.target != null || (baritone.getPathingBehavior().isPathing() && !this.combatAim.overPath())) {
            this.combatAim.yieldTick();
            return;
        }
        Rotation rotation = this.combatAim.resolve();
        if (rotation != null) {
            // always the visible camera, like a block interact. the smoothing steps from the camera's rotation every tick,
            // a SERVER target would put the camera back at POST and the next step would start from the wrong place
            this.target = new Target(rotation, Target.Mode.CLIENT, true);
            this.targetBy = "combat";
        }
    }

    // combat targets already carry their own wobble, so they skip the per tick reroll (and the pitch nudge, a mob's pitch
    // matching ours exactly is not "I don't care about pitch"). mouse pixels and the clamp still apply
    private Rotation peek(Target target) {
        return target.combat ? this.processor.peekPlain(target.rotation) : this.processor.peekRotation(target.rotation);
    }

    public void pig() {
        if (this.target != null) {
            final Rotation actual = peek(this.target);
            ctx.player().setYRot(actual.getYaw());
        }
    }

    // what the server last heard from us, freeLook or not. null until the first look packet after joining a world
    public Optional<Rotation> serverRotation() {
        return Optional.ofNullable(this.serverRotation);
    }

    public Optional<Rotation> getEffectiveRotation() {
        if (Baritone.settings().freeLook.value) {
            return Optional.ofNullable(this.serverRotation);
        }
        // If freeLook isn't on, just defer to the player's actual rotations
        return Optional.empty();
    }

    @Override
    public void onPlayerRotationMove(RotationMoveEvent event) {
        if (this.target != null) {
            final Rotation actual = peek(this.target);
            event.setYaw(actual.getYaw());
            event.setPitch(actual.getPitch());
        }
        if (event.getType() == RotationMoveEvent.Type.MOTION_UPDATE) { // jumpFromGround fires one too, same yaw, not a second move
            this.moveYaw = event.getYaw();
            this.moveCount++;
        }
    }

    // the yaw moveRelative is going to use this tick: the target's if one is set, else our own. EdgeSettle picks its keys off it
    public float pendingMoveYaw() {
        return this.target != null ? peek(this.target).getYaw() : ctx.player().getYRot();
    }

    // the yaw moveRelative really used last time, and how many times it has run. SprintTelemetry replays the tick with them
    public float lastMoveYaw() {
        return moveYaw;
    }

    public int moveCount() {
        return moveCount;
    }

    private static final class AimProcessor extends AbstractAimProcessor {

        public AimProcessor(final IPlayerContext ctx) {
            super(ctx);
        }

        @Override
        protected Rotation getPrevRotation() {
            // Implementation will use LookBehavior.serverRotation
            return ctx.playerRotations();
        }
    }

    private static abstract class AbstractAimProcessor implements ITickableAimProcessor {

        protected final IPlayerContext ctx;
        private final ForkableRandom rand;
        private double randomYawOffset;
        private double randomPitchOffset;

        public AbstractAimProcessor(IPlayerContext ctx) {
            this.ctx = ctx;
            this.rand = new ForkableRandom();
        }

        private AbstractAimProcessor(final AbstractAimProcessor source) {
            this.ctx = source.ctx;
            this.rand = source.rand.fork();
            this.randomYawOffset = source.randomYawOffset;
            this.randomPitchOffset = source.randomPitchOffset;
        }

        @Override
        public final Rotation peekRotation(final Rotation rotation) {
            final Rotation prev = this.getPrevRotation();

            float desiredYaw = rotation.getYaw();
            float desiredPitch = rotation.getPitch();

            // In other words, the target doesn't care about the pitch, so it used playerRotations().getPitch()
            // and it's safe to adjust it to a normal level
            if (desiredPitch == prev.getPitch()) {
                desiredPitch = nudgeToLevel(desiredPitch);
            }

            desiredYaw += this.randomYawOffset;
            desiredPitch += this.randomPitchOffset;

            return new Rotation(
                    this.calculateMouseMove(prev.getYaw(), desiredYaw),
                    this.calculateMouseMove(prev.getPitch(), desiredPitch)
            ).clamp();
        }

        // peekRotation without the random offsets and the pitch nudge, for targets that bring their own (CombatAim)
        final Rotation peekPlain(final Rotation rotation) {
            final Rotation prev = this.getPrevRotation();
            return new Rotation(
                    this.calculateMouseMove(prev.getYaw(), rotation.getYaw()),
                    this.calculateMouseMove(prev.getPitch(), rotation.getPitch())
            ).clamp();
        }

        @Override
        public final void tick() {
            // randomLooking
            this.randomYawOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;
            this.randomPitchOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;

            // randomLooking113
            double random = this.rand.nextDouble() - 0.5;
            if (Math.abs(random) < 0.1) {
                random *= 4;
            }
            this.randomYawOffset += random * Baritone.settings().randomLooking113.value;
        }

        @Override
        public final void advance(int ticks) {
            for (int i = 0; i < ticks; i++) {
                this.tick();
            }
        }

        @Override
        public Rotation nextRotation(final Rotation rotation) {
            final Rotation actual = this.peekRotation(rotation);
            this.tick();
            return actual;
        }

        @Override
        public final ITickableAimProcessor fork() {
            return new AbstractAimProcessor(this) {

                private Rotation prev = AbstractAimProcessor.this.getPrevRotation();

                @Override
                public Rotation nextRotation(final Rotation rotation) {
                    return (this.prev = super.nextRotation(rotation));
                }

                @Override
                protected Rotation getPrevRotation() {
                    return this.prev;
                }
            };
        }

        protected abstract Rotation getPrevRotation();

        /**
         * Nudges the player's pitch to a regular level. (Between {@code -20} and {@code 10}, increments are by {@code 1})
         */
        private float nudgeToLevel(float pitch) {
            if (pitch < -20) {
                return pitch + 1;
            } else if (pitch > 10) {
                return pitch - 1;
            }
            return pitch;
        }

        private float calculateMouseMove(float current, float target) {
            final float delta = target - current;
            final double deltaPx = angleToMouse(delta); // yes, even the mouse movements use double
            return current + mouseToAngle(deltaPx);
        }

        private double angleToMouse(float angleDelta) {
            final float minAngleChange = mouseToAngle(1);
            return Math.round(angleDelta / minAngleChange);
        }

        private float mouseToAngle(double mouseDelta) {
            // casting float literals to double gets us the precise values used by mc
            final double f = ctx.minecraft().options.sensitivity().get() * (double) 0.6f + (double) 0.2f;
            return (float) (mouseDelta * f * f * f * 8.0d) * 0.15f; // yes, one double and one float scaling factor
        }
    }

    private static class Target {

        public final Rotation rotation;
        public final Mode mode;
        // came from CombatAim, see peek()
        public final boolean combat;

        public Target(Rotation rotation, Mode mode, boolean combat) {
            this.rotation = rotation;
            this.mode = mode;
            this.combat = combat;
        }

        enum Mode {
            /**
             * Rotation will be set client-side and is visual to the player
             */
            CLIENT,

            /**
             * Rotation will be set server-side and is silent to the player
             */
            SERVER,

            /**
             * Rotation will remain unaffected on both the client and server
             */
            NONE;

            static Mode resolve(IPlayerContext ctx, boolean blockInteract) {
                final Settings settings = Baritone.settings();
                final boolean antiCheat = settings.antiCheatCompatibility.value;
                final boolean blockFreeLook = settings.blockFreeLook.value;

                if (ctx.player().isFallFlying()) {
                    // always need to set angles while flying
                    return settings.elytraFreeLook.value ? SERVER : CLIENT;
                } else if (ExperimentalMovement.allowSwimming() && ctx.player().isSwimming()) {
                    // same idea as fall flying: pitch is what actually steers you up and down while
                    // swimming, so the angles always need to be set. SERVER restores the free look camera
                    // afterwards, so the user's view stays put either way.
                    // unless we're mining: the crosshair has to actually be on the block or objectMouseOver
                    // never sees it and we swim in place clicking at the user's view forever. same rule as
                    // the freeLook branch below
                    if (blockInteract && !blockFreeLook) {
                        return CLIENT;
                    }
                    return settings.freeLook.value ? SERVER : CLIENT;
                } else if (settings.freeLook.value) {
                    // Regardless of if antiCheatCompatibility is enabled, if a blockInteract is requested then the player
                    // rotation needs to be set somehow, otherwise Baritone will halt since objectMouseOver() will just be
                    // whatever the player is mousing over visually. Let's just settle for setting it silently.
                    if (blockInteract) {
                        return blockFreeLook ? SERVER : CLIENT;
                    }
                    return antiCheat ? SERVER : NONE;
                }

                // all freeLook settings are disabled so set the angles
                return CLIENT;
            }
        }
    }
}
