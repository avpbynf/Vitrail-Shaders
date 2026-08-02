package dev.vitrail.uniform.expr.kroppeb.stareval.element;

public interface PriorityOperatorElement extends Element {
	int getPriority();

	ExpressionElement resolveWith(ExpressionElement right);
}
