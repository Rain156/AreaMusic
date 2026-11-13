package datura.areamusic.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferBudgetTest {
    @Test
    void startsFullAndRefusesOnceEmpty() {
        TransferBudget budget = new TransferBudget(100, 250);

        assertTrue(budget.tryTake(200));
        assertFalse(budget.tryTake(51));
        assertTrue(budget.tryTake(50));
        assertFalse(budget.tryTake(1));
        assertTrue(budget.tryTake(0));
        assertEquals(0, budget.available());
    }

    @Test
    void refillAddsOneTickWithoutExceedingTheBurst() {
        TransferBudget budget = new TransferBudget(100, 250);
        budget.tryTake(250);

        budget.refill();
        assertEquals(100, budget.available());
        assertTrue(budget.tryTake(100));

        for (int tick = 0; tick < 10; tick++) {
            budget.refill();
        }
        assertEquals(250, budget.available());
    }

    @Test
    void rejectsImpossibleConfigurations() {
        assertThrows(IllegalArgumentException.class, () -> new TransferBudget(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TransferBudget(100, 99));
        assertThrows(IllegalArgumentException.class, () -> new TransferBudget(100, 100).tryTake(-1));
    }
}
