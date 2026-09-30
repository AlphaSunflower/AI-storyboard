package com.moon.moonagent.type;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JsonbStringTypeHandlerTest {

    @Test
    void bindsJsonTextAsPostgresJsonb() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        JsonbStringTypeHandler handler = new JsonbStringTypeHandler();

        handler.setNonNullParameter(statement, 1, "{\"mode\":\"STRICT\"}", null);

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(statement).setObject(eq(1), value.capture(), eq(Types.OTHER));
        assertThat(value.getValue()).isInstanceOf(PGobject.class);
        PGobject json = (PGobject) value.getValue();
        assertThat(json.getType()).isEqualTo("jsonb");
        assertThat(json.getValue()).isEqualTo("{\"mode\":\"STRICT\"}");
    }

    @Test
    void rejectsBlankJsonText() {
        JsonbStringTypeHandler handler = new JsonbStringTypeHandler();

        assertThatThrownBy(() -> handler.setNonNullParameter(
                mock(PreparedStatement.class), 1, "  ", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("must not be blank");
    }
}
