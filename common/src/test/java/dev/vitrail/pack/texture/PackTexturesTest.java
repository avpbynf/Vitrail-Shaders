package dev.vitrail.pack.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.OptionPackFixture;
import dev.vitrail.pack.model.PackTexture;
import dev.vitrail.pack.model.PixelFormat;
import dev.vitrail.pack.model.PixelType;
import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.model.TextureStage;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds how a pack's own textures are read out of {@code shaders.properties} and checked against
 * the files behind them: the word count that decides what a value means, the refusals and which of
 * them still take the name over, the notes, and how a sampler is resolved.
 * <p>
 * Every pack here is a handful of tiny files written to a temporary directory.
 */
class PackTexturesTest {

	/** The sixteen bytes a PNG has to open on, then whatever padding a test wants after them. */
	private static byte[] png(int total) {
		ByteBuffer bytes = ByteBuffer.allocate(total);
		bytes.putLong(0x89504E470D0A1A0AL);
		bytes.putInt(13);
		bytes.putInt(0x49484452);

		return bytes.array();
	}

	private static PackTextures read(Path dir, String properties, Map<String, byte[]> files) throws IOException {
		return read(dir, properties, files, Map.of());
	}

	private static PackTextures read(Path dir, String properties, Map<String, byte[]> files,
			Map<String, String> defines) throws IOException {
		OptionPackFixture.write(dir, Map.of("shaders.properties", properties));
		for (Map.Entry<String, byte[]> file : files.entrySet()) {
			OptionPackFixture.writeBytes(dir, file.getKey(), file.getValue());
		}

		try (ShaderPackSource source = OptionPackFixture.open(dir)) {
			return PackTextures.read(ShaderProperties.parse(source), defines, source);
		}
	}

	private static PackTexture named(PackTextures textures, String sampler) {
		return textures.supplied().stream().filter(texture -> texture.sampler().equals(sampler)).findFirst()
				.orElseThrow(() -> new AssertionError(sampler + " is not supplied: " + textures.supplied()));
	}

	// ---- what a value means by how many words it has -----------------------------------------

	@Test
	void oneWordIsAPictureAndItIsNeitherFilteredNorClamped(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, "customTexture.noise=tex/noise.png\n", Map.of("tex/noise.png", png(32)));

		PackTexture noise = named(textures, "noise");
		assertEquals(Optional.empty(), noise.stage());
		assertEquals("tex/noise.png", noise.path());
		assertTrue(noise.png());
		assertFalse(noise.blur());
		assertFalse(noise.clamp());
		assertEquals(List.of(), textures.refused());
		assertEquals(List.of(), textures.notes());
	}

	@Test
	void sevenWordsAreATwoDimensionalBlobFilteredAndClampedByDefault(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, "customTexture.lut=tex/lut.bin TEXTURE_2D RG8 4 2 RG UNSIGNED_BYTE\n",
				Map.of("tex/lut.bin", new byte[16]));

		PackTexture lut = named(textures, "lut");
		PackTexture.Raw raw = lut.raw().orElseThrow();
		assertEquals(PackTexture.Shape.TEXTURE_2D, raw.shape());
		assertEquals(TargetFormat.RG8_UNORM, raw.internalFormat().used());
		assertEquals("RG8", raw.internalFormat().declared());
		assertEquals(4, raw.sizeX());
		assertEquals(2, raw.sizeY());
		assertEquals(0, raw.sizeZ());
		assertEquals(PixelFormat.RG, raw.pixelFormat());
		assertEquals(PixelType.UNSIGNED_BYTE, raw.pixelType());
		assertTrue(lut.blur());
		assertTrue(lut.clamp());
		assertEquals(16L, raw.bytes());
	}

	@Test
	void sixWordsAreALineAndEightAVolumeWhateverTheSecondWordSays(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, """
				customTexture.line=tex/line.bin WHATEVER R8 16 RED UNSIGNED_BYTE
				customTexture.vol=tex/vol.bin WHATEVER RGBA8 4 4 4 RGBA UNSIGNED_BYTE
				""", Map.of("tex/line.bin", new byte[16], "tex/vol.bin", new byte[256]));

		assertEquals(PackTexture.Shape.TEXTURE_1D, named(textures, "line").raw().orElseThrow().shape());
		assertEquals(16, named(textures, "line").raw().orElseThrow().sizeX());
		PackTexture.Raw volume = named(textures, "vol").raw().orElseThrow();
		assertEquals(PackTexture.Shape.TEXTURE_3D, volume.shape());
		assertEquals(4, volume.sizeZ());
		assertEquals(256L, volume.bytes());
	}

	@Test
	void aRectangleIsTheOtherThingSevenWordsCanMeanAndIsNotUploaded(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, """
				customTexture.rect=tex/rect.bin texture_rectangle RGBA8 4 4 RGBA UNSIGNED_BYTE
				customTexture.lie=tex/rect.bin TEXTURE_3D RGBA8 4 4 RGBA UNSIGNED_BYTE
				""", Map.of("tex/rect.bin", new byte[64]));

		assertEquals(PackTexture.Shape.TEXTURE_RECTANGLE, named(textures, "rect").raw().orElseThrow().shape());
		assertEquals(1, textures.supplied().size());
		assertEquals("declares a raw texture this cannot read", textures.refused().get(0).reason());
		assertEquals("lie", textures.refused().get(0).sampler());
	}

	@Test
	void aBlobThatCannotBeReadIsRefusedButTakesItsNameOver(@TempDir Path dir) throws IOException {
		Map<String, byte[]> files = Map.of("tex/lut.bin", new byte[16]);
		for (String value : List.of("tex/lut.bin TEXTURE_2D RG8 four 2 RG UNSIGNED_BYTE",
				"tex/lut.bin TEXTURE_2D RG8 0 2 RG UNSIGNED_BYTE", "tex/lut.bin TEXTURE_2D RG8 4 -2 RG UNSIGNED_BYTE",
				"tex/lut.bin TEXTURE_2D NOPE 4 2 RG UNSIGNED_BYTE", "tex/lut.bin TEXTURE_2D RG8 4 2 XYZ UNSIGNED_BYTE",
				"tex/lut.bin TEXTURE_2D RG8 4 2 RG UNSIGNEDBYTE")) {
			PackTextures textures = read(dir.resolve(Integer.toString(value.hashCode())), "customTexture.bad=" + value
					+ "\n", files);

			assertEquals(1, textures.refused().size(), value);
			assertEquals("bad", textures.refused().get(0).sampler(), value);
			assertEquals("declares a raw texture this cannot read", textures.refused().get(0).reason(), value);
			assertTrue(textures.supplied().isEmpty(), value);
			assertTrue(textures.suppliedTo(TextureStage.COMPOSITE).contains("bad"), value);
			assertTrue(textures.resolve(TextureStage.COMPOSITE, "bad").isEmpty(), value);
		}
	}

	@Test
	void anyOtherCountOfWordsIsRefusedWithTheCountAndTheNameIsTaken(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, """
				customTexture.two=tex/a.png TEXTURE_2D
				texture.deferred.colortex3=a b c
				customTexture.nine=a b c d e f g h i
				""", Map.of());

		List<PackTextures.Refused> refused = textures.refused();
		assertEquals(3, refused.size());
		assertEquals("holds 2 words, and the format gives a meaning to 1, 6, 7 and 8", refused.get(0).reason());
		assertEquals(Optional.empty(), refused.get(0).stage());
		assertEquals("two", refused.get(0).sampler());
		assertEquals("holds 3 words, and the format gives a meaning to 1, 6, 7 and 8", refused.get(1).reason());
		assertEquals(Optional.of(TextureStage.DEFERRED), refused.get(1).stage());
		assertEquals("colortex3", refused.get(1).sampler());
		assertEquals("holds 9 words, and the format gives a meaning to 1, 6, 7 and 8", refused.get(2).reason());
		assertEquals("nine", refused.get(2).sampler());
		assertEquals("customTexture.two=tex/a.png TEXTURE_2D: holds 2 words, and the format gives a meaning to "
				+ "1, 6, 7 and 8", refused.get(0).toString());
	}

	@Test
	void aNamespacedPathIsAResourceOfTheGameServedAsAPlainPictureWhateverElseTheLineSays(@TempDir Path dir)
			throws IOException {
		PackTextures textures = read(dir, """
				texture.gbuffers.gaux1=minecraft:textures/atlas/blocks.png
				customTexture.atlas=minecraft:textures/atlas/blocks.png TEXTURE_3D RGBA8 4 4 4 RGBA UNSIGNED_BYTE
				""", Map.of());

		PackTexture stage = textures.supplied().get(0);
		assertEquals("gaux1", stage.sampler());
		assertEquals(Optional.of(TextureStage.GBUFFERS), stage.stage());
		assertTrue(stage.gameResource());
		assertFalse(stage.blur());
		assertFalse(stage.clamp());

		PackTexture atlas = named(textures, "atlas");
		assertTrue(atlas.png(), "the shape and size the line announces went with it");
		assertTrue(atlas.gameResource());
		assertEquals(List.of(), textures.refused());
	}

	// ---- the key -----------------------------------------------------------------------------

	@Test
	void aKeyThatNamesNoStageNoSamplerOrAStageOutsideTheSevenTakesNothing(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, """
				texture.colortex3=tex/a.png
				texture.bogus.colortex3=tex/a.png
				texture.composite.=tex/a.png
				""", Map.of("tex/a.png", png(16)));

		assertEquals(List.of("names no stage to override the sampler in",
				"names the stage bogus, which is not one of the seven", "names no sampler"),
				textures.refused().stream().map(PackTextures.Refused::reason).toList());
		for (PackTextures.Refused refused : textures.refused()) {
			assertEquals("", refused.sampler());
			assertEquals(Optional.empty(), refused.stage());
		}
		assertTrue(textures.supplied().isEmpty());
		assertEquals(Set.of(), textures.suppliedTo(TextureStage.COMPOSITE));
	}

	@Test
	void aSamplerIsCutOffAtItsFirstDotInTheStageForm(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, """
				texture.composite.colortex3.extra=tex/a.png
				customTexture.dotted.name=tex/a.png
				""", Map.of("tex/a.png", png(16)));

		assertEquals("colortex3", textures.supplied().get(0).sampler());
		assertEquals("dotted.name", textures.supplied().get(1).sampler(), "a name of nobody else's keeps its dots");
	}

	// ---- the files behind the lines ----------------------------------------------------------

	@Test
	void aFileThePackDoesNotShipHandsTheNameBackAndSaysSo(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, """
				customTexture.gone=tex/none.png
				texture.composite.colortex3=tex/none.png
				""", Map.of());

		assertEquals(List.of("points at tex/none.png, which the pack does not ship, so gone reads what it read "
				+ "before", "points at tex/none.png, which the pack does not ship, so colortex3 reads what it read "
				+ "before"), textures.refused().stream().map(PackTextures.Refused::reason).toList());
		assertTrue(textures.refused().stream().allMatch(refused -> refused.sampler().isEmpty()));
		assertEquals(Set.of(), textures.suppliedTo(TextureStage.COMPOSITE));
	}

	@Test
	void aPathThatLeavesThePackKeepsItsNameAndReadsBlack(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, "texture.composite.colortex3=../outside.png\n", Map.of());

		PackTextures.Refused refused = textures.refused().get(0);
		assertEquals("points at ../outside.png, which is outside the pack", refused.reason());
		assertEquals("colortex3", refused.sampler());
		assertEquals(Optional.of(TextureStage.COMPOSITE), refused.stage());
		assertTrue(textures.suppliedTo(TextureStage.COMPOSITE).contains("colortex3"));
		assertTrue(textures.resolve(TextureStage.COMPOSITE, "colortex3").isEmpty());
	}

	@Test
	void aPictureIsAPngOnlyIfItsFirstSixteenBytesSayIt(@TempDir Path dir) throws IOException {
		byte[] badSignature = png(20);
		badSignature[0] = 0;
		byte[] badLength = png(20);
		badLength[11] = 12;
		byte[] badType = png(20);
		badType[15] = 'X';

		PackTextures textures = read(dir, """
				customTexture.short=tex/short.png
				customTexture.sig=tex/sig.png
				customTexture.len=tex/len.png
				customTexture.type=tex/type.png
				customTexture.fine=tex/fine.png
				""", Map.of("tex/short.png", new byte[15], "tex/sig.png", badSignature, "tex/len.png", badLength,
				"tex/type.png", badType, "tex/fine.png", png(16)));

		assertEquals(List.of(
				"tex/short.png is not a PNG the game reads (PNG header missing), so short reads what it read before",
				"tex/sig.png is not a PNG the game reads (bad PNG signature), so sig reads what it read before",
				"tex/len.png is not a PNG the game reads (bad length for the IHDR chunk), so len reads what it read "
						+ "before",
				"tex/type.png is not a PNG the game reads (bad type for the IHDR chunk), so type reads what it read "
						+ "before"), textures.refused().stream().map(PackTextures.Refused::reason).toList());
		assertEquals(List.of("fine"), textures.supplied().stream().map(PackTexture::sampler).toList());
		assertTrue(textures.refused().stream().allMatch(refused -> refused.sampler().isEmpty()));
	}

	@Test
	void aBlobShorterThanItAnnouncesIsRefusedButTakesItsNameAndALongerOneIsNoted(@TempDir Path dir)
			throws IOException {
		PackTextures textures = read(dir, """
				customTexture.short=tex/short.bin TEXTURE_2D RG8 4 2 RG UNSIGNED_BYTE
				customTexture.long=tex/long.bin TEXTURE_2D RG8 4 2 RG UNSIGNED_BYTE
				""", Map.of("tex/short.bin", new byte[15], "tex/long.bin", new byte[20]));

		assertEquals(1, textures.refused().size());
		assertEquals("tex/short.bin holds 15 bytes and the declaration asks for 16", textures.refused().get(0).reason());
		assertEquals("short", textures.refused().get(0).sampler());
		assertEquals(List.of("tex/long.bin holds 20 bytes for a declaration of 16, and the tail is not uploaded"),
				textures.notes());
		assertEquals(List.of("long"), textures.supplied().stream().map(PackTexture::sampler).toList());
	}

	@Test
	void aMcmetaBesideTheFileOverridesTheDefaultsAndAnythingItCannotReadLeavesThem(@TempDir Path dir)
			throws IOException {
		Map<String, byte[]> files = Map.of("tex/a.png", png(16), "tex/a.png.mcmeta",
				"{\"texture\": {\"blur\": true, \"clamp\": true}}".getBytes(StandardCharsets.UTF_8),
				"tex/b.bin", new byte[16], "tex/b.bin.mcmeta",
				"{\n  \"blur\" : false\n}".getBytes(StandardCharsets.UTF_8), "tex/c.png", png(16),
				"tex/c.png.mcmeta", "{\"blur\": \"true\", \"clamp\"}".getBytes(StandardCharsets.UTF_8));

		PackTextures textures = read(dir, """
				customTexture.a=tex/a.png
				customTexture.b=tex/b.bin TEXTURE_2D RG8 4 2 RG UNSIGNED_BYTE
				customTexture.c=tex/c.png
				""", files);

		assertTrue(named(textures, "a").blur());
		assertTrue(named(textures, "a").clamp());
		assertFalse(named(textures, "b").blur(), "a raw blob defaults to filtered and the file says no");
		assertTrue(named(textures, "b").clamp());
		assertFalse(named(textures, "c").blur());
		assertFalse(named(textures, "c").clamp());
	}

	// ---- what a device would take ------------------------------------------------------------

	@Test
	void aPlainBlobPastWhatATextureCanBeIsRefusedByNameAndTakesItsName(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, "customTexture.big=tex/big.bin TEXTURE_2D RGBA32F 16385 1 RGBA FLOAT\n",
				Map.of("tex/big.bin", new byte[16385 * 16]));

		assertEquals("tex/big.bin is declared 16385x1, which is past what a texture can be",
				textures.refused().get(0).reason());
		assertEquals("big", textures.refused().get(0).sampler());
		assertTrue(textures.supplied().isEmpty());
	}

	@Test
	void aVolumeIsMeasuredByTheAtlasItSpreadsToAndNotByItsOwnSize(@TempDir Path dir) throws IOException {
		// 4096 x 1 x 2048 bytes is only eight mebibytes of blob, a sparse file here.
		Path sparse = dir.resolve("shaders").resolve("tex").resolve("wide.bin");
		OptionPackFixture.write(dir, Map.of("shaders.properties",
				"customTexture.wide=tex/wide.bin TEXTURE_3D R8 4096 1 2048 RED UNSIGNED_BYTE\n"));
		Files.createDirectories(sparse.getParent());
		try (RandomAccessFile file = new RandomAccessFile(sparse.toFile(), "rw")) {
			file.setLength(4096L * 2048);
		}

		try (ShaderPackSource source = OptionPackFixture.open(dir)) {
			PackTextures textures = PackTextures.read(ShaderProperties.parse(source), Map.of(), source);

			assertEquals("tex/wide.bin lays out flat as 188508x135, which is past what a texture can be",
					textures.refused().get(0).reason());
			assertEquals("wide", textures.refused().get(0).sampler());
			assertTrue(textures.volumes().isEmpty());
			assertTrue(textures.supplied().isEmpty());
		}
	}

	@Test
	void aVolumeThatIsNotLaidOutFlatIsKeptAndSaidOutLoud(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, "customTexture.ivol=tex/v.bin TEXTURE_3D R8 2 2 2 RED BYTE\n",
				Map.of("tex/v.bin", new byte[8]));

		assertEquals(List.of("tex/v.bin is a volume of RED BYTE, which is not laid out flat here, so ivol stays a "
				+ "sampler3D and no program declaring it can be built"), textures.notes());
		assertEquals(1, textures.supplied().size());
		assertTrue(textures.volumes().isEmpty());
	}

	@Test
	void aVolumeIsServedAsAnAtlasUnderItsNameWithTheAddressingThePackAsked(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, """
				customTexture.vol=tex/vol.bin TEXTURE_3D RGBA8 4 4 4 RGBA UNSIGNED_BYTE
				customTexture.repeat=tex/vol.bin TEXTURE_3D RGBA8 2 2 2 RGBA UNSIGNED_BYTE
				""", Map.of("tex/vol.bin", new byte[256], "tex/vol.bin.mcmeta",
				"{\"clamp\": false}".getBytes(StandardCharsets.UTF_8)));

		Map<String, VolumeAtlas> volumes = textures.volumes();

		assertEquals(List.of("vol", "repeat"), List.copyOf(volumes.keySet()));
		assertEquals(4, volumes.get("vol").width());
		assertEquals(2, volumes.get("repeat").width());
		assertFalse(volumes.get("vol").clamp(), "the mcmeta said clamp is off");
		assertEquals(6, volumes.get("vol").tileStride());
	}

	// ---- how a sampler is resolved -----------------------------------------------------------

	private static final String MIXED = """
			customTexture.noise=tex/noise.png
			customTexture.vol=tex/vol.bin TEXTURE_3D RGBA8 2 2 2 RGBA UNSIGNED_BYTE
			customTexture.lut=tex/lut.bin TEXTURE_2D RG8 4 2 RG UNSIGNED_BYTE
			texture.composite.colortex3=tex/lut2.png
			texture.gbuffers.gaux4=tex/g.png
			texture.deferred.noise=tex/other.png
			customTexture.bad=a b c
			texture.prepare.colortex5=tex/missing.png
			""";

	private static PackTextures mixed(Path dir) throws IOException {
		return read(dir, MIXED, Map.of("tex/noise.png", png(16), "tex/vol.bin", new byte[32], "tex/lut.bin",
				new byte[16], "tex/lut2.png", png(16), "tex/g.png", png(16), "tex/other.png", png(16)));
	}

	@Test
	void aStageOverrideAnswersUnderBothSpellingsOfAColourTarget(@TempDir Path dir) throws IOException {
		PackTextures textures = mixed(dir);

		// colortex3 is also composite, and colortex7 is also gaux4: written under either, read under both.
		assertEquals("tex/lut2.png", textures.resolve(TextureStage.COMPOSITE, "colortex3").orElseThrow().path());
		assertEquals("tex/lut2.png", textures.resolve(TextureStage.COMPOSITE, "composite").orElseThrow().path());
		assertEquals("tex/g.png", textures.resolve(TextureStage.GBUFFERS, "gaux4").orElseThrow().path());
		assertEquals("tex/g.png", textures.resolve(TextureStage.GBUFFERS, "colortex7").orElseThrow().path());
		assertTrue(textures.resolve(TextureStage.DEFERRED, "colortex3").isEmpty(), "another stage");
		assertTrue(textures.resolve(TextureStage.COMPOSITE, "colortex4").isEmpty());
	}

	@Test
	void aNamedTextureAnswersInEveryStageAndTheStageFormIsAskedFirst(@TempDir Path dir) throws IOException {
		PackTextures textures = mixed(dir);

		assertEquals("tex/noise.png", textures.resolve(TextureStage.COMPOSITE, "noise").orElseThrow().path());
		assertEquals("tex/noise.png", textures.resolve(TextureStage.GBUFFERS, "noise").orElseThrow().path());
		assertEquals("tex/other.png", textures.resolve(TextureStage.DEFERRED, "noise").orElseThrow().path());
		assertEquals("tex/lut.bin", textures.resolve(TextureStage.PREPARE, "lut").orElseThrow().path());
	}

	@Test
	void aVolumeIsNeverTheAnswerForAPlainSamplerAndAlwaysServedByItsAtlas(@TempDir Path dir) throws IOException {
		PackTextures textures = mixed(dir);

		assertTrue(textures.resolve(TextureStage.COMPOSITE, "vol").isEmpty());
		assertTrue(textures.volumes().containsKey("vol"));
		assertFalse(textures.suppliedTo(TextureStage.COMPOSITE).contains("vol"));
	}

	@Test
	void aRefusedNameThatWasClaimedReadsBlackAndOneThatWasNotFallsBackToTheTarget(@TempDir Path dir)
			throws IOException {
		PackTextures textures = mixed(dir);

		// customTexture.bad has three words: refused and claimed everywhere.
		assertTrue(textures.suppliedTo(TextureStage.COMPOSITE).contains("bad"));
		assertTrue(textures.suppliedTo(TextureStage.GBUFFERS).contains("bad"));
		assertTrue(textures.resolve(TextureStage.COMPOSITE, "bad").isEmpty());
		// texture.prepare.colortex5 names a file the pack does not ship: dropped, and not claimed.
		assertFalse(textures.suppliedTo(TextureStage.PREPARE).contains("colortex5"));
		assertFalse(textures.suppliedTo(TextureStage.PREPARE).contains("gaux2"));
	}

	@Test
	void suppliedToListsTheNamesAStageTakesOverBothSpellingsIncluded(@TempDir Path dir) throws IOException {
		PackTextures textures = mixed(dir);

		assertEquals(Set.of("noise", "lut", "bad", "colortex3", "composite"),
				textures.suppliedTo(TextureStage.COMPOSITE));
		assertEquals(Set.of("noise", "lut", "bad", "colortex7", "gaux4"), textures.suppliedTo(TextureStage.GBUFFERS));
		assertEquals(Set.of("noise", "lut", "bad"), textures.suppliedTo(TextureStage.SETUP));
	}

	@Test
	void picturesToListsOnlyTheStageFormAPictureStandsOn(@TempDir Path dir) throws IOException {
		PackTextures textures = mixed(dir);

		assertEquals(Set.of("colortex3", "composite"), textures.picturesTo(TextureStage.COMPOSITE));
		assertEquals(Set.of("colortex7", "gaux4"), textures.picturesTo(TextureStage.GBUFFERS));
		assertEquals(Set.of("noise"), textures.picturesTo(TextureStage.DEFERRED));
		assertEquals(Set.of(), textures.picturesTo(TextureStage.SETUP));
	}

	@Test
	void aBlobOverridingAStageIsNotAPictureForTheDefaultSampler(@TempDir Path dir) throws IOException {
		PackTextures textures = read(dir, "texture.composite.colortex3=tex/lut.bin TEXTURE_2D RG8 4 2 RG UNSIGNED_BYTE\n",
				Map.of("tex/lut.bin", new byte[16]));

		assertEquals(Set.of(), textures.picturesTo(TextureStage.COMPOSITE));
		assertEquals(Set.of("colortex3", "composite"), textures.suppliedTo(TextureStage.COMPOSITE));
	}

	@Test
	void theLinesAreReadThroughThePacksOwnConditionals(@TempDir Path dir) throws IOException {
		String properties = """
				#ifdef HAVE_LUT
				customTexture.lut=tex/lut.bin TEXTURE_2D RG8 4 2 RG UNSIGNED_BYTE
				#else
				customTexture.plain=tex/noise.png
				#endif
				""";
		Map<String, byte[]> files = Map.of("tex/lut.bin", new byte[16], "tex/noise.png", png(16));

		PackTextures on = read(dir.resolve("on"), properties, files, Map.of("HAVE_LUT", ""));
		PackTextures off = read(dir.resolve("off"), properties, files);

		assertEquals(List.of("lut"), on.supplied().stream().map(PackTexture::sampler).toList());
		assertEquals(List.of("plain"), off.supplied().stream().map(PackTexture::sampler).toList());
	}

	@Test
	void aPackThatDeclaresNoneHasAnEmptySetOfEverything(@TempDir Path dir) throws IOException {
		for (PackTextures textures : List.of(PackTextures.empty(),
				read(dir, "screen=A\n", Map.of()))) {
			assertEquals(List.of(), textures.supplied());
			assertEquals(List.of(), textures.refused());
			assertEquals(List.of(), textures.notes());
			assertEquals(Map.of(), textures.volumes());
			assertEquals(Set.of(), textures.suppliedTo(TextureStage.COMPOSITE));
			assertEquals(Set.of(), textures.picturesTo(TextureStage.GBUFFERS));
			assertTrue(textures.resolve(TextureStage.COMPOSITE, "colortex0").isEmpty());
		}
	}

	@Test
	void theListsAreCopiesThatCannotBeChanged(@TempDir Path dir) throws IOException {
		PackTextures textures = mixed(dir);

		assertNotNull(textures.supplied());
		assertThrows(UnsupportedOperationException.class, () -> textures.supplied().clear());
		assertThrows(UnsupportedOperationException.class, () -> textures.refused().clear());
		assertThrows(UnsupportedOperationException.class, () -> textures.notes().add("x"));
		assertThrows(UnsupportedOperationException.class, () -> textures.volumes().clear());
	}
}
