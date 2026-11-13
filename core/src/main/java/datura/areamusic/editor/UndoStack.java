package datura.areamusic.editor;

import datura.areamusic.edit.EditOp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/** Per-editor history. The caller sends {@code step.undo()} or {@code step.redo()} to the server. */
public final class UndoStack {
    public static final int LIMIT = 100;

    private final Deque<Step> undoSteps = new ArrayDeque<>();
    private final Deque<Step> redoSteps = new ArrayDeque<>();

    public void record(EditOp undo, EditOp redo) {
        undoSteps.push(new Step(undo, redo));
        if (undoSteps.size() > LIMIT) {
            undoSteps.removeLast();
        }
        redoSteps.clear();
    }

    public Optional<Step> undo() {
        Step step = undoSteps.poll();
        if (step != null) {
            redoSteps.push(step);
        }
        return Optional.ofNullable(step);
    }

    public Optional<Step> redo() {
        Step step = redoSteps.poll();
        if (step != null) {
            undoSteps.push(step);
        }
        return Optional.ofNullable(step);
    }

    public boolean canUndo() {
        return !undoSteps.isEmpty();
    }

    public boolean canRedo() {
        return !redoSteps.isEmpty();
    }

    public void clear() {
        undoSteps.clear();
        redoSteps.clear();
    }

    public record Step(EditOp undo, EditOp redo) {
        public Step {
            Objects.requireNonNull(undo, "undo");
            Objects.requireNonNull(redo, "redo");
        }
    }
}
