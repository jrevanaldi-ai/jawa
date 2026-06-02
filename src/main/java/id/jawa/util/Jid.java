// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util;

import java.util.Objects;

/**
 * WhatsApp JID. Mirrors Baileys' FullJid and whatsmeow's types.JID.
 *
 * <p>Wire forms supported:
 * <ul>
 *   <li>{@code user@server} — plain JID pair
 *   <li>{@code user_agent:device@server} — multi-device JID with agent
 *   <li>{@code user:device@server} — multi-device JID without agent
 * </ul>
 *
 * <p>{@link #domainType} encodes the server kind for AD-JID on-wire form:
 * 0=WhatsApp ({@code s.whatsapp.net}), 1=LID, 128=hosted, 129=hosted.lid.
 */
public record Jid(String user, String server, int device, int agent, int domainType) {

    public static final String SERVER_WHATSAPP = "s.whatsapp.net";
    public static final String SERVER_GROUP = "g.us";
    public static final String SERVER_BROADCAST = "broadcast";
    public static final String SERVER_LID = "lid";
    public static final String SERVER_NEWSLETTER = "newsletter";
    public static final String SERVER_HOSTED = "hosted";
    public static final String SERVER_HOSTED_LID = "hosted.lid";
    public static final String SERVER_INTEROP = "interop";
    public static final String SERVER_MSGR = "msgr";
    public static final String SERVER_CALL = "call";
    public static final String SERVER_BOT = "bot";
    public static final String SERVER_CUS = "c.us";

    public static final int DOMAIN_WHATSAPP = 0;
    public static final int DOMAIN_LID = 1;
    public static final int DOMAIN_HOSTED = 128;
    public static final int DOMAIN_HOSTED_LID = 129;

    public Jid {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(server, "server");
    }

    public static Jid of(String user, String server) {
        return new Jid(user, server, 0, 0, domainTypeFor(server, 0));
    }

    public static Jid of(String user, String server, int device) {
        return new Jid(user, server, device, 0, domainTypeFor(server, 0));
    }

    public boolean hasDevice() { return device != 0; }
    public boolean hasAgent()  { return agent  != 0; }

    /** Parse a JID from its string form. Returns {@code null} if no '@'. */
    public static Jid parse(String s) {
        if (s == null) return null;
        int at = s.indexOf('@');
        if (at < 0) return null;
        String server = s.substring(at + 1);
        String userCombined = s.substring(0, at);

        String userAgent;
        int device = 0;
        int colon = userCombined.indexOf(':');
        if (colon >= 0) {
            userAgent = userCombined.substring(0, colon);
            device = Integer.parseInt(userCombined.substring(colon + 1));
        } else {
            userAgent = userCombined;
        }

        String user;
        int agent = 0;
        int under = userAgent.indexOf('_');
        if (under >= 0) {
            user = userAgent.substring(0, under);
            agent = Integer.parseInt(userAgent.substring(under + 1));
        } else {
            user = userAgent;
        }

        return new Jid(user, server, device, agent, domainTypeFor(server, agent));
    }

    private static int domainTypeFor(String server, int agent) {
        return switch (server) {
            case SERVER_LID         -> DOMAIN_LID;
            case SERVER_HOSTED      -> DOMAIN_HOSTED;
            case SERVER_HOSTED_LID  -> DOMAIN_HOSTED_LID;
            default                 -> agent != 0 ? agent : DOMAIN_WHATSAPP;
        };
    }

    /** Server string for an AD-JID domainType byte. */
    public static String serverForDomain(int domainType, String fallback) {
        return switch (domainType) {
            case DOMAIN_LID         -> SERVER_LID;
            case DOMAIN_HOSTED      -> SERVER_HOSTED;
            case DOMAIN_HOSTED_LID  -> SERVER_HOSTED_LID;
            default                 -> fallback;
        };
    }

    /** Canonical wire string form. */
    public String asString() {
        StringBuilder sb = new StringBuilder(user.length() + server.length() + 8);
        sb.append(user);
        if (agent != 0)  sb.append('_').append(agent);
        if (device != 0) sb.append(':').append(device);
        sb.append('@').append(server);
        return sb.toString();
    }

    @Override
    public String toString() { return asString(); }
}
