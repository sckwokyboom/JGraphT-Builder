package com.github.sckwoky.typegraph.flow;

import com.github.sckwoky.typegraph.flow.model.FlowNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * SSA-lite scope tracker. Maintains a stack of frames; each frame maps a
 * variable name to its current defining {@link FlowNode}. {@code newDef}
 * always allocates a fresh version, so multiple assignments to the same
 * variable produce distinct {@code LOCAL_DEF} nodes joined by phi-like
 * {@code MERGE_VALUE} nodes after branches and loops.
 * <p>
 * snapshot()/restore() are used by the builder around branches/loops to
 * compute per-branch deltas and reconstruct phi merges.
 */
public class VariableState {

    private final Deque<Map<String, FlowNode>> frames = new ArrayDeque<>();
    private final Map<String, Integer> versionCounter = new HashMap<>();

    public VariableState() {
        frames.push(new LinkedHashMap<>());
    }

    public void pushFrame() { frames.push(new LinkedHashMap<>()); }

    public void popFrame() { frames.pop(); }

    /** Returns the current definition node for {@code name}, walking outward through frames. */
    public FlowNode currentDef(String name) {
        for (var frame : frames) {
            if (frame.containsKey(name)) return frame.get(name);
        }
        return null;
    }

    /** Allocates a new version index for {@code name}. */
    public int nextVersion(String name) {
        int v = versionCounter.getOrDefault(name, 0);
        versionCounter.put(name, v + 1);
        return v;
    }

    /** Registers a new definition for {@code name} in the innermost frame. */
    public void define(String name, FlowNode node) {
        frames.peek().put(name, node);
    }

    /**
     * Updates the current definition of {@code name} in whichever frame currently
     * holds it (or the innermost frame if not present). Used for assignments to
     * variables declared in an outer scope.
     */
    public void update(String name, FlowNode node) {
        for (var frame : frames) {
            if (frame.containsKey(name)) {
                frame.put(name, node);
                return;
            }
        }
        frames.peek().put(name, node);
    }

    /** Snapshot of the *flattened* current state: name → currentDef across all frames. */
    public Map<String, FlowNode> snapshot() {
        var snap = new LinkedHashMap<String, FlowNode>();
        var iter = frames.descendingIterator();
        while (iter.hasNext()) {
            snap.putAll(iter.next());
        }
        return snap;
    }

    /**
     * Restores variable bindings from a snapshot. Existing frames are kept, but the
     * mapping from names to nodes is overridden so that any variable in {@code snap}
     * uses the snapshotted node. Variables not in {@code snap} keep their current value.
     */
    public void restoreFromSnapshot(Map<String, FlowNode> snap) {
        for (var entry : snap.entrySet()) {
            update(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Returns names of variables whose current definition differs between this state
     * and the supplied snapshot. Useful for detecting which variables to phi-merge
     * after a branch.
     */
    public Set<String> diffSince(Map<String, FlowNode> snap) {
        var changed = new LinkedHashSet<String>();
        var current = snapshot();
        for (var name : current.keySet()) {
            if (!snap.containsKey(name) || snap.get(name) != current.get(name)) {
                changed.add(name);
            }
        }
        return changed;
    }
}
