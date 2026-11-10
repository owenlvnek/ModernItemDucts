package org.vnek.modernitemducts.attachment;

/** The three kinds of duct attachment. */
public enum AttachmentKind {
    /** Pulls from the inventory it faces and pushes into the network. */
    SERVO("servo"),
    /** Pulls matching items from elsewhere on the network into the inventory it faces. */
    RETRIEVER("retriever"),
    /** Restricts what may be delivered into (and retrieved out of) the inventory it faces. */
    FILTER("filter");

    public final String name;

    AttachmentKind(String name) {
        this.name = name;
    }

    /** Servos and retrievers tick, have a backlog and obey redstone. */
    public boolean isActive() {
        return this != FILTER;
    }

    public static AttachmentKind byId(int id) {
        AttachmentKind[] v = values();
        return v[Math.floorMod(id, v.length)];
    }
}
