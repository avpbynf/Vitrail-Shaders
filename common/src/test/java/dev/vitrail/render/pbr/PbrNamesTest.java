package dev.vitrail.render.pbr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vitrail.pack.option.EngineDefines;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import org.junit.jupiter.api.Test;

/**
 * Where the material maps are looked for and which convention the resource pack says they are in:
 * the {@code _n} and {@code _s} that go before an extension, the exception for the CIT sprites, and
 * the one line of {@code optifine/texture.properties} that switches labPBR on.
 * <p>
 * The two path builders are private, since nothing outside their class asks for one, so they are
 * reached by reflection; the format reader is package private and is handed a resource manager that
 * is a proxy answering the one question it is asked.
 */
class PbrNamesTest {

	// -- the format a resource pack declares -------------------------------------------------

	/** A manager holding one file under the path the engine asks for, or none at all. */
	private static ResourceManager holding(byte[] file) {
		return (ResourceManager) Proxy.newProxyInstance(PbrNamesTest.class.getClassLoader(),
				new Class<?>[] {ResourceManager.class}, (proxy, method, args) -> {
					if (method.getName().equals("getResource")) {
						Identifier asked = (Identifier) args[0];
						assertEquals("minecraft:optifine/texture.properties", asked.toString());

						return file == null
								? Optional.empty()
								: Optional.of(new Resource(null, () -> new ByteArrayInputStream(file)));
					}

					throw new UnsupportedOperationException(method.getName());
				});
	}

	private static EngineDefines.TextureFormat formatOf(String properties) {
		return PbrAtlases.format(holding(properties.getBytes(StandardCharsets.ISO_8859_1)));
	}

	@Test
	void aPackThatShipsNoFileDeclaresNothing() {
		assertNull(PbrAtlases.format(holding(null)));
	}

	@Test
	void labPbrWithAVersionIsKeptAsTheNameAndTheVersion() {
		assertEquals(new EngineDefines.TextureFormat("lab-pbr", "1.3"), formatOf("format=lab-pbr/1.3\n"));
		assertEquals(new EngineDefines.TextureFormat("lab-pbr", "1.2"), formatOf("format=lab-pbr/1.2"));
	}

	@Test
	void labPbrWithoutAVersionIsStillLabPbrAndHasNoVersion() {
		assertEquals(new EngineDefines.TextureFormat("lab-pbr", null), formatOf("format=lab-pbr\n"));
		assertEquals(new EngineDefines.TextureFormat("lab-pbr", null), formatOf("format=lab-pbr/\n"));
		assertEquals(new EngineDefines.TextureFormat("lab-pbr", null), formatOf("format=lab-pbr//x\n"),
				"an empty field between two slashes is no version");
	}

	@Test
	void onlyTheFieldBetweenTheFirstTwoSlashesIsTheVersion() {
		assertEquals(new EngineDefines.TextureFormat("lab-pbr", "1.3"), formatOf("format=lab-pbr/1.3/extra/more\n"));
	}

	@Test
	void spacesAroundTheDeclarationAreTrimmedAndTheKeyMayHaveSomeToo() {
		assertEquals(new EngineDefines.TextureFormat("lab-pbr", "1.3"), formatOf("format = lab-pbr/1.3   \n"));
		assertEquals(new EngineDefines.TextureFormat("lab-pbr", "1.3"), formatOf("# a comment\n\nformat:lab-pbr/1.3\n"));
	}

	@Test
	void anyOtherConventionOrNoDeclarationAtAllMeansNoFormat() {
		assertNull(formatOf("format=old-pbr/1.0\n"));
		assertNull(formatOf("format=LAB-PBR/1.3\n"), "the name is matched exactly");
		assertNull(formatOf("format=lab-pbr-extended/1.3\n"));
		assertNull(formatOf("format=\n"));
		assertNull(formatOf("nothing=here\n"));
		assertNull(formatOf(""));
		assertNull(formatOf("\n\n# format=lab-pbr/1.3\n"), "a commented line declares nothing");
	}

	@Test
	void aFileThatIsNotAPropertiesFileOrCannotBeReadMeansNoFormatAndNoFailure() {
		assertNull(formatOf("format=lab-pbr/1.3\\uZZZZ\n"), "a malformed escape throws inside Properties");
		assertNull(PbrAtlases.format(holding(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 1, 2, 3})));

		ResourceManager unreadable = (ResourceManager) Proxy.newProxyInstance(
				PbrNamesTest.class.getClassLoader(), new Class<?>[] {ResourceManager.class},
				(proxy, method, args) -> Optional.of(new Resource(null, () -> {
					throw new IOException("cannot open");
				})));
		assertNull(PbrAtlases.format(unreadable));
	}

	@Test
	void theLastLineOfARepeatedKeyWins() {
		assertEquals(new EngineDefines.TextureFormat("lab-pbr", "1.3"),
				formatOf("format=old-pbr\nformat=lab-pbr/1.3\n"));
		assertNull(formatOf("format=lab-pbr/1.3\nformat=old-pbr\n"));
	}

	// -- where the maps of an atlas sprite live ----------------------------------------------

	private static Identifier locationOf(String sprite, PbrMap map) throws ReflectiveOperationException {
		Method method = PbrAtlas.class.getDeclaredMethod("location", Identifier.class, PbrMap.class);
		method.setAccessible(true);
		try {
			return (Identifier) method.invoke(null, Identifier.parse(sprite), map);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e.getCause());
		}
	}

	@Test
	void anAtlasMapLivesBesideItsSpriteUnderTexturesWithTheSuffixBeforePng() throws ReflectiveOperationException {
		assertEquals("minecraft:textures/block/stone_n.png", locationOf("minecraft:block/stone", PbrMap.NORMALS).toString());
		assertEquals("minecraft:textures/block/stone_s.png", locationOf("minecraft:block/stone", PbrMap.SPECULAR).toString());
		assertEquals("somemod:textures/item/wand_n.png", locationOf("somemod:item/wand", PbrMap.NORMALS).toString());
	}

	@Test
	void aDotInTheSpriteNameDoesNotMoveTheSuffix() throws ReflectiveOperationException {
		assertEquals("minecraft:textures/block/a.b_n.png", locationOf("minecraft:block/a.b", PbrMap.NORMALS).toString());
	}

	@Test
	void aCitSpriteKeepsItsOwnFolderAndGetsNoTexturesPrefix() throws ReflectiveOperationException {
		assertEquals("minecraft:optifine/cit/sword_s.png",
				locationOf("minecraft:optifine/cit/sword", PbrMap.SPECULAR).toString());
		assertEquals("citmod:optifine/cit/a/b_n.png", locationOf("citmod:optifine/cit/a/b", PbrMap.NORMALS).toString());
		// only the folder counts, not a name that merely starts alike
		assertEquals("minecraft:textures/optifine/citrus_n.png",
				locationOf("minecraft:optifine/citrus", PbrMap.NORMALS).toString());
	}

	// -- where the maps of a plain texture live ----------------------------------------------

	private static Identifier besideOf(String texture, PbrMap map) throws ReflectiveOperationException {
		Method method = PbrTextures.class.getDeclaredMethod("beside", Identifier.class, PbrMap.class);
		method.setAccessible(true);
		try {
			return (Identifier) method.invoke(null, Identifier.parse(texture), map);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e.getCause());
		}
	}

	@Test
	void aPlainTexturesMapGoesBeforeTheExtensionAndNeverAfterIt() throws ReflectiveOperationException {
		assertEquals("minecraft:textures/entity/creeper/creeper_n.png",
				besideOf("minecraft:textures/entity/creeper/creeper.png", PbrMap.NORMALS).toString());
		assertEquals("minecraft:textures/entity/creeper/creeper_s.png",
				besideOf("minecraft:textures/entity/creeper/creeper.png", PbrMap.SPECULAR).toString());
		assertEquals("mod:textures/armor/iron_layer_1_n.png",
				besideOf("mod:textures/armor/iron_layer_1.png", PbrMap.NORMALS).toString());
	}

	@Test
	void aPathWithNoExtensionGetsTheSuffixAtTheEnd() throws ReflectiveOperationException {
		assertEquals("minecraft:textures/entity/creeper_n", besideOf("minecraft:textures/entity/creeper", PbrMap.NORMALS).toString());
	}

	@Test
	void aDotInAFolderIsNotAnExtension() throws ReflectiveOperationException {
		assertEquals("minecraft:textures/a.b/creeper_s", besideOf("minecraft:textures/a.b/creeper", PbrMap.SPECULAR).toString());
	}

	@Test
	void onlyTheLastDotOfTheFileNameStartsTheExtension() throws ReflectiveOperationException {
		assertEquals("minecraft:textures/x.tar_n.png", besideOf("minecraft:textures/x.tar.png", PbrMap.NORMALS).toString());
	}

	@Test
	void aFileNameThatIsOnlyAnExtensionGetsTheSuffixInFrontOfIt() throws ReflectiveOperationException {
		assertEquals("minecraft:textures/_n.png", besideOf("minecraft:textures/.png", PbrMap.NORMALS).toString());
	}
}
