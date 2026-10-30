package dev.nejeh.televeling.level;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FormulaEvaluator {
    private static final Pattern LEVEL_TOKEN = Pattern.compile("\\{level}");

    private FormulaEvaluator() {
    }

    public static double evaluate(String expression, int level) {
        Matcher matcher = LEVEL_TOKEN.matcher(expression);
        Parser parser = new Parser(matcher.replaceAll(Integer.toString(level)));
        double result = parser.parseExpression();
        parser.skipWhitespace();
        if (!parser.isAtEnd() || !Double.isFinite(result)) {
            throw new IllegalArgumentException("Invalid or non-finite experience formula.");
        }
        return result;
    }

    private static final class Parser {
        private final String input;
        private int position;

        private Parser(String input) {
            this.input = input;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipWhitespace();
                if (consume('+')) {
                    value += parseTerm();
                } else if (consume('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseUnary();
            while (true) {
                skipWhitespace();
                if (consume('*')) {
                    value *= parseUnary();
                } else if (consume('/')) {
                    double divisor = parseUnary();
                    if (divisor == 0) {
                        throw new IllegalArgumentException("Division by zero in experience formula.");
                    }
                    value /= divisor;
                } else if (consume('%')) {
                    double divisor = parseUnary();
                    if (divisor == 0) {
                        throw new IllegalArgumentException("Modulo by zero in experience formula.");
                    }
                    value %= divisor;
                } else {
                    return value;
                }
            }
        }

        private double parseUnary() {
            skipWhitespace();
            if (consume('+')) {
                return parseUnary();
            }
            if (consume('-')) {
                return -parseUnary();
            }
            return parsePrimary();
        }

        private double parsePrimary() {
            skipWhitespace();
            if (consume('(')) {
                double value = parseExpression();
                skipWhitespace();
                if (!consume(')')) {
                    throw new IllegalArgumentException("Unclosed parenthesis in experience formula.");
                }
                return value;
            }

            int start = position;
            boolean decimalPoint = false;
            while (!isAtEnd()) {
                char character = input.charAt(position);
                if (Character.isDigit(character)) {
                    position++;
                } else if (character == '.' && !decimalPoint) {
                    decimalPoint = true;
                    position++;
                } else {
                    break;
                }
            }
            if (start == position || input.substring(start, position).equals(".")) {
                throw new IllegalArgumentException("Expected a number in experience formula.");
            }
            return Double.parseDouble(input.substring(start, position));
        }

        private boolean consume(char expected) {
            if (!isAtEnd() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!isAtEnd() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        private boolean isAtEnd() {
            return position >= input.length();
        }
    }
}
