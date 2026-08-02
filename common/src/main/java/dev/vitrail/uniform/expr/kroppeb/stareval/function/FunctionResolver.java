package dev.vitrail.uniform.expr.kroppeb.stareval.function;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class FunctionResolver {
	private final Map<String, Map<Type, List<TypedFunction>>> functions;
	// TODO: instead of a suplier we could use an int->function to make varargs
	private final Map<String, Map<Type, List<Supplier<? extends TypedFunction>>>> dynamicFunctions;

	public FunctionResolver(
		Map<String, Map<Type, List<TypedFunction>>> functions,
		Map<String, Map<Type, List<Supplier<? extends TypedFunction>>>> dynamicFunctions) {
		this.functions = functions;
		this.dynamicFunctions = dynamicFunctions;
	}

	public List<? extends TypedFunction> resolve(String name, Type returnType) {
		Map<Type, List<TypedFunction>> normal = this.functions.get(name);
		Map<Type, List<Supplier<? extends TypedFunction>>> dynamic = this.dynamicFunctions.get(name);
		List<? extends TypedFunction> u = null;

		if (normal == null && dynamic == null)
			throw new RuntimeException("No such function: " + name);
		if (normal != null)
			u = normal.get(returnType);
		if (dynamic != null) {
			List<Supplier<? extends TypedFunction>> p = dynamic.get(returnType);
			if (p != null) {
				List<? extends TypedFunction> uDynamic = p.stream().map(Supplier::get).collect(Collectors.toList());
				if (u == null) {
					u = uDynamic;
				} else {
					List<TypedFunction> newU = new ArrayList<>(u.size() + uDynamic.size());
					newU.addAll(u);
					newU.addAll(uDynamic);
					u = newU;
				}
			}

		}

		if (u == null)
			return Collections.emptyList();
		return u;
	}

	/**
	 * Every name the table answers to, the internal accessors included. Replaces the upstream
	 * method that printed the same thing to standard output: a list a test can compare against is
	 * what proves the grammar is complete, and a print is not.
	 */
	public List<String> names() {
		List<String> names = new ArrayList<>(this.functions.keySet());
		for (String name : this.dynamicFunctions.keySet()) {
			if (!names.contains(name)) {
				names.add(name);
			}
		}
		Collections.sort(names);

		return names;
	}

	public static class Builder {
		private final Map<String, List<TypedFunction>> functions = new LinkedHashMap<>();
		private final Map<String, Map<Type, List<Supplier<? extends TypedFunction>>>> dynamicFunctions = new LinkedHashMap<>();

		public <T extends TypedFunction> void add(String name, T function) {
			this.addFunction(name, function);
		}

		public <T extends TypedFunction> void addDynamic(String name, Type returnType, Supplier<T> function) {
			this.addDynamicFunction(name, returnType, function);
		}

		public void addDynamicFunction(String name, Type returnType, Supplier<? extends TypedFunction> function) {
			this.dynamicFunctions
				.computeIfAbsent(name, (n) -> new LinkedHashMap<>())
				.computeIfAbsent(returnType, (n) -> new ArrayList<>())
				.add(function);
		}

		public void addFunction(String name, TypedFunction function) {
			this.functions.computeIfAbsent(name, (n) -> new ArrayList<>()).add(function);
		}

		public FunctionResolver build() {
			Map<String, Map<Type, List<TypedFunction>>> functions = new LinkedHashMap<>();
			for (Map.Entry<String, List<TypedFunction>> entry : this.functions.entrySet()) {
				Map<Type, List<TypedFunction>> typeMap = new LinkedHashMap<>();
				for (TypedFunction function : entry.getValue()) {
					typeMap.computeIfAbsent(function.getReturnType(), i -> new ArrayList<>())
						.add(function);
				}
				functions.put(entry.getKey(), typeMap);
			}

			return new FunctionResolver(functions, this.dynamicFunctions);
		}
	}

}
