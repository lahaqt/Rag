package com.example.authoringcoach.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConfigSecretCipherTests {
    @Test
    void encryptsWithAuthenticatedRandomNoncesAndDecrypts() {
        ConfigSecretCipher cipher = new ConfigSecretCipher("test-only-master-key");

        String first = cipher.encrypt("provider-secret");
        String second = cipher.encrypt("provider-secret");

        assertThat(first).isNotEqualTo(second).doesNotContain("provider-secret");
        assertThat(cipher.decrypt(first)).isEqualTo("provider-secret");
        assertThat(cipher.decrypt(second)).isEqualTo("provider-secret");
    }

    @Test
    void refusesToPersistSecretsWithoutTheDeploymentKey() {
        assertThatThrownBy(() -> new ConfigSecretCipher("").encrypt("secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTHORING_CONFIG_ENCRYPTION_KEY");
    }
}
