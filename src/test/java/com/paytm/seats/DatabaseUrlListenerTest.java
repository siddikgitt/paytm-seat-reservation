package com.paytm.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class DatabaseUrlListenerTest {

    @Test
    void translatesRenderStyleUrl() {
        Map<String, Object> p = DatabaseUrlListener.toDatasourceProperties(
                "postgresql://seats_user:p%40ss@dpg-abc-a:5432/seats_db", "require");
        assertThat(p.get("spring.datasource.url")).isEqualTo("jdbc:postgresql://dpg-abc-a:5432/seats_db?sslmode=require");
        assertThat(p.get("spring.datasource.username")).isEqualTo("seats_user");
        assertThat(p.get("spring.datasource.password")).isEqualTo("p@ss");
    }

    @Test
    void keepsExplicitQueryAndDefaultsPort() {
        Map<String, Object> p = DatabaseUrlListener.toDatasourceProperties("postgres://u:p@host/db?sslmode=disable", "require");
        assertThat(p.get("spring.datasource.url")).isEqualTo("jdbc:postgresql://host:5432/db?sslmode=disable");
    }
}
