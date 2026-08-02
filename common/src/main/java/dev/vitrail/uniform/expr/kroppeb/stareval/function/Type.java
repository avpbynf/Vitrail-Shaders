package dev.vitrail.uniform.expr.kroppeb.stareval.function;

import dev.vitrail.uniform.expr.kroppeb.stareval.expression.ConstantExpression;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.TypedFunction.Parameter;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.expr.MatrixType;
import dev.vitrail.uniform.expr.VectorType;

public abstract class Type {
	public static final Boolean Boolean = new Boolean();
	public static final Int Int = new Int();
	public static final Float Float = new Float();
	public static final Parameter BooleanParameter = new Parameter(Boolean);
	public static final Parameter IntParameter = new Parameter(Int);
	public static final Parameter FloatParameter = new Parameter(Float);
	public static final Primitive[] AllPrimitives = {Type.Boolean, Type.Int, Type.Float};

	/** The block shape a value of this type takes, or null when nothing here can carry it. */
	public static UniformShape convert(Type type) {
		if (type == Type.Int || type == Type.Boolean) return UniformShape.INT;
		else if (type == Type.Float) return UniformShape.FLOAT;
		else if (type == VectorType.VEC2) return UniformShape.VEC2;
		else if (type == VectorType.VEC3) return UniformShape.VEC3;
		else if (type == VectorType.VEC4) return UniformShape.VEC4;
		else if (type == VectorType.I_VEC2) return UniformShape.IVEC2;
		else if (type == VectorType.I_VEC3) return UniformShape.IVEC3;
		else if (type == VectorType.I_VEC4) return UniformShape.IVEC4;
		else if (type == MatrixType.MAT3) return UniformShape.MAT3;
		else if (type == MatrixType.MAT4) return UniformShape.MAT4;
		else return null;
	}

	/** The other way round, for reading an engine value into an expression. */
	public static Type of(UniformShape shape) {
		return switch (shape) {
			case FLOAT -> Type.Float;
			case INT -> Type.Int;
			case VEC2 -> VectorType.VEC2;
			case VEC3 -> VectorType.VEC3;
			case VEC4 -> VectorType.VEC4;
			case IVEC2 -> VectorType.I_VEC2;
			case IVEC3 -> VectorType.I_VEC3;
			case IVEC4 -> VectorType.I_VEC4;
			case MAT3 -> MatrixType.MAT3;
			case MAT4 -> MatrixType.MAT4;
			case FOG -> null;
		};
	}

	public abstract ConstantExpression createConstant(FunctionReturn functionReturn);

	public abstract Object createArray(int length);

	public abstract void setValueFromReturn(Object array, int index, FunctionReturn value);

	public abstract void getValueFromArray(Object array, int index, FunctionReturn value);

	public abstract String toString();

	public abstract static class Primitive extends Type {
	}

	public static class ObjectType extends Type {
		@Override
		public ConstantExpression createConstant(FunctionReturn functionReturn) {
			Object object = functionReturn.objectReturn;
			return new ConstantExpression(this) {
				@Override
				public void evaluateTo(FunctionContext context, FunctionReturn functionReturn) {
					functionReturn.objectReturn = object;
				}
			};
		}

		@Override
		public Object createArray(int length) {
			return new Object[length];
		}

		@Override
		public void setValueFromReturn(Object array, int index, FunctionReturn value) {
			Object[] arr = (Object[]) array;
			arr[index] = value.objectReturn;
		}

		@Override
		public void getValueFromArray(Object array, int index, FunctionReturn value) {
			Object[] arr = (Object[]) array;
			value.objectReturn = arr[index];
		}

		@Override
		public String toString() {
			return "Object";
		}
	}

	public static class Boolean extends Primitive {
		@Override
		public ConstantExpression createConstant(FunctionReturn functionReturn) {
			boolean value = functionReturn.booleanReturn;
			return new ConstantExpression(this) {
				@Override
				public void evaluateTo(FunctionContext context, FunctionReturn functionReturn) {
					functionReturn.booleanReturn = value;
				}
			};
		}

		@Override
		public Object createArray(int length) {
			return new boolean[length];
		}

		@Override
		public void setValueFromReturn(Object array, int index, FunctionReturn value) {
			boolean[] arr = (boolean[]) array;
			arr[index] = value.booleanReturn;
		}

		@Override
		public void getValueFromArray(Object array, int index, FunctionReturn value) {
			boolean[] arr = (boolean[]) array;
			value.booleanReturn = arr[index];
		}

		@Override
		public String toString() {
			return "bool";
		}
	}

	public static class Int extends Primitive {
		@Override
		public ConstantExpression createConstant(FunctionReturn functionReturn) {
			int value = functionReturn.intReturn;
			return new ConstantExpression(this) {
				@Override
				public void evaluateTo(FunctionContext context, FunctionReturn functionReturn) {
					functionReturn.intReturn = value;
				}
			};
		}

		@Override
		public Object createArray(int length) {
			return new int[length];
		}

		@Override
		public void setValueFromReturn(Object array, int index, FunctionReturn value) {
			int[] arr = (int[]) array;
			arr[index] = value.intReturn;
		}

		@Override
		public void getValueFromArray(Object array, int index, FunctionReturn value) {
			int[] arr = (int[]) array;
			value.intReturn = arr[index];
		}

		@Override
		public String toString() {
			return "int";
		}
	}

	public static class Float extends Primitive {
		@Override
		public ConstantExpression createConstant(FunctionReturn functionReturn) {
			float value = functionReturn.floatReturn;
			return new ConstantExpression(this) {
				@Override
				public void evaluateTo(FunctionContext context, FunctionReturn functionReturn) {
					functionReturn.floatReturn = value;
				}
			};
		}

		@Override
		public Object createArray(int length) {
			return new float[length];
		}

		@Override
		public void setValueFromReturn(Object array, int index, FunctionReturn value) {
			float[] arr = (float[]) array;
			arr[index] = value.floatReturn;
		}

		@Override
		public void getValueFromArray(Object array, int index, FunctionReturn value) {
			float[] arr = (float[]) array;
			value.floatReturn = arr[index];
		}

		@Override
		public String toString() {
			return "float";
		}
	}
}
