package net.milenco.buddy.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.milenco.buddy.mixin.client.EntityRenderersAccessor;
import net.milenco.buddy.mixin.client.LivingScaleInvoker;
import net.milenco.buddy.mixin.client.ModelPartAccessor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The game's own models, for the panel's 3D view (models.json in the exchange folder): every model layer the game knows
 * (chests, beds, signs, shulker boxes, banners, heads, bells, the player), and per kind of mob the model its renderer
 * draws, with the texture it draws it with for a plain one and the scale it gives it. Each part is a list of the cubes'
 * faces, four corners of x, y, z (blocks, the part's pose applied, as the game draws them: y down, 24 pixels above the
 * feet is 0) and u, v (0 to 1 on the texture), with its path ("head", "body/left_arm") and its pivot, so that the page can
 * turn a head later. For the 3D view as the game shows it: block entities, mobs and skins.
 * <p>
 * Command {@code models}: written once per game (the models are the game's, not the world's), on the game's thread
 * because the renderers live there; a few hundred kilobytes.
 */
final class Models {
	private Models() {}

	static void command(Minecraft mc) {
		long t0 = System.currentTimeMillis();
		StringBuilder sb = new StringBuilder(1 << 20);
		sb.append("{\"version\":\"").append(SharedConstants.getCurrentVersion().name()).append("\",\"layers\":{");
		int layers = 0, ents = 0;
		boolean first = true;
		for (ModelLayerLocation loc : ModelLayers.getKnownLocations().toList()) {
			ModelPart root;
			try { root = mc.getEntityModels().bakeLayer(loc); } catch (Throwable e) { continue; }
			if (!first) sb.append(',');
			first = false;
			sb.append('"').append(loc.toString()).append("\":");
			parts(root, sb);
			layers++;
		}
		sb.append("},\"entities\":{");
		first = true;
		var disp = (EntityRenderersAccessor) mc.getEntityRenderDispatcher();
		for (var en : disp.buddy$renderers().entrySet()) {
			if (!(en.getValue() instanceof LivingEntityRenderer<?, ?, ?> lr)) continue;
			String kind = BuiltInRegistries.ENTITY_TYPE.getKey(en.getKey()).getPath();
			if (!first) sb.append(',');
			first = false;
			sb.append('"').append(kind).append("\":");
			living(lr, sb);
			ents++;
		}
		for (var en : disp.buddy$playerRenderers().entrySet()) {
			if (!first) sb.append(',');
			first = false;
			sb.append("\"player_").append(en.getKey().name().toLowerCase()).append("\":");
			living(en.getValue(), sb);
			ents++;
		}
		sb.append("}}");
		String json = sb.toString();
		int nLayers = layers, nEnts = ents;
		new Thread(() -> {
			try {
				Path f = Paths.get(AfkConfig.get().exchangeDir).resolve("models.json"), tmp = f.resolveSibling("models.json.tmp");
				Files.writeString(tmp, json, StandardCharsets.UTF_8);
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				Exchange.emit("models", "action", "written", "layers", nLayers, "entities", nEnts, "bytes", json.length(), "ms", System.currentTimeMillis() - t0);
			} catch (Exception e) {
				Exchange.emit("models", "error", String.valueOf(e.getMessage()));
			}
		}, "buddy-models").start();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void living(LivingEntityRenderer lr, StringBuilder sb) {
		sb.append("{\"tex\":");
		LivingEntityRenderState state = null;
		try {
			state = (LivingEntityRenderState) lr.createRenderState();
			sb.append('"').append(lr.getTextureLocation(state)).append('"');
		} catch (Throwable e) {
			sb.append("null");
		}
		float scale = 1f;
		if (state != null) {
			try {
				PoseStack ps = new PoseStack();
				((LivingScaleInvoker) lr).buddy$scale(state, ps);
				scale = ps.last().pose().getColumn(1, new Vector3f()).length();
			} catch (Throwable e) { scale = 1f; }
		}
		sb.append(",\"scale\":").append(num(scale)).append(",\"parts\":");
		parts(lr.getModel().root(), sb);
		sb.append('}');
	}

	/** The parts of a model, depth first, the hidden ones (a hat layer, a saddle) left out as the game leaves them out. */
	private static void parts(ModelPart root, StringBuilder sb) {
		List<String> out = new ArrayList<>();
		walk(root, new PoseStack(), "", out);
		sb.append('[').append(String.join(",", out)).append(']');
	}

	private static void walk(ModelPart part, PoseStack ps, String path, List<String> out) {
		if (!part.visible) return;
		ps.pushPose();
		part.translateAndRotate(ps);
		var acc = (ModelPartAccessor) (Object) part;
		if (!part.skipDraw && !acc.buddy$cubes().isEmpty()) {
			Matrix4f m = ps.last().pose();
			Vector3f p = new Vector3f();
			StringBuilder v = new StringBuilder();
			for (ModelPart.Cube cube : acc.buddy$cubes())
				for (ModelPart.Polygon poly : cube.polygons)
					for (ModelPart.Vertex vx : poly.vertices()) {
						m.transformPosition(vx.worldX(), vx.worldY(), vx.worldZ(), p);
						if (v.length() > 0) v.append(',');
						v.append(num(p.x)).append(',').append(num(p.y)).append(',').append(num(p.z)).append(',').append(num(vx.u())).append(',').append(num(vx.v()));
					}
			m.transformPosition(0, 0, 0, p);
			out.add("{\"p\":\"" + path + "\",\"o\":[" + num(p.x) + "," + num(p.y) + "," + num(p.z) + "],\"v\":[" + v + "]}");
		}
		for (Map.Entry<String, ModelPart> c : acc.buddy$children().entrySet())
			walk(c.getValue(), ps, path.isEmpty() ? c.getKey() : path + "/" + c.getKey(), out);
		ps.popPose();
	}

	private static String num(float f) {
		// a texture of no size (a boat's water patch) gives NaN, which JSON has not
		if (!Float.isFinite(f) || Math.abs(f) < 1e-5f) return "0";
		String s = String.format(java.util.Locale.ROOT, "%.4f", f);
		int e = s.length();
		while (s.charAt(e - 1) == '0') e--;
		if (s.charAt(e - 1) == '.') e--;
		return s.substring(0, e);
	}
}
