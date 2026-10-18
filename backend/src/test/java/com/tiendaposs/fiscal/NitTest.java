package com.tiendaposs.fiscal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NitTest {

    @Test
    void computesTheVerificationDigit() {
        assertThat(Nit.dv("800197268")).isEqualTo('4');      // DIAN
        assertThat(Nit.dv("800.197.268")).isEqualTo('4');    // con puntos
        assertThat(Nit.matches("800197268", "4")).isTrue();
        assertThat(Nit.matches("800197268", "5")).isFalse();
        assertThat(Nit.dv("")).isNull();
        assertThat(Nit.dv("1234567890123456")).isNull();     // más de 15 dígitos
    }
}
