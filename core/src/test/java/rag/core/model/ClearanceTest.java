package rag.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static rag.core.model.Clearance.INTERNAL;
import static rag.core.model.Clearance.PUBLIC;
import static rag.core.model.Clearance.RESTRICTED;

import org.junit.jupiter.api.Test;

class ClearanceTest {

    @Test
    void visibleIsCumulative() {
        assertThat(INTERNAL.visible()).containsExactlyInAnyOrder(PUBLIC, INTERNAL);
        assertThat(PUBLIC.visible()).containsExactly(PUBLIC);
        assertThat(RESTRICTED.visible()).containsExactlyInAnyOrder(PUBLIC, INTERNAL, RESTRICTED);
    }

    @Test
    void parseReadsLowerCaseWireForm() {
        assertThat(Clearance.parse("internal")).isEqualTo(INTERNAL);
        assertThat(RESTRICTED.wire()).isEqualTo("restricted");
    }

    @Test
    void parseRejectsUnknown() {
        assertThatThrownBy(() -> Clearance.parse("secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }
}
