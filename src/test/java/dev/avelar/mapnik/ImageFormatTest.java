package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ImageFormatTest {
    @Test
    void writesWhatMapnikReads() {
        assertEquals("png", ImageFormat.png().toString());
        assertEquals("png8:c=64:m=h", ImageFormat.png8().colors(64).quantizer(ImageFormat.Quantizer.HEXTREE).toString());
        assertEquals("png8:t=1:g=2.2:z=9:s=filtered", ImageFormat.png8().transparency(ImageFormat.Transparency.BINARY)
            .gamma(2.2).compression(9).strategy(ImageFormat.PngStrategy.FILTERED).toString());
        assertEquals("png:z=-1", ImageFormat.png().compression(-1).toString());
        assertEquals("jpeg:quality=85", ImageFormat.jpeg(85).toString());
        assertEquals("webp:quality=75:method=6", ImageFormat.webp().quality(75).effort(6).toString());
        assertEquals("webp:lossless=1:alpha=false", ImageFormat.webp().lossless().alpha(false).toString());
        assertEquals("tiff:compression=lzw:method=stripped:rows_per_strip=16",
            ImageFormat.tiff().compression(ImageFormat.TiffCompression.LZW).layout(ImageFormat.TiffLayout.STRIPPED).rowsPerStrip(16).toString());
        assertEquals("tiff:compression=adobedeflate:zlevel=9",
            ImageFormat.tiff().compression(ImageFormat.TiffCompression.ADOBE_DEFLATE).zlevel(9).toString());
        assertEquals("webp:image_hint=2", ImageFormat.webp().option("image_hint", "2").toString());
    }

    @Test
    void refusesValuesMapnikWouldRefuse() {
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.png8().colors(0));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.png8().colors(257));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.png8().gamma(-1));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.png8().gamma(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.png().compression(10));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.jpeg(101));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.jpeg(-1));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.webp().quality(100.5));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.webp().effort(7));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.tiff().zlevel(10));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.tiff().rowsPerStrip(-1));
    }

    @Test
    void optionsBelongToTheirFormat() {
        assertThrows(IllegalStateException.class, () -> ImageFormat.png().colors(16));
        assertThrows(IllegalStateException.class, () -> ImageFormat.png().quantizer(ImageFormat.Quantizer.OCTREE));
        assertThrows(IllegalStateException.class, () -> ImageFormat.jpeg(80).lossless());
        assertThrows(IllegalStateException.class, () -> ImageFormat.webp().compression(5));
        assertThrows(IllegalStateException.class, () -> ImageFormat.tiff().strategy(ImageFormat.PngStrategy.RLE));
        assertThrows(IllegalStateException.class, () -> ImageFormat.png8().zlevel(3));
    }

    @Test
    void aFreeFormOptionCannotSmuggleInTheSeparators() {
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.png().option("c", "1:m=o"));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.png().option("c=1", "2"));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.png().option("", "2"));
    }
}
