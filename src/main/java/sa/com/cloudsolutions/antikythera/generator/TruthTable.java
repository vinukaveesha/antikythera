package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates and print truth tables for given conditionals
 */
public class TruthTable {
    /**
     * The condition that this truth table is for
     */
    private final Expression condition;
    /**
     * The set of variables involved in the condition
     */
    private final Set<Expression> variables;
    /**
     * The matrix of values for the variables and the result of the condition
     */
    private List<Map<Expression, Object>> table;

    /**
     * Create a new truth table for the given condition represented as a string
     * @param conditionCode the condition as string
     */
    public TruthTable(String conditionCode) {
        this(StaticJavaParser.parseExpression(conditionCode));

    }

    /**
     * Create a new truth table for the given condition.
     * @param condition Expression
     */
    public TruthTable(Expression condition) {
        this.condition = condition;
        this.variables = new HashSet<>();
        this.condition.accept(new VariableCollector(), variables);
        generateTruthTable();
    }

    private static boolean isInequality(BinaryExpr binaryExpr) {
        return binaryExpr.getOperator() == BinaryExpr.Operator.LESS || binaryExpr.getOperator() == BinaryExpr.Operator.GREATER ||
                binaryExpr.getOperator() == BinaryExpr.Operator.LESS_EQUALS || binaryExpr.getOperator() == BinaryExpr.Operator.GREATER_EQUALS;
    }

    /**
     * Main method to test the truth table generation and printing with different conditions.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        String[] conditions = {
                "a > b",
                "a > b && c == d",
                "a != null && b != null",
                "a == null",
                "a == null || b == null",
                "a && b || !c",
                "x || y && !z",
                "a > b && b < c",
                "a > b && b > c"
        };

        for (String condition : conditions) {
            TruthTable generator = new TruthTable(condition);
            generator.printTruthTable();
            generator.printValues(true);
            generator.printValues(false);
            System.out.println("\n");
        }
    }

    /**
     * Generates a truth table for the given condition.
     */
    private void generateTruthTable() {
        List<Expression> variableList = new ArrayList<>(variables);
        int numVariables = variableList.size();
        int numRows = (int) Math.pow(2, numVariables);

        table = new ArrayList<>();

        for (int i = 0; i < numRows; i++) {
            Map<Expression, Object> truthValues = new HashMap<>();
            for (int j = 0; j < numVariables; j++) {
                boolean value = (i & (1 << j)) != 0;
                if (!value && condition.toString().contains("null")) {
                    truthValues.put(variableList.get(j), null);
                } else {
                    truthValues.put(variableList.get(j), value);
                }
            }
            boolean result = evaluateCondition(condition, truthValues);
            truthValues.put(new NameExpr("Result"), result);
            table.add(truthValues);
        }
    }

    /**
     * Prints the truth table for the given condition.
     *
     */
    public void printTruthTable() {
        System.out.println("Truth Table for condition: " + condition);

        if (!table.isEmpty()) {
            Map<Expression, Object> firstRow = table.get(0);
            for (Expression var : firstRow.keySet()) {
                System.out.printf("%-10s", var.toString());
            }
            System.out.println();

            for (Map<Expression, Object> row : table) {
                for (Object value : row.values()) {
                    System.out.printf("%-10s", value);
                }
                System.out.println();
            }
        } else {
            System.out.println("No data to display.");
        }
    }

    /**
     * Prints the values that make the condition true.
     */
    public void printValues(boolean desiredState) {
        String state = desiredState ? "true" : "false";
        System.out.println("\nValues to make the condition " + state + " for: " + condition);

        List<Map<Expression, Object>> values = findValuesForCondition(desiredState);

        values.stream().findFirst().ifPresentOrElse(
                row -> {
                    row.entrySet().forEach(var ->System.out.printf("%-10s", var));
                    System.out.println();
                },
                () -> System.out.println("No combination of values makes the condition " + state + ".")
        );
    }

    /**
     * Find the values that make the condition true or false.
     * Often there will be more than one combination of values.
     * @param desiredState either true or false
     * @return a list of maps containing the values that make the condition true or false
     */
    public List<Map<Expression, Object>> findValuesForCondition(boolean desiredState) {
        List<Map<Expression, Object>> result = new ArrayList<>();

        for (Map<Expression, Object> row : table) {
            if ((boolean) row.get(new NameExpr("Result")) == desiredState) {
                Map<Expression, Object> copy = new HashMap<>();
                for (Map.Entry<Expression, Object> entry : row.entrySet()) {
                    if (!entry.getKey().toString().equals("Result")) {
                        copy.put(entry.getKey(), entry.getValue());
                    }
                }
                result.add(copy);
            }
        }

        return result;
    }

    /**
     * Evaluates the given condition with the provided truth values.
     *
     * @param condition   The condition to evaluate.
     * @param truthValues The truth values for the variables.
     * @return The result of the evaluation.
     */
    private Boolean evaluateCondition(Expression condition, Map<Expression, Object> truthValues) {
        if (condition.isBinaryExpr()) {
            var binaryExpr = condition.asBinaryExpr();
            var leftExpr = binaryExpr.getLeft();
            var rightExpr = binaryExpr.getRight();

            if (isInequality(binaryExpr)) {
                int left = (int) getValue(leftExpr, truthValues);
                int right = (int) getValue(rightExpr, truthValues);

                truthValues.put(leftExpr, left);
                truthValues.put(rightExpr, right);

                return switch (binaryExpr.getOperator()) {
                    case LESS -> left < right;
                    case GREATER -> left > right;
                    case LESS_EQUALS -> left <= right;
                    case GREATER_EQUALS -> left >= right;
                    default -> throw new UnsupportedOperationException("Unsupported operator: " + binaryExpr.getOperator());
                };
            } else {
                Boolean left = evaluateCondition(leftExpr, truthValues);
                Boolean right = evaluateCondition(rightExpr, truthValues);
                return switch (binaryExpr.getOperator()) {
                    case AND -> left && right;
                    case OR -> left || right;
                    case EQUALS -> (left == null || right == null) ? left == right : left.equals(right);
                    case NOT_EQUALS -> (left == null || right == null) ? left != right : !left.equals(right);
                    default -> throw new UnsupportedOperationException("Unsupported operator: " + binaryExpr.getOperator());
                };
            }
        } else if (condition.isUnaryExpr()) {
            var unaryExpr = condition.asUnaryExpr();
            boolean value = evaluateCondition(unaryExpr.getExpression(), truthValues);
            return switch (unaryExpr.getOperator()) {
                case LOGICAL_COMPLEMENT -> !value;
                default -> throw new UnsupportedOperationException("Unsupported operator: " + unaryExpr.getOperator());
            };
        } else if (condition.isNameExpr()) {
            return (Boolean) truthValues.get(condition);
        } else if (condition.isBooleanLiteralExpr()) {
            return condition.asBooleanLiteralExpr().getValue();
        } else if (condition.isMethodCallExpr() || condition.isFieldAccessExpr()) {
            return (Boolean) getValue(condition, truthValues);
        } else if (condition.isNullLiteralExpr()) {
            return null;
        }
        throw new UnsupportedOperationException("Unsupported expression: " + condition);
    }

    /**
     * FInd the appropriate value for the given expression
     * @param expr the expression to find the value for
     * @param truthValues the table containing the values to use
     * @return the value will typically be true/false in some cases it maybe 0/1 and when the
     *      condition has a null in it, we may return null
     */
    private Object getValue(Expression expr, Map<Expression, Object> truthValues) {
        if (expr.isNameExpr()) {
            Object value = truthValues.get(expr);
            if (value instanceof Boolean) {
                return (boolean) value ? 1 : 0;
            } else if (value instanceof Number n) {
                return n.intValue();
            }
        } else if (expr.isLiteralExpr()) {
            return switch (expr) {
                case IntegerLiteralExpr integerLiteralExpr -> Integer.valueOf(integerLiteralExpr.getValue());
                case DoubleLiteralExpr doubleLiteralExpr -> Double.valueOf(doubleLiteralExpr.getValue());
                case StringLiteralExpr stringLiteralExpr -> stringLiteralExpr.getValue();
                case NullLiteralExpr nullLiteralExpr -> null;
                default -> throw new UnsupportedOperationException("Unsupported literal expression: " + expr);
            };
        }

        return truthValues.get(expr);
    }


    /**
     * Collects variable names from the condition expression.
     */
    private static class VariableCollector extends VoidVisitorAdapter<Set<Expression>> {
        @Override
        public void visit(NameExpr n, Set<Expression> collector) {
            if (n.getParentNode().isEmpty()) {
                collector.add(n);
            } else {
                Node parent = n.getParentNode().get();
                if (!(parent instanceof MethodCallExpr || parent instanceof FieldAccessExpr)) {
                    collector.add(n);
                }
            }
            super.visit(n, collector);
        }

        @Override
        public void visit(MethodCallExpr m, Set<Expression> collector) {
            collector.add(m);
            super.visit(m, collector);
        }

        @Override
        public void visit(FieldAccessExpr f, Set<Expression> collector) {
            collector.add(f);
            super.visit(f, collector);
        }
    }

    public List<Map<Expression, Object>> getTable() {
        return table;
    }
}
