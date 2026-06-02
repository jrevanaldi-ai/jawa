// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.binary;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A WhatsApp binary-protocol node ({@code <tag attrs...>content</tag>}).
 *
 * <p>Content is one of:
 * <ul>
 *   <li>{@code null} — no content
 *   <li>{@link String} — text content (encoded as a string token)
 *   <li>{@code byte[]} — raw binary content
 *   <li>{@code List<BinaryNode>} — child nodes
 * </ul>
 */
public record BinaryNode(String tag, Map<String, String> attrs, Object content) {

    public BinaryNode {
        Objects.requireNonNull(tag, "tag");
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
        // content stays as-is; byte[] arrays are accepted and intentionally not deep-copied
        if (content != null
                && !(content instanceof String)
                && !(content instanceof byte[])
                && !(content instanceof List<?>)) {
            throw new IllegalArgumentException(
                "content must be null, String, byte[], or List<BinaryNode>, got: " + content.getClass());
        }
    }

    public static BinaryNode of(String tag) {
        return new BinaryNode(tag, Map.of(), null);
    }

    public static BinaryNode of(String tag, Map<String, String> attrs) {
        return new BinaryNode(tag, attrs, null);
    }

    public static BinaryNode text(String tag, Map<String, String> attrs, String text) {
        return new BinaryNode(tag, attrs, text);
    }

    public static BinaryNode bytes(String tag, Map<String, String> attrs, byte[] data) {
        return new BinaryNode(tag, attrs, data);
    }

    public static BinaryNode children(String tag, Map<String, String> attrs, List<BinaryNode> kids) {
        return new BinaryNode(tag, attrs, kids == null ? null : List.copyOf(kids));
    }

    public String attr(String key) { return attrs.get(key); }
    public String attr(String key, String def) { return attrs.getOrDefault(key, def); }

    @SuppressWarnings("unchecked")
    public List<BinaryNode> childrenList() {
        return content instanceof List<?> l ? (List<BinaryNode>) l : List.of();
    }

    /**
     * Content as text, UTF-8 decoded.
     * <p>Non-tokenizable strings encode on the wire as {@code BINARY_8/20/32} and
     * therefore decode back as {@code byte[]} — this helper unifies both forms.
     */
    public String textContent() {
        return switch (content) {
            case null -> null;
            case String s -> s;
            case byte[] b -> new String(b, java.nio.charset.StandardCharsets.UTF_8);
            default -> throw new IllegalStateException(
                "content is not text/bytes: " + content.getClass());
        };
    }

    /** Get the first child node with the given tag, or {@code null}. */
    public BinaryNode child(String tag) {
        for (BinaryNode k : childrenList()) if (tag.equals(k.tag())) return k;
        return null;
    }

    /** Get all child nodes with the given tag. */
    public List<BinaryNode> children(String tag) {
        List<BinaryNode> out = new java.util.ArrayList<>();
        for (BinaryNode k : childrenList()) if (tag.equals(k.tag())) out.add(k);
        return out;
    }

    /** Content as raw bytes (UTF-8 encodes a String form on the fly). */
    public byte[] bytesContent() {
        return switch (content) {
            case null -> null;
            case byte[] b -> b;
            case String s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            default -> throw new IllegalStateException(
                "content is not text/bytes: " + content.getClass());
        };
    }
}
