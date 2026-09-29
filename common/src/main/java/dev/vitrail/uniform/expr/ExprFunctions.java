package dev.vitrail.uniform.expr;

import dev.vitrail.uniform.expr.kroppeb.stareval.expression.Expression;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.AbstractTypedFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.B2BFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.BB2BFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.F2FFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.F2IFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FF2BFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FF2FFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FFF2BFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FFF2FFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FunctionContext;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FunctionResolver;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FunctionReturn;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.I2IFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.II2BFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.II2IFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.III2BFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.III2IFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.Type;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.TypedFunction.Parameter;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.TypedFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.V2FFunction;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.V2IFunction;
import dev.vitrail.uniform.Smoothed;
import dev.vitrail.uniform.values.FrameSmoothed;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.util.Arrays;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Every function a pack may call in an expression, keyed by name and return type.
 * <p>
 * Adapted from {@code net.irisshaders.iris.parsing.IrisFunctions}, see NOTICE. What follows is
 * OptiFine's own list, kept word for word because it is the only statement of the contract these
 * packs were written against. Two of its lines matter more than they look: the identifier of
 * {@code smooth} is optional, and the default fade is one second, not zero.
 * <p>
 * The list is not exhaustive. {@code mix}, {@code edge}, {@code equal} and the unicode spellings
 * of the comparison operators are registered as well and are missing from every second hand
 * summary of this grammar.
 *
 * <pre>
 * #   sin(x)
 * #   cos(x)
 * #   asin(x)
 * #   acos(x)
 * #   tan(x)
 * #   atan(x)
 * #   atan2(y, x)
 * #   torad(deg)
 * #   todeg(rad)
 * #   min(x, y ,...)
 * #   max(x, y, ...)
 * #   clamp(x, min, max)                             Limits a value to be between min and max values
 * #   abs(x)
 * #   floor(x)
 * #   ceil(x)
 * #   exp(x)
 * #   frac(x)
 * #   log(x)
 * #   pow(x)
 * #   random()
 * #   round(x)
 * #   signum(x)
 * #   sqrt(x)
 * #   fmod(x, y)                                     Similar to Math.floorMod()
 * #   if(cond, val, [cond2, val2, ...], val_else)    Select a value based one or more conditions
 * #   smooth([id], val, [fadeInTime, [fadeOutTime]]) Smooths a variable with custom fade-in time.
 * #                                                  The "id" must be unique, if not specified it is generated automatically
 * #                                                  Default fade time is 1 sec.
 *
 * # Boolean functions
 * #   between(x, min, max)                           Check if a value is between min and max values
 * #   equals(x, y, epsilon)                          Compare two float values with error margin
 * #   in(x, val1, val2, ...)                         Check if a value equals one of several values
 * </pre>
 */
public final class ExprFunctions {
	public static final FunctionResolver functions;
	static final FunctionResolver.Builder builder = new FunctionResolver.Builder();

	static {
		{
			// Unary ops
			{
				// negate
				ExprFunctions.<I2IFunction>addVectorizable("negate", (a) -> -a);
				ExprFunctions.<F2FFunction>add("negate", (a) -> -a);

				ExprFunctions.addUnaryOpJOML("negate", VectorType.VEC2, Vector2f::negate);
				ExprFunctions.addUnaryOpJOML("negate", VectorType.VEC3, Vector3f::negate);
				ExprFunctions.addUnaryOpJOML("negate", VectorType.VEC4, Vector4f::negate);
			}
		}
		{
			// binary ops
			{
				// add
				ExprFunctions.<II2IFunction>addVectorizable("add", Integer::sum);
				ExprFunctions.<FF2FFunction>add("add", Float::sum);

				ExprFunctions.addBinaryOpJOML("add", VectorType.VEC2, Vector2f::add);
				ExprFunctions.addBinaryOpJOML("add", VectorType.VEC3, Vector3f::add);
				ExprFunctions.addBinaryOpJOML("add", VectorType.VEC4, Vector4f::add);
			}

			{
				// subtract
				ExprFunctions.<II2IFunction>addVectorizable("subtract", (a, b) -> a - b);
				ExprFunctions.<FF2FFunction>add("subtract", (a, b) -> a - b);

				ExprFunctions.addBinaryOpJOML("subtract", VectorType.VEC2, Vector2f::sub);
				ExprFunctions.addBinaryOpJOML("subtract", VectorType.VEC3, Vector3f::sub);
				ExprFunctions.addBinaryOpJOML("subtract", VectorType.VEC4, Vector4f::sub);
			}

			{
				// multiply
				ExprFunctions.<II2IFunction>addVectorizable("multiply", (a, b) -> a * b);
				ExprFunctions.<FF2FFunction>add("multiply", (a, b) -> a * b);

				ExprFunctions.addBinaryOpJOML("multiply", VectorType.VEC2, Vector2f::mul);
				ExprFunctions.addBinaryOpJOML("multiply", VectorType.VEC3, Vector3f::mul);
				ExprFunctions.addBinaryOpJOML("multiply", VectorType.VEC4, Vector4f::mul);
			}

			{
				// divide
				ExprFunctions.<FF2FFunction>add("divide", (a, b) -> a / b);

				ExprFunctions.addBinaryOpJOML("divide", VectorType.VEC2, Vector2f::div);
				ExprFunctions.addBinaryOpJOML("divide", VectorType.VEC3, Vector3f::div);
				ExprFunctions.addBinaryOpJOML("divide", VectorType.VEC4, Vector4f::div);
			}

			{
				// remainder
				ExprFunctions.<II2IFunction>addVectorizable("remainder", (a, b) -> a % b);
				ExprFunctions.<FF2FFunction>add("remainder", (a, b) -> a % b);

				// ExprFunctions.addBinaryOpJOML("multiply", VectorType.VEC2, Vector2f::??);
				// ExprFunctions.addBinaryOpJOML("multiply", VectorType.VEC3, Vector3f::??);
				// ExprFunctions.addBinaryOpJOML("multiply", VectorType.VEC4, Vector4f::??);
			}
		}
		{
			ExprFunctions.<II2BFunction>addBooleanVectorizable("equals", (a, b) -> a == b);
			ExprFunctions.<FF2BFunction>add("equals", (a, b) -> a == b);

			// Float vectors, one boolean for the whole vector, under equals, under equal because the
			// note at the top of this class names it, and under == and != through the operator
			// table. Each group of three sits after the scalar lines of the operator it extends.
			// The components compare as the scalar == does: -0.0 equals 0.0 and NaN equals nothing,
			// where JOML's equals has it the other way. This departs from Iris, whose vector
			// comparisons (parsing/IrisFunctions.java:146-155) declare a vector as their result and
			// share one name, so a call wanting a boolean, which is every use there is, resolves to
			// nothing there and the declaration is dropped. The reference therefore never answers a
			// comparison of two vectors, so no pack can have been tuned against one. What it costs
			// the image: a pack comparing two vectors draws with a boolean where Iris drops the
			// declaration and its program reads nought, and no declaration of BSL, Bliss, Photon
			// or either Complementary compares two.
			ExprFunctions.addBinaryToBooleanOpJOML("equals", VectorType.VEC2, false, ExprFunctions::sameComponents);
			ExprFunctions.addBinaryToBooleanOpJOML("equals", VectorType.VEC3, false, ExprFunctions::sameComponents);
			ExprFunctions.addBinaryToBooleanOpJOML("equals", VectorType.VEC4, false, ExprFunctions::sameComponents);

			ExprFunctions.addBinaryToBooleanOpJOML("equal", VectorType.VEC2, false, ExprFunctions::sameComponents);
			ExprFunctions.addBinaryToBooleanOpJOML("equal", VectorType.VEC3, false, ExprFunctions::sameComponents);
			ExprFunctions.addBinaryToBooleanOpJOML("equal", VectorType.VEC4, false, ExprFunctions::sameComponents);

			ExprFunctions.<II2BFunction>addBooleanVectorizable("notEquals", (a, b) -> a != b);
			ExprFunctions.<FF2BFunction>add("notEquals", (a, b) -> a != b);

			ExprFunctions.addBinaryToBooleanOpJOML("notEquals", VectorType.VEC2, true, ExprFunctions::sameComponents);
			ExprFunctions.addBinaryToBooleanOpJOML("notEquals", VectorType.VEC3, true, ExprFunctions::sameComponents);
			ExprFunctions.addBinaryToBooleanOpJOML("notEquals", VectorType.VEC4, true, ExprFunctions::sameComponents);

			ExprFunctions.<II2BFunction>add("lessThanOrEquals", (a, b) -> a <= b);
			ExprFunctions.<FF2BFunction>add("lessThanOrEquals", (a, b) -> a <= b);

			ExprFunctions.<II2BFunction>add("moreThanOrEquals", (a, b) -> a >= b);
			ExprFunctions.<FF2BFunction>add("moreThanOrEquals", (a, b) -> a >= b);

			ExprFunctions.<II2BFunction>add("lessThan", (a, b) -> a < b);
			ExprFunctions.<FF2BFunction>add("lessThan", (a, b) -> a < b);

			ExprFunctions.<II2BFunction>add("moreThan", (a, b) -> a > b);
			ExprFunctions.<FF2BFunction>add("moreThan", (a, b) -> a > b);
		}
		{

			ExprFunctions.<BB2BFunction>addVectorizable("equals", (a, b) -> a == b);
			ExprFunctions.<BB2BFunction>addVectorizable("notEquals", (a, b) -> a != b);
			ExprFunctions.<BB2BFunction>addVectorizable("and", (a, b) -> a && b);
			ExprFunctions.<BB2BFunction>addVectorizable("or", (a, b) -> a || b);
			ExprFunctions.<B2BFunction>addVectorizable("not", (a) -> !a);
		}

		{
			// these are also vectorizable in glsl
			// http://learnwebgl.brown37.net/12_shader_language/documents/webgl-reference-card-1_0.pdf
			// page 4

			{
				// Angle & Trigonometry Functions

				// optifine
				ExprFunctions.<F2FFunction>add("torad", (a) -> (float) Math.toRadians(a));
				ExprFunctions.<F2FFunction>add("todeg", (a) -> (float) Math.toDegrees(a));

				ExprFunctions.<F2FFunction>add("radians", (a) -> (float) Math.toRadians(a));
				ExprFunctions.<F2FFunction>add("degrees", (a) -> (float) Math.toDegrees(a));


				ExprFunctions.<F2FFunction>add("sin", (a) -> (float) Math.sin(a));
				ExprFunctions.<F2FFunction>add("cos", (a) -> (float) Math.cos(a));
				ExprFunctions.<F2FFunction>add("tan", (a) -> (float) Math.tan(a));
				ExprFunctions.<F2FFunction>add("asin", (a) -> (float) Math.asin(a));
				ExprFunctions.<F2FFunction>add("acos", (a) -> (float) Math.acos(a));
				ExprFunctions.<F2FFunction>add("atan", (a) -> (float) Math.atan(a));
				ExprFunctions.<FF2FFunction>add("atan", (y, x) -> (float) Math.atan2(y, x));
				// optifine
				ExprFunctions.<FF2FFunction>add("atan2", (y, x) -> (float) Math.atan2(y, x));
			}
			{
				// Exponential Functions
				ExprFunctions.<FF2FFunction>add("pow", (a, b) -> (float) Math.pow(a, b));
				ExprFunctions.<F2FFunction>add("exp", (a) -> (float) Math.exp(a));
				ExprFunctions.<F2FFunction>add("log", (a) -> (float) Math.log(a));
				// java does not have built ins: https://bugs.java.com/bugdatabase/view_bug.do?bug_id=4851627
				ExprFunctions.<F2FFunction>add("exp2", (a) -> (float) Math.pow(2, a));
				ExprFunctions.<F2FFunction>add("log2", (a) -> (float) (Math.log(a) / Math.log(2)));

				ExprFunctions.<F2FFunction>add("sqrt", (a) -> (float) Math.sqrt(a));
				// ExprFunctions.<F2FFunction>addVectorizable("inversesqrt", (a) -> (float) Math.(a));

				// optifine
				ExprFunctions.<F2FFunction>add("log10", (a) -> (float) Math.log10(a));

				// TODO the base may be static so doing `log(2, x)` would be slower than `log(x)/log(2)`
				ExprFunctions.<FF2FFunction>add("log",
					(base, value) -> (float) (Math.log(value) / Math.log(base)));

				// cause I want consistency
				ExprFunctions.<F2FFunction>add("exp10", (a) -> (float) Math.pow(10, a));

			}

			{
				// Common Functions
				ExprFunctions.<I2IFunction>addVectorizable("abs", Math::abs);
				ExprFunctions.<F2FFunction>add("abs", Math::abs);

				ExprFunctions.addUnaryOpJOML("abs", VectorType.VEC2, Vector2f::absolute);
				ExprFunctions.addUnaryOpJOML("abs", VectorType.VEC3, Vector3f::absolute);
				ExprFunctions.addUnaryOpJOML("abs", VectorType.VEC4, Vector4f::absolute);


				ExprFunctions.<F2FFunction>add("sign", Math::signum);
				// ExprFunctions.addUnaryOpJOML("abs", VectorType.VEC2, Vector2f::??);
				// ExprFunctions.addUnaryOpJOML("abs", VectorType.VEC3, Vector3f::??);
				// ExprFunctions.addUnaryOpJOML("abs", VectorType.VEC4, Vector4f::??);

				// optifine
				ExprFunctions.<F2FFunction>add("signum", Math::signum);
				// ExprFunctions.addUnaryOpJOML("abs", VectorType.VEC2, Vector2f::??);
				// ExprFunctions.addUnaryOpJOML("abs", VectorType.VEC3, Vector3f::??);
				// ExprFunctions.addUnaryOpJOML("abs", VectorType.VEC4, Vector4f::??);

				// because my type checker can handle (float) -> float and (float) -> int,
				// floor doesn't require a cast to int, but does not cause issues if the float is too big and
				// casting to float and back would change the result

				ExprFunctions.<F2FFunction>add("floor", (a) -> (float) Math.floor(a));
				ExprFunctions.<F2IFunction>add("floor", (a) -> (int) Math.floor(a));

				ExprFunctions.addUnaryOpJOML("floor", VectorType.VEC2, Vector2f::floor);
				ExprFunctions.addUnaryOpJOML("floor", VectorType.VEC3, Vector3f::floor);
				ExprFunctions.addUnaryOpJOML("floor", VectorType.VEC4, Vector4f::floor);

				ExprFunctions.<F2FFunction>add("ceil", (a) -> (float) Math.ceil(a));
				ExprFunctions.<F2IFunction>add("ceil", (a) -> (int) Math.ceil(a));

				ExprFunctions.addUnaryOpJOML("ceil", VectorType.VEC2, Vector2f::ceil);
				ExprFunctions.addUnaryOpJOML("ceil", VectorType.VEC3, Vector3f::ceil);
				ExprFunctions.addUnaryOpJOML("ceil", VectorType.VEC4, Vector4f::ceil);

				ExprFunctions.<F2FFunction>add("frac", (a) -> (float) (a - Math.floor(a)));

				// ExprFunctions.addUnaryOpJOML("frac", VectorType.VEC2, Vector2f::??);
				// ExprFunctions.addUnaryOpJOML("frac", VectorType.VEC3, Vector3f::??);
				// ExprFunctions.addUnaryOpJOML("frac", VectorType.VEC4, Vector4f::??);

				// optifine
				// OptiFine's round is Java's: Math.round of a float, widened back to a float
				// (FunctionType.eval, case ROUND). So a half goes up, towards positive infinity:
				// round(1.5) is 2 and round(-1.5) is -1, where GLSL leaves the direction of a half
				// to the driver. The int form is there for an int declaration, as floor's is. Unlike
				// floor and ceil above it has no vector form: OptiFine documents round(x) for a
				// scalar and lists no vector function but the vec2, vec3 and vec4 constructors, so
				// there is none to follow.
				// Iris leaves both lines commented out (parsing/IrisFunctions.java:274-277) and
				// resolves no call of round at all, so a declaration that uses it is dropped there
				// with a "No such function" and its program reads nought. It answers here, as it
				// does under OptiFine, whose list at the top of this class names it: nothing can
				// have been tuned against a value the reference never gives. What it costs the
				// image: a pack that calls round draws with its value where it had nought, and
				// no declaration of BSL, Bliss, Photon or either Complementary calls it.
				ExprFunctions.<F2FFunction>add("round", (a) -> (float) Math.round(a));
				ExprFunctions.<F2IFunction>add("round", Math::round);


				// mod is also already an operator

				// TODO: min and max require vararg for optifine compat
				// TODO: glsl has vecn min(vecn a, float b)
				ExprFunctions.<II2IFunction>addVectorizable("min", Math::min);
				ExprFunctions.<FF2FFunction>add("min", Math::min);

				ExprFunctions.addBinaryOpJOML("min", VectorType.VEC2, Vector2f::min);
				ExprFunctions.addBinaryOpJOML("min", VectorType.VEC3, Vector3f::min);
				ExprFunctions.addBinaryOpJOML("min", VectorType.VEC4, Vector4f::min);

				ExprFunctions.<II2IFunction>addVectorizable("max", Math::max);
				ExprFunctions.<FF2FFunction>add("max", Math::max);

				ExprFunctions.addBinaryOpJOML("max", VectorType.VEC2, Vector2f::max);
				ExprFunctions.addBinaryOpJOML("max", VectorType.VEC3, Vector3f::max);
				ExprFunctions.addBinaryOpJOML("max", VectorType.VEC4, Vector4f::max);

				{
					// Fake vararg. Each loop reads its arguments by their own index, and that is a
					// divergence: Iris reads params[1] on every turn of all four
					// (parsing/IrisFunctions.java:311, :328, :345, :362), so that min(3, 2, 1) is 2
					// there and nothing past the second argument is ever read. That is not a
					// defect a pack can be tuned against, its answer following the order the values
					// were listed in rather than the values, and the list at the top of this class
					// is OptiFine's, which compares every argument (FunctionType.getMin) for the
					// same pack. What it costs the image: a pack whose third or later value is the
					// smallest, or the largest, now draws with that one. No declaration of BSL,
					// Bliss, Photon or either Complementary calls min or max with more than two.
					for (int length = 3; length <= 16; length++) {
						{
							// min float
							Type[] inputs = new Type[length];
							Arrays.fill(inputs, Type.Float);
							ExprFunctions.add("min", new AbstractTypedFunction(Type.Float, inputs) {
								@Override
								public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
									params[0].evaluateTo(context, functionReturn);
									float min = functionReturn.floatReturn;
									for (int i = 1; i < params.length; i++) {
										params[i].evaluateTo(context, functionReturn);
										min = Math.min(min, functionReturn.floatReturn);
									}
									functionReturn.floatReturn = min;
								}
							});
						}
						{
							// max float
							Type[] inputs = new Type[length];
							Arrays.fill(inputs, Type.Float);
							ExprFunctions.add("max", new AbstractTypedFunction(Type.Float, inputs) {
								@Override
								public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
									params[0].evaluateTo(context, functionReturn);
									float max = functionReturn.floatReturn;
									for (int i = 1; i < params.length; i++) {
										params[i].evaluateTo(context, functionReturn);
										max = Math.max(max, functionReturn.floatReturn);
									}
									functionReturn.floatReturn = max;
								}
							});
						}
						{
							// min int
							Type[] inputs = new Type[length];
							Arrays.fill(inputs, Type.Int);
							ExprFunctions.addVectorizable("min", new AbstractTypedFunction(Type.Int, inputs) {
								@Override
								public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
									params[0].evaluateTo(context, functionReturn);
									int min = functionReturn.intReturn;
									for (int i = 1; i < params.length; i++) {
										params[i].evaluateTo(context, functionReturn);
										min = Math.min(min, functionReturn.intReturn);
									}
									functionReturn.intReturn = min;
								}
							});
						}
						{
							// max int
							Type[] inputs = new Type[length];
							Arrays.fill(inputs, Type.Int);
							ExprFunctions.addVectorizable("max", new AbstractTypedFunction(Type.Int, inputs) {
								@Override
								public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
									params[0].evaluateTo(context, functionReturn);
									int max = functionReturn.intReturn;
									for (int i = 1; i < params.length; i++) {
										params[i].evaluateTo(context, functionReturn);
										max = Math.max(max, functionReturn.intReturn);
									}
									functionReturn.intReturn = max;
								}
							});
						}
					}
				}

				// if max < min => undefined behaviour
				// TODO: glsl has vecn mix(vecn x, float min, float max)
				ExprFunctions.<III2IFunction>addVectorizable("clamp",
					(val, min, max) -> Math.max(min, Math.min(max, val)));
				ExprFunctions.<FFF2FFunction>add("clamp",
					(val, min, max) -> Math.max(min, Math.min(max, val)));
				ExprFunctions.addTernaryOpJOML("clamp", VectorType.VEC2, (val, min, max, dest) -> {
					val.min(max, dest);
					dest.max(min);
				});
				ExprFunctions.addTernaryOpJOML("clamp", VectorType.VEC3, (val, min, max, dest) -> {
					val.min(max, dest);
					dest.max(min);
				});
				ExprFunctions.addTernaryOpJOML("clamp", VectorType.VEC4, (val, min, max, dest) -> {
					val.min(max, dest);
					dest.max(min);
				});

				// TODO: glsl has vecn mix(vecn x, vecn y, float a)
				ExprFunctions.<FFF2FFunction>add("mix", (x, y, a) -> x + (y - x) * a);
				// TODO flaot vector lerp

				// TODO: glsl has vecn step(float edge, vecn x)
				ExprFunctions.<II2IFunction>addVectorizable("edge", (edge, x) -> (x < edge) ? 0 : 1);
				ExprFunctions.<FF2FFunction>add("edge", (edge, x) -> (x < edge) ? 0 : 1);
				// TODO float vector step
				// TODO: smooth step
			}

			{
				// Geometric Functions
				// TODO: Geometric Functions
			}

			{
				// Matrix Functions
				// TODO: Add matrices
			}

			{
				// Vector Relational Functions
				// TODO: These might clash with the operators
				// Although we can have multiple return values so
			}


			{
				{
					// fmod
					ExprFunctions.<II2IFunction>addVectorizable("fmod", Math::floorMod);
					ExprFunctions.<FF2FFunction>add("fmod", (a, b) -> (a % b + b) % b);
				}
				{
					Random random = new Random();
					// randomInt(), randomInt(int bound), randomInt(int inclusiveMin, int exclusiveMax)
					ExprFunctions.<V2IFunction>addVectorizable("randomInt", random::nextInt);
					ExprFunctions.<I2IFunction>addVectorizable("randomInt", random::nextInt);
					ExprFunctions.<II2IFunction>addVectorizable("randomInt", (a, b) -> random.nextInt(b - a) + a);

					// random, random(float min, float max)
					ExprFunctions.<V2FFunction>add("random", random::nextFloat);
					ExprFunctions.<FF2FFunction>add("random", (min, max) ->
						min + random.nextFloat() * (max - min));
				}
				{
					// IF
					// if(boolean, primitive, primitive) -> primitive
					// if(boolean, xvec, xvec) -> xvec
					// TODO: REDO: if(bvec, xvec, xvec) -> xvec
					// TODO: optifine requires vararg
					for (Type.Primitive type : Type.AllPrimitives) {
						add("if", new AbstractTypedFunction(type, new Type[]{Type.Boolean, type, type}) {
							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[0].evaluateTo(context, functionReturn);

								params[
									functionReturn.booleanReturn ? 1 : 2
									].evaluateTo(context, functionReturn);

							}
						});
					}

					for (Type type : VectorType.AllVectorTypes) {
						add("if", new AbstractTypedFunction(type, new Type[]{Type.Boolean, type, type}) {
							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[0].evaluateTo(context, functionReturn);

								params[
									functionReturn.booleanReturn ? 1 : 2
									].evaluateTo(context, functionReturn);

							}
						});
					}

					{
						// FAKE vararg
						for (int length = 2; length <= 16; length++) {
							for (Type.Primitive type : Type.AllPrimitives) {
								Type[] params = new Type[length * 2 + 1];
								for (int i = 0; i < length * 2; i += 2) {
									params[i] = Type.Boolean;
									params[i + 1] = type;
								}
								params[length * 2] = type;
								int finalLength = length * 2;
								add("if", new AbstractTypedFunction(type, params) {
									@Override
									public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
										for (int i = 0; i < finalLength; i += 2) {
											params[i].evaluateTo(context, functionReturn);
											if (functionReturn.booleanReturn) {
												params[i + 1].evaluateTo(context, functionReturn);
												return;
											}
										}

										// Read once, and only when no condition held. Iris reads
										// it inside the loop (parsing/IrisFunctions.java:491),
										// once for every condition that fails and before a later
										// one wins. The value still comes out right, the winning
										// branch being read last, but a smooth() written there
										// steps once per failed condition on every frame and
										// fades that many times faster, and a random() draws
										// that many times. OptiFine reads it once, after the
										// loop, and so does this. What it costs the image is
										// the fade of such a smooth(), now at the rate the pack
										// wrote; a fallback with nothing in it that steps gives
										// the same value either way. Photon's
										// moon_phase_brightness is the one long if in BSL,
										// Bliss, Photon and either Complementary, and its
										// fallback is a constant, so none of them changes.
										params[finalLength].evaluateTo(context, functionReturn);
									}
								});
							}
						}
					}
				}
				{
					// smooth, in the six shapes OptiFine documents. The identifier is optional and
					// decorative: what actually keeps one call site apart from another is that the
					// resolver builds a fresh function, and so a fresh accumulator, per occurrence.
					for (int fadeArgs = 0; fadeArgs <= 2; fadeArgs++) {
						addSmooth(false, fadeArgs);
						addSmooth(true, fadeArgs);
					}
				}
			}
		}

		// casts
		{
			addImplicitCast(Type.Int, Type.Float, r -> r.floatReturn = r.intReturn);
			// this is actually done by round i think
			addExplicitCast(Type.Float, Type.Int, r -> r.intReturn = (int) r.floatReturn);

			// Added here, and not in Iris. Iris registers a boolean uniform as a boolean and needs
			// no such rule; our catalogue has no boolean shape, so a value like is_hurt arrives as
			// an integer and would not fit the condition of an if(). Six of Bliss's declarations
			// hang off that one, and they read as a resolver bug when they go missing.
			// The cast only ever runs after an exact match has failed, so nothing that resolved
			// before resolves differently now.
			addImplicitCast(Type.Int, Type.Boolean, r -> r.booleanReturn = r.intReturn != 0);
		}

		// boolean functions
		{
			ExprFunctions.<III2BFunction>add("between", (a, min, max) -> a >= min && a <= max);
			ExprFunctions.<FFF2BFunction>add("between", (a, min, max) -> a >= min && a <= max);

			ExprFunctions.<FFF2BFunction>add("equals", (a, b, epsilon) -> Math.abs(a - b) <= epsilon);

			// TODO: varargs
			// TODO also for other types
			{
				// FAKE vararg
				for (int length = 2; length <= 32; length++) {
					Type[] params = new Type[length];
					Arrays.fill(params, Type.Float);
					int finalLength = length;
					ExprFunctions.add("in", new AbstractTypedFunction(
						Type.Boolean,
						params
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							float value = functionReturn.floatReturn;
							for (int i = 1; i < finalLength; i++) {
								params[i].evaluateTo(context, functionReturn);
								if (functionReturn.floatReturn == value) {
									functionReturn.booleanReturn = true;
									return;
								}
							}
							functionReturn.booleanReturn = false;
						}
					});
				}
			}
		}

		// create vectors
		{
			for (Type.Primitive type : new Type.Primitive[]{Type.Boolean, Type.Int}) {
				for (int size = 2; size <= 4; size++) {
					TypedFunction function = new VectorConstructor(type, size);
					// TODO make it possible to do `vec3(vec2(0),0)`
					add(
						Character.toLowerCase(
							type.getClass().getSimpleName().charAt(0)
						) + "vec" + size, function);
				}
			}

			add("vec2", new AbstractTypedFunction(
				VectorType.VEC2,
				new Type[]{Type.Float, Type.Float}
			) {
				@Override
				public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
					params[0].evaluateTo(context, functionReturn);
					float x = functionReturn.floatReturn;

					params[1].evaluateTo(context, functionReturn);
					float y = functionReturn.floatReturn;

					// TODO: this can't be cached atm. If we swap this to a function provider we could
					functionReturn.objectReturn = new Vector2f(x, y);
				}
			});

			add("vec3", new AbstractTypedFunction(
				VectorType.VEC3,
				new Type[]{Type.Float, Type.Float, Type.Float}
			) {
				@Override
				public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
					params[0].evaluateTo(context, functionReturn);
					float x = functionReturn.floatReturn;

					params[1].evaluateTo(context, functionReturn);
					float y = functionReturn.floatReturn;

					params[2].evaluateTo(context, functionReturn);
					float z = functionReturn.floatReturn;

					// TODO: this can't be cached atm. If we swap this to a function provider we could
					functionReturn.objectReturn = new Vector3f(x, y, z);
				}
			});

			add("vec4", new AbstractTypedFunction(
				VectorType.VEC4,
				new Type[]{Type.Float, Type.Float, Type.Float, Type.Float}
			) {
				@Override
				public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
					params[0].evaluateTo(context, functionReturn);
					float x = functionReturn.floatReturn;

					params[1].evaluateTo(context, functionReturn);
					float y = functionReturn.floatReturn;

					params[2].evaluateTo(context, functionReturn);
					float z = functionReturn.floatReturn;

					params[3].evaluateTo(context, functionReturn);
					float w = functionReturn.floatReturn;

					// TODO: this can't be cached atm. If we swap this to a function provider we could
					functionReturn.objectReturn = new Vector4f(x, y, z, w);
				}
			});
		}

		// accessors
		{
			// is this the best way to do these?
			String[][] accessNames = new String[][]{
				new String[]{"0", "r", "x", "s"},
				new String[]{"1", "g", "y", "t"},
				new String[]{"2", "b", "z", "p"},
				new String[]{"3", "a", "w", "q"}
			};

			{
				// access$0
				for (String access : accessNames[0]) {
					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC2}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector2f) functionReturn.objectReturn).x;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC2}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector2i) functionReturn.objectReturn).x;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector3f) functionReturn.objectReturn).x;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector3i) functionReturn.objectReturn).x;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector4f) functionReturn.objectReturn).x;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector4i) functionReturn.objectReturn).x;
						}
					});
				}
			}

			{
				// access$1
				for (String access : accessNames[1]) {
					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC2}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector2f) functionReturn.objectReturn).y;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC2}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector2i) functionReturn.objectReturn).y;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector3f) functionReturn.objectReturn).y;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector3i) functionReturn.objectReturn).y;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector4f) functionReturn.objectReturn).y;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector4i) functionReturn.objectReturn).y;
						}
					});
				}
			}

			{
				// access$2
				for (String access : accessNames[2]) {
					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector3f) functionReturn.objectReturn).z;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC3}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector3i) functionReturn.objectReturn).z;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector4f) functionReturn.objectReturn).z;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector4i) functionReturn.objectReturn).z;
						}
					});
				}
			}

			{
				// access$3
				for (String access : accessNames[3]) {
					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Float,
						new Type[]{VectorType.VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.floatReturn = ((Vector4f) functionReturn.objectReturn).w;
						}
					});

					ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
						Type.Int,
						new Type[]{VectorType.I_VEC4}
					) {
						@Override
						public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
							params[0].evaluateTo(context, functionReturn);
							functionReturn.intReturn = ((Vector4i) functionReturn.objectReturn).w;
						}
					});
				}
			}

			{
				// matrix access
				for (int i = 0; i < 4; i++) {
					for (String access : accessNames[i]) {
						int finalI = i;
						ExprFunctions.add("<access$" + access + ">", new AbstractTypedFunction(
							VectorType.VEC4,
							new Type[]{MatrixType.MAT4}
						) {
							@Override
							public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
								params[0].evaluateTo(context, functionReturn);
								functionReturn.objectReturn = ((Matrix4f) functionReturn.objectReturn).getColumn(finalI, new Vector4f());
							}
						});
					}
				}
			}
		}

		functions = builder.build();
	}

	/**
	 * A function applied component by component, one per size of vector.
	 * <p>
	 * Registered as a supplier for the same reason as {@link #addUnaryOpJOML}, though what one of
	 * these keeps is its operands rather than its answer: it parks each evaluated argument in a
	 * field before it walks the components. Shared between call sites, as Iris shares it
	 * ({@code parsing/IrisFunctions.java:1049}, and {@code :1066} for the boolean fold below), the
	 * inner sum of {@code a + (b + c)} parks {@code b} where the outer one had parked {@code a},
	 * and the outer one then adds {@code b}. What that costs the image: an integer vector reaches a
	 * declaration only through a comparison or an {@code if}, and none of BSL, Bliss, Photon or
	 * either Complementary builds one.
	 */
	static <T extends TypedFunction> void addVectorized(String name, T function) {
		if (function.getReturnType() instanceof Type.Primitive) {
			for (int size = 2; size <= 4; size++) {
				int length = size;
				builder.addDynamicFunction(name, new VectorType.ArrayVector(function.getReturnType(), length),
					() -> new VectorizedFunction(function, length));
			}
		} else {
			throw new IllegalArgumentException(name + " is not vectorizable");
		}
	}

	static <T extends TypedFunction> void addVectorizable(String name, T function) {
		add(name, function);
		addVectorized(name, function);
	}

	/** The same, folding the components into one boolean. It parks its operands as well. */
	static <T extends TypedFunction> void addBooleanVectorizable(String name, T function) {
		assert function.getReturnType().equals(Type.Boolean);
		add(name, function);
		if (function.getReturnType() instanceof Type.Primitive) {
			for (int size = 2; size <= 4; size++) {
				int length = size;
				builder.addDynamicFunction(name, Type.Boolean,
					() -> new BooleanVectorizedFunction(function, length));
			}
		} else {
			throw new IllegalArgumentException(name + " is not vectorizable");
		}
	}

	/**
	 * A function of one float vector, answering one.
	 * <p>
	 * The answer is written into a vector the function keeps, and that is why this registers a
	 * supplier rather than an instance: the resolver asks it once per place an expression calls
	 * the function, so each call site gets a vector of its own, as each {@code smooth()} gets an
	 * accumulator of its own. One instance shared by every call site, which is what Iris registers
	 * ({@code parsing/IrisFunctions.java:1074}, and {@code :1093} and {@code :1115} for the two
	 * below), hands every call the same vector, and in {@code abs(a) + abs(b)} the second call
	 * overwrites the first answer before the sum reads it: the sum is twice {@code abs(b)}.
	 * <p>
	 * That is a divergence, and not from anything a pack can have been tuned against: the vector
	 * functions are the reference's own addition to OptiFine's list, and what it answers for two
	 * calls in one expression is not what the pack wrote. What it costs the image is nothing among
	 * the packs read here, no declaration of BSL, Bliss, Photon or either Complementary calling one
	 * of these twice.
	 * <p>
	 * An answer still lives only until its own call site runs again, which is why a declaration
	 * copies its value out ({@code CustomUniforms.Node}).
	 */
	static <T> void addUnaryOpJOML(String name, VectorType.JOMLVector<T> type, BiConsumer<T, T> function) {
		builder.addDynamicFunction(name, type, () -> new AbstractTypedFunction(
			type,
			new Type[]{type}
		) {
			final private T vector = type.create();

			@SuppressWarnings("unchecked")
			@Override
			public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
				params[0].evaluateTo(context, functionReturn);
				T a = (T) functionReturn.objectReturn;

				function.accept(a, this.vector);
				functionReturn.objectReturn = this.vector;
			}
		});
	}

	/** A function of two float vectors, one answer vector per call site as {@link #addUnaryOpJOML}. */
	static <T> void addBinaryOpJOML(String name, VectorType.JOMLVector<T> type, TriConsumer<T, T, T> function) {
		builder.addDynamicFunction(name, type, () -> new AbstractTypedFunction(
			type,
			new Type[]{type, type}
		) {
			final private T vector = type.create();

			@SuppressWarnings("unchecked")
			@Override
			public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
				params[0].evaluateTo(context, functionReturn);
				T a = (T) functionReturn.objectReturn;

				params[1].evaluateTo(context, functionReturn);
				T b = (T) functionReturn.objectReturn;

				function.accept(a, b, this.vector);
				functionReturn.objectReturn = this.vector;
			}
		});
	}

	/** A function of three float vectors, one answer vector per call site as {@link #addUnaryOpJOML}. */
	static <T> void addTernaryOpJOML(String name, VectorType.JOMLVector<T> type, QuadConsumer<T, T, T, T> function) {
		builder.addDynamicFunction(name, type, () -> new AbstractTypedFunction(
			type,
			new Type[]{type, type, type}
		) {
			final private T vector = type.create();

			@SuppressWarnings("unchecked")
			@Override
			public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
				params[0].evaluateTo(context, functionReturn);
				T a = (T) functionReturn.objectReturn;

				params[1].evaluateTo(context, functionReturn);
				T b = (T) functionReturn.objectReturn;

				params[2].evaluateTo(context, functionReturn);
				T c = (T) functionReturn.objectReturn;

				function.accept(a, b, c, this.vector);
				functionReturn.objectReturn = this.vector;
			}
		});
	}

	/**
	 * A test of two float vectors, answering one boolean. It keeps nothing between calls, so one
	 * instance serves every call site.
	 */
	static <T> void addBinaryToBooleanOpJOML(
		String name,
		VectorType.JOMLVector<T> type,
		boolean inverted,
		ObjectObject2BooleanFunction<T, T> function) {
		builder.add(name, new AbstractTypedFunction(
			Type.Boolean,
			new Type[]{type, type}
		) {
			@SuppressWarnings("unchecked")
			@Override
			public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
				params[0].evaluateTo(context, functionReturn);
				T a = (T) functionReturn.objectReturn;

				params[1].evaluateTo(context, functionReturn);
				T b = (T) functionReturn.objectReturn;

				functionReturn.booleanReturn = function.apply(a, b) != inverted;
			}
		});
	}

	private static boolean sameComponents(Vector2f a, Vector2f b) {
		return a.x == b.x && a.y == b.y;
	}

	private static boolean sameComponents(Vector3f a, Vector3f b) {
		return a.x == b.x && a.y == b.y && a.z == b.z;
	}

	private static boolean sameComponents(Vector4f a, Vector4f b) {
		return a.x == b.x && a.y == b.y && a.z == b.z && a.w == b.w;
	}

	static <T extends TypedFunction> void add(String name, T function) {
		builder.add(name, function);
	}

	static void addCast(final String name, final Type from, final Type to, final Consumer<FunctionReturn> function) {
		add(name, new TypedFunction() {
			@Override
			public Type getReturnType() {
				return to;
			}

			@Override
			public Parameter[] getParameters() {
				return new Parameter[]{new Parameter(from)};
			}

			@Override
			public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
				params[0].evaluateTo(context, functionReturn);
				function.accept(functionReturn);
			}
		});
	}

	static void addImplicitCast(final Type from, final Type to, final Consumer<FunctionReturn> function) {
		addCast("<cast>", from, to, function);
		addExplicitCast(from, to, function);
	}

	static void addExplicitCast(final Type from, final Type to, final Consumer<FunctionReturn> function) {
		addCast("to" + to.getClass().getSimpleName(), from, to, function);
	}

	/**
	 * One overload of {@code smooth}. {@code fadeArgs} is 0 for the default one second fade, 1 for
	 * a single half life used both ways, 2 for a separate rise and fall.
	 * <p>
	 * The identifier, when present, is declared constant so that the resolver only matches it
	 * against a literal, and it is never read: OptiFine used it to tell call sites apart, and here
	 * each call site already has its own accumulator.
	 */
	private static void addSmooth(boolean withId, int fadeArgs) {
		int base = withId ? 1 : 0;
		Parameter[] parameters = new Parameter[base + 1 + fadeArgs];
		if (withId) {
			parameters[0] = new Parameter(Type.Float, true);
		}
		for (int i = base; i < parameters.length; i++) {
			parameters[i] = new Parameter(Type.Float, false);
		}

		builder.addDynamicFunction("smooth", Type.Float, () ->
			new AbstractTypedFunction(Type.Float, parameters, withId ? 1 : 0, false) {
				// Tracked, so that a world change forgets it with the engine's own smoothed values.
				private final Smoothed smoothed = FrameSmoothed.tracked();

				@Override
				public void evaluateTo(Expression[] params, FunctionContext context, FunctionReturn functionReturn) {
					params[base].evaluateTo(context, functionReturn);
					float target = functionReturn.floatReturn;

					float up = 1.0F;
					float down = 1.0F;
					if (fadeArgs >= 1) {
						params[base + 1].evaluateTo(context, functionReturn);
						up = functionReturn.floatReturn;
						down = up;
					}
					if (fadeArgs == 2) {
						params[base + 2].evaluateTo(context, functionReturn);
						down = functionReturn.floatReturn;
					}

					float dt = context instanceof FrameClock clock ? clock.deltaSeconds() : 0.0F;
					functionReturn.floatReturn = smoothed.updateAndGet(target, up, down, dt);
				}
			});
	}

	private ExprFunctions() {
	}

	interface ObjectObject2BooleanFunction<T, U> {
		boolean apply(T t, U u);
	}

	interface TriConsumer<T, U, V> {
		void accept(T t, U u, V v);
	}

	interface QuadConsumer<T, U, V, W> {
		void accept(T t, U u, V v, W w);
	}
}

