package dev.vitrail.uniform.expr.kroppeb.stareval.parser;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ParserOptions {
	private final Map<Character, ? extends OpResolver<? extends UnaryOp>> unaryOpResolvers;
	private final Map<Character, ? extends OpResolver<? extends BinaryOp>> binaryOpResolvers;
	private final TokenRules tokenRules;

	private ParserOptions(
		Map<Character, ? extends OpResolver<? extends UnaryOp>> unaryOpResolvers,
		Map<Character, ? extends OpResolver<? extends BinaryOp>> binaryOpResolvers,
		TokenRules tokenRules) {
		this.unaryOpResolvers = unaryOpResolvers;
		this.binaryOpResolvers = binaryOpResolvers;
		this.tokenRules = tokenRules;
	}

	TokenRules getTokenRules() {
		return this.tokenRules;
	}

	OpResolver<? extends UnaryOp> getUnaryOpResolver(char c) {
		return this.unaryOpResolvers.get(c);
	}

	OpResolver<? extends BinaryOp> getBinaryOpResolver(char c) {
		return this.binaryOpResolvers.get(c);
	}

	/**
	 * Defines a set of rules that allows the tokenizer to identify tokens within a string.
	 */
	public interface TokenRules {
		TokenRules DEFAULT = new TokenRules() {
		};

		static boolean isNumber(final char c) {
			return c >= '0' && c <= '9';
		}

		static boolean isLowerCaseLetter(final char c) {
			return c >= 'a' && c <= 'z';
		}

		static boolean isUpperCaseLetter(final char c) {
			return c >= 'A' && c <= 'Z';
		}

		static boolean isLetter(final char c) {
			return isLowerCaseLetter(c) || isUpperCaseLetter(c);
		}

		default boolean isIdStart(final char c) {
			return isLetter(c) || c == '_';
		}

		default boolean isIdPart(final char c) {
			return this.isIdStart(c) || isNumber(c);
		}

		default boolean isNumberStart(final char c) {
			return isNumber(c) || c == '.';
		}

		default boolean isNumberPart(final char c) {
			return this.isNumberStart(c) || isLetter(c);
		}

		default boolean isAccessStart(final char c) {
			return this.isIdStart(c) || isNumber(c);
		}

		default boolean isAccessPart(final char c) {
			return this.isAccessStart(c);
		}
	}

	public static class Builder {
		private final Map<Character, OpResolver.Builder<UnaryOp>> unaryOpResolvers = new LinkedHashMap<>();
		private final Map<Character, OpResolver.Builder<BinaryOp>> binaryOpResolvers = new LinkedHashMap<>();
		private TokenRules tokenRules = TokenRules.DEFAULT;

		private static <T> Map<Character, ? extends OpResolver<? extends T>> buildOpResolvers(
			Map<Character, OpResolver.Builder<T>> ops) {
			Map<Character, OpResolver<T>> result = new LinkedHashMap<>();

			ops.forEach((key, value) -> result.put(key, value.build()));

			return result;
		}

		public void addUnaryOp(String s, UnaryOp op) {
			char first = s.charAt(0);
			String trailing = s.substring(1);

			this.unaryOpResolvers.computeIfAbsent(first, (c) -> new OpResolver.Builder<>()).multiChar(trailing, op);
		}

		public void addBinaryOp(String s, BinaryOp op) {
			char first = s.charAt(0);
			String trailing = s.substring(1);

			this.binaryOpResolvers.computeIfAbsent(first, (c) -> new OpResolver.Builder<>()).multiChar(trailing, op);
		}

		public void setTokenRules(TokenRules tokenRules) {
			this.tokenRules = tokenRules;
		}

		public ParserOptions build() {
			return new ParserOptions(
				buildOpResolvers(this.unaryOpResolvers),
				buildOpResolvers(this.binaryOpResolvers),
				this.tokenRules);
		}
	}
}
