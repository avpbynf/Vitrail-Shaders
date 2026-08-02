package dev.vitrail.uniform.expr.kroppeb.stareval.element.tree;

import dev.vitrail.uniform.expr.kroppeb.stareval.element.ExpressionElement;
import dev.vitrail.uniform.expr.kroppeb.stareval.parser.UnaryOp;

public record UnaryExpressionElement(UnaryOp op, ExpressionElement inner) implements ExpressionElement {


	@Override
	public String toString() {
		return "UnaryExpr{" + this.op + " {" + this.inner + "} }";
	}
}
