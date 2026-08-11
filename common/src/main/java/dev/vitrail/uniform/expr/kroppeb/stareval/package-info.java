/**
 * stareval, by Kroppeb, as Iris carries it: a small typed expression evaluator with implicit
 * casts and no lookahead. Fifty-eight files under this tree, taken from Iris at {@code b0ae41c}
 * and relocated here rather than rewritten. See NOTICE.
 * <p>
 * The relocation is the only sweeping change: the package moved under
 * {@code dev.vitrail.uniform.expr} so that nothing collides if Iris is installed next to us, and
 * the author's own name is kept in the path so that a reader knows at a glance which files are
 * ours and which are not.
 * <p>
 * Five edits beyond that, four of them because we do not have Iris around this code and one
 * because we do not have its build either. The same five are in NOTICE, and the two lists have to
 * say the same thing: this one is what a reader of the code finds, that one is what a reader of
 * the licence finds.
 * <ul>
 *     <li>{@code function.Type.convert} answers a {@link dev.vitrail.uniform.UniformShape}
 *     instead of Iris's GL uniform type enum, and gained the reverse direction, which is how an
 *     engine value gets read by an expression;</li>
 *     <li>{@code function.FunctionResolver.logAllFunctions}, which printed the whole table to
 *     standard output, became {@code names()}, which a test can compare against;</li>
 *     <li>fastutil's collections became the JDK's. The maps that were ordered stay ordered, the
 *     ones that were not become ordered, which only makes a log line reproducible;</li>
 *     <li>the two {@code log} overloads of {@code resolver.ExpressionResolver}, the one taking a
 *     {@code Supplier} and the one taking a plain {@code String}, are not carried. Nothing calls
 *     them here, and nothing called them in Iris either.</li>
 *     <li>{@code parser.StringReader} is final where Iris leaves it open. The one edit that is
 *     ours rather than the code's: this build compiles with {@code -Xlint:all -Werror}, and the
 *     class publishes {@code this} from its constructor, so javac refuses it open. Nothing here
 *     or in Iris subclasses it.</li>
 * </ul>
 */
package dev.vitrail.uniform.expr.kroppeb.stareval;
