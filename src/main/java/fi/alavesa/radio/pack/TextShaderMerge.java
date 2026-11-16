package fi.alavesa.radio.pack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Builds the pack's {@code minecraft:core/text} shader (the animated parts of the transceiver GUI).
 *
 * <p>Minecraft runs only ONE copy of a core shader: the one in the topmost pack. SuperbVehicles (HUD) and CCTV
 * (camera HUD, monitor screens) replace the same two files, so the Radio pack must not ship a text shader of its own
 * that drops theirs. Instead it starts from whatever those plugins ship and adds the Radio hooks:
 *
 * <ol>
 *   <li>base = the text shader in SuperbVehicles' HUD pack (which already carries CCTV's hooks when CCTV is
 *       installed), else the one in CCTV's pack, else the vanilla 26.3 files bundled in this jar;</li>
 *   <li>if CCTV's pack exists and the base does not call its hooks, they are added (the same two blocks
 *       SuperbVehicles adds);</li>
 *   <li>the Radio blocks are added, and the other plugins' include files are copied along.</li>
 * </ol>
 *
 * Every block is inserted after a line that exists in the vanilla shader. When such a line is missing (a changed
 * shader in a newer version of one of the plugins) NO text shader is shipped: a core shader that does not compile
 * makes the client drop every server pack, so a still GUI is the safe result. The GUI then shows its still art.
 */
public final class TextShaderMerge {

    /** What the pack gets (path -> bytes; empty when no shader is shipped) and how it came about. */
    public record Result(Map<String, byte[]> files, String description) {
    }

    private static final String CORE = "assets/minecraft/shaders/core/text.";
    private static final String V_INCLUDE_AFTER = "#include <minecraft:projection.glsl>\n";
    private static final String V_CALL_AFTER = "gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n";
    private static final String F_INCLUDE_AFTER = "#include <minecraft:oit.glsl>\n";
    private static final String F_CALL_AFTER = "vec4 color = texColor * vertexColor * ColorModulator;\n";

    private TextShaderMerge() {
    }

    /** One other plugin's pack zip, read once. */
    private record Source(String name, @Nullable String vertex, @Nullable String fragment, Map<String, byte[]> includes) {
    }

    /**
     * @param vanillaVertex   minecraft:core/text.vsh of 26.3, verbatim
     * @param vanillaFragment minecraft:core/text.fsh of 26.3, verbatim
     * @param superbVehicles  SuperbVehicles' HUD pack zip, may not exist
     * @param cctv            CCTV's pack zip, may not exist
     */
    public static Result build(String vanillaVertex, String vanillaFragment, File superbVehicles, File cctv, Consumer<String> warn) {
        Source sbv = read("SuperbVehicles", superbVehicles, warn);
        Source cc = read("CCTV", cctv, warn);
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> parts = new ArrayList<>();
        String vertex = vanillaVertex.replace("\r\n", "\n"), fragment = vanillaFragment.replace("\r\n", "\n");
        if (sbv != null && sbv.vertex != null && sbv.fragment != null) {
            vertex = sbv.vertex;
            fragment = sbv.fragment;
            files.putAll(sbv.includes);
            parts.add("SuperbVehicles");
        } else if (cc != null && cc.vertex != null && cc.fragment != null) {
            vertex = cc.vertex;
            fragment = cc.fragment;
            parts.add("CCTV");
        }
        try {
            if (cc != null && !cc.includes.isEmpty()) {
                files.putAll(cc.includes);
                if (!vertex.contains("cctv_text_vertex") || !fragment.contains("cctv_text_fragment")) {
                    if (vertex.contains("cctv_text_vertex") || fragment.contains("cctv_text_fragment")) {
                        throw new IllegalStateException("the base shader carries only half of CCTV's hooks");
                    }
                    vertex = insertAfter(vertex, V_INCLUDE_AFTER, "// ===== CCTV BEGIN =====\n#include <cctv:cctv_text_vertex.glsl>\n// ===== CCTV END =====\n");
                    vertex = insertAfter(vertex, V_CALL_AFTER, "    // ===== CCTV BEGIN =====\n    gl_Position = ProjMat * cctv_screen_pull(ModelViewMat * vec4(Position + cctv_gui_offset(Position, Color), 1.0));\n    // ===== CCTV END =====\n");
                    fragment = insertAfter(fragment, F_INCLUDE_AFTER, "// ===== CCTV BEGIN =====\n#include <cctv:cctv_text_fragment.glsl>\n// ===== CCTV END =====\n");
                    fragment = insertAfter(fragment, F_CALL_AFTER, "    // ===== CCTV BEGIN =====\n    color = cctv_text_fragment(color, texColor, vertexColor, Sampler0, texCoord0, ColorModulator);\n    // ===== CCTV END =====\n");
                }
                if (!parts.contains("CCTV")) parts.add("CCTV");
            }
            if (vertex.contains("radio_text_vertex") || fragment.contains("radio_text_fragment")) {
                throw new IllegalStateException("the base shader already carries Radio's hooks");
            }
            vertex = insertAfter(vertex, V_INCLUDE_AFTER, "// ===== RADIO BEGIN =====\n#include <radio:radio_text_vertex.glsl>\n// ===== RADIO END =====\n");
            vertex = insertAfter(vertex, V_CALL_AFTER, "    // ===== RADIO BEGIN =====\n    radio_text_vertex();\n    // ===== RADIO END =====\n");
            fragment = insertAfter(fragment, F_INCLUDE_AFTER, "// ===== RADIO BEGIN =====\n#include <radio:radio_text_fragment.glsl>\n// ===== RADIO END =====\n");
            fragment = insertAfter(fragment, F_CALL_AFTER, "    // ===== RADIO BEGIN =====\n    color = radio_text_fragment(color, texColor, vertexColor, ColorModulator);\n    // ===== RADIO END =====\n");
        } catch (IllegalStateException e) {
            warn.accept("text shader not shipped (" + e.getMessage() + "): the radio GUI shows its still art. Update Radio, or set pack.text-shader: false to silence this.");
            return new Result(Map.of(), "none");
        }
        files.put(CORE + "vsh", vertex.getBytes(StandardCharsets.UTF_8));
        files.put(CORE + "fsh", fragment.getBytes(StandardCharsets.UTF_8));
        return new Result(files, parts.isEmpty() ? "vanilla + Radio" : "merged with " + String.join(" + ", parts));
    }

    private static String insertAfter(String s, String anchor, String add) {
        int i = s.indexOf(anchor);
        if (i < 0) throw new IllegalStateException("line not found in the base text shader: " + anchor.trim());
        if (s.indexOf(anchor, i + 1) >= 0) throw new IllegalStateException("line found twice in the base text shader: " + anchor.trim());
        return s.substring(0, i + anchor.length()) + add + s.substring(i + anchor.length());
    }

    /**
     * The 26.3 text shader and the non-vanilla shader include files of a pack zip. A 26.3 overlay directory (CCTV's
     * cctv_26_3/) wins over the pack root, whose files may be written for 26.2.
     */
    private static @Nullable Source read(String name, File zip, Consumer<String> warn) {
        if (!zip.isFile()) return null;
        try (ZipFile z = new ZipFile(zip)) {
            String vertex = null, fragment = null;
            Map<String, byte[]> root = new LinkedHashMap<>(), overlay = new LinkedHashMap<>();
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                if (e.isDirectory()) continue;
                String path = e.getName();
                int assets = path.indexOf("assets/");
                if (assets < 0 || (assets > 0 && path.lastIndexOf('/', assets - 2) >= 0)) continue; // root or one overlay folder only
                boolean inOverlay = assets > 0;
                String rel = path.substring(assets);
                if (rel.equals(CORE + "vsh") || rel.equals(CORE + "fsh")) {
                    String text = text(z, e);
                    if (!text.contains("#include <minecraft:")) continue; // 26.2 syntax (#moj_import): not usable on 26.3
                    boolean isVertex = rel.endsWith("vsh");
                    if (isVertex && (vertex == null || inOverlay)) vertex = text;
                    if (!isVertex && (fragment == null || inOverlay)) fragment = text;
                } else if (rel.matches("assets/[a-z0-9_.-]+/shaders/include/.+") && !rel.startsWith("assets/minecraft/")
                        && !rel.startsWith("assets/radio/")) {
                    try (InputStream in = z.getInputStream(e)) {
                        (inOverlay ? overlay : root).put(rel, in.readAllBytes());
                    }
                }
            }
            root.putAll(overlay);
            return new Source(name, vertex, fragment, root);
        } catch (IOException e) {
            warn.accept("could not read " + zip + " (" + e.getMessage() + "); " + name + "'s text shader hooks are not merged");
            return null;
        }
    }

    private static String text(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
