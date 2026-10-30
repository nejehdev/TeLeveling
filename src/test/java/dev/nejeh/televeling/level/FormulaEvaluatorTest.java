package dev.nejeh.televeling.level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FormulaEvaluatorTest {
    @Test
    void evaluatesLevelExpressionWithArithmeticPrecedence() {
        assertEquals(400, FormulaEvaluator.evaluate("100 * ({level} + 1)", 3));
        assertEquals(14, FormulaEvaluator.evaluate("2 + 3 * 4", 0));
        assertEquals(2.5, FormulaEvaluator.evaluate("(10 / 4)", 0));
    }

    @Test
    void rejectsUnsupportedTokensAndDivisionByZero() {
        assertThrows(IllegalArgumentException.class, () -> FormulaEvaluator.evaluate("system.exit(0)", 1));
        assertThrows(IllegalArgumentException.class, () -> FormulaEvaluator.evaluate("10 / 0", 1));
    }
}
