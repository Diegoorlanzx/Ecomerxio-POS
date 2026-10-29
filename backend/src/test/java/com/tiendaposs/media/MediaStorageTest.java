package com.tiendaposs.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaStorageTest {

    private static final String PNG = "data:image/png;base64,iVBORw0KGgo=";

    @Test
    void sinLlavesQuedaApagadoYGuardaLaImagenComoLlega() {
        MediaStorage m = new MediaStorage("", "", "", "", "", "pos");
        assertThat(m.enabled()).isFalse();
        assertThat(m.publicOrigin()).isNull();
        assertThat(m.save(7L, "productos", PNG)).isEqualTo(PNG);
        assertThat(m.isStored(7L, "https://media.ecomerxio.com/pos/7/productos/a.webp")).isFalse();
    }

    @Test
    void soloAceptaUrlsDelMismoNegocio() {
        MediaStorage m = new MediaStorage("https://cuenta.r2.cloudflarestorage.com", "ecommerce-media", "k", "s",
                "https://media.ecomerxio.com/", "/pos/");
        assertThat(m.enabled()).isTrue();
        assertThat(m.publicOrigin()).isEqualTo("https://media.ecomerxio.com");
        assertThat(m.isStored(7L, "https://media.ecomerxio.com/pos/7/productos/a.webp")).isTrue();
        assertThat(m.isStored(7L, "https://media.ecomerxio.com/pos/70/productos/a.webp")).isFalse();
        assertThat(m.isStored(7L, "https://media.ecomerxio.com/pos/8/productos/a.webp")).isFalse();
        assertThat(m.isStored(7L, "https://media.ecomerxio.com/pos/7/../8/a.webp")).isFalse();
        assertThat(m.isStored(7L, "https://otro.com/pos/7/a.webp")).isFalse();
        // Una URL ya subida no se vuelve a subir.
        String url = "https://media.ecomerxio.com/pos/7/logo/a.png";
        assertThat(m.save(7L, "logo", url)).isEqualTo(url);
    }

    @Test
    void decodificaLaDataUrl() {
        MediaStorage.Decoded d = MediaStorage.decode(PNG);
        assertThat(d.contentType()).isEqualTo("image/png");
        assertThat(d.bytes()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }
}
