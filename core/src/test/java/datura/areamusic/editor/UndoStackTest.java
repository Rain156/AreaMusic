package datura.areamusic.editor;

import datura.areamusic.edit.EditOp;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoStackTest {
    @Test
    void undoThenRedoReturnsSameStep() {
        UndoStack stack = new UndoStack();
        stack.record(remove("undo-1"), remove("redo-1"));
        stack.record(remove("undo-2"), remove("redo-2"));

        UndoStack.Step undone = stack.undo().orElseThrow();
        assertEquals(remove("undo-2"), undone.undo());
        assertTrue(stack.canRedo());
        assertEquals(undone, stack.redo().orElseThrow());
        assertFalse(stack.canRedo());
        assertEquals(remove("undo-2"), stack.undo().orElseThrow().undo());
        assertEquals(remove("undo-1"), stack.undo().orElseThrow().undo());
        assertFalse(stack.canUndo());
        assertEquals(remove("redo-1"), stack.redo().orElseThrow().redo());
    }

    @Test
    void recordClearsRedo() {
        UndoStack stack = new UndoStack();
        stack.record(remove("undo-1"), remove("redo-1"));
        stack.undo();

        stack.record(remove("undo-2"), remove("redo-2"));

        assertFalse(stack.canRedo());
        assertEquals(Optional.empty(), stack.redo());
    }

    @Test
    void emptyStackReturnsEmpty() {
        UndoStack stack = new UndoStack();

        assertEquals(Optional.empty(), stack.undo());
        assertEquals(Optional.empty(), stack.redo());
        assertFalse(stack.canUndo());
        assertFalse(stack.canRedo());
    }

    @Test
    void oldestStepIsDroppedBeyondLimit() {
        UndoStack stack = new UndoStack();
        for (int index = 1; index <= UndoStack.LIMIT + 1; index++) {
            stack.record(remove("undo-" + index), remove("redo-" + index));
        }

        UndoStack.Step last = null;
        int undone = 0;
        for (Optional<UndoStack.Step> step = stack.undo(); step.isPresent(); step = stack.undo()) {
            last = step.get();
            undone++;
        }

        assertEquals(UndoStack.LIMIT, undone);
        assertEquals(remove("undo-2"), last.undo());
    }

    @Test
    void clearEmptiesBothDirections() {
        UndoStack stack = new UndoStack();
        stack.record(remove("undo-1"), remove("redo-1"));
        stack.record(remove("undo-2"), remove("redo-2"));
        stack.undo();

        stack.clear();

        assertFalse(stack.canUndo());
        assertFalse(stack.canRedo());
    }

    private static EditOp remove(String areaId) {
        return new EditOp.Remove(areaId);
    }
}
