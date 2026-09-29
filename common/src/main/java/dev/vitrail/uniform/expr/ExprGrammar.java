package dev.vitrail.uniform.expr;

import dev.vitrail.uniform.expr.kroppeb.stareval.parser.BinaryOp;
import dev.vitrail.uniform.expr.kroppeb.stareval.parser.ParserOptions;
import dev.vitrail.uniform.expr.kroppeb.stareval.parser.UnaryOp;

/**
 * The operators a pack may write in an expression: fifteen of them over five levels of
 * precedence, {@code * / %} then {@code + -} then the comparisons then {@code &&} then
 * {@code ||}, with {@code !} and {@code -} as the two unary ones.
 * <p>
 * The last two levels are a divergence. Iris puts {@code &&} and {@code ||} on one level
 * ({@code parsing/IrisOptions.java:20-21}) and so reads them left to right: {@code a || b && c}
 * is {@code (a || b) && c} there. OptiFine gives {@code &&} the higher precedence, three against
 * two ({@code FunctionType.AND} and {@code OR}, merged from the highest down by
 * {@code ExpressionParser.makeInfixFunc}), and reads it {@code a || (b && c)}, as C, Java and the
 * GLSL of the same pack do. A pack that writes the two unbracketed is written with that reading
 * in mind, and under Iris gets another one than under OptiFine, so no single reading could have
 * been tuned against; this one is OptiFine's, whose list the functions follow too. What it costs
 * the image: such a declaration changes value when the left of the {@code ||} holds and the right
 * of the {@code &&} does not. No declaration of BSL, Bliss, Photon or either Complementary writes
 * the two together, so none of them reads differently.
 * <p>
 * The comparisons stay on one level, where OptiFine puts {@code ==} and {@code !=} one below the
 * others. That can only change an expression comparing the result of a comparison with a number,
 * which neither engine accepts, so it changes nothing that resolves.
 * <p>
 * Adapted from {@code net.irisshaders.iris.parsing.IrisOptions}, see NOTICE. The three unicode
 * spellings at the end are not decoration: a pack written in an editor that substitutes them
 * would not parse without them, and they cost nothing.
 */
public final class ExprGrammar {
	public static final ParserOptions options;
	static final BinaryOp Multiply = new BinaryOp("multiply", 0);
	static final BinaryOp Divide = new BinaryOp("divide", 0);
	static final BinaryOp Remainder = new BinaryOp("remainder", 0);
	static final BinaryOp Add = new BinaryOp("add", 1);
	static final BinaryOp Subtract = new BinaryOp("subtract", 1);
	static final BinaryOp Equals = new BinaryOp("equals", 2);
	static final BinaryOp NotEquals = new BinaryOp("notEquals", 2);
	static final BinaryOp LessThan = new BinaryOp("lessThan", 2);
	static final BinaryOp MoreThan = new BinaryOp("moreThan", 2);
	static final BinaryOp LessThanOrEquals = new BinaryOp("lessThanOrEquals", 2);
	static final BinaryOp MoreThanOrEquals = new BinaryOp("moreThanOrEquals", 2);
	static final BinaryOp And = new BinaryOp("and", 3);
	static final BinaryOp Or = new BinaryOp("or", 4);
	static final UnaryOp Not = new UnaryOp("not");
	static final UnaryOp Negate = new UnaryOp("negate");

	static {
		final ParserOptions.Builder builder = new ParserOptions.Builder();
		builder.addBinaryOp("*", Multiply);
		builder.addBinaryOp("/", Divide);
		builder.addBinaryOp("%", Remainder);

		builder.addBinaryOp("+", Add);
		builder.addBinaryOp("-", Subtract);

		builder.addBinaryOp("==", Equals);
		builder.addBinaryOp("!=", NotEquals);
		builder.addBinaryOp("<", LessThan);
		builder.addBinaryOp(">", MoreThan);
		builder.addBinaryOp("<=", LessThanOrEquals);
		builder.addBinaryOp(">=", MoreThanOrEquals);

		builder.addBinaryOp("≠", NotEquals);
		builder.addBinaryOp("≤", LessThanOrEquals);
		builder.addBinaryOp("≥", MoreThanOrEquals);

		builder.addBinaryOp("&&", And);
		builder.addBinaryOp("||", Or);

		builder.addUnaryOp("!", Not);
		builder.addUnaryOp("-", Negate);

		options = builder.build();
	}

	private ExprGrammar() {
	}
}
