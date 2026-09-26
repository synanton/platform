package org.synanton.storage.contract;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveProvidersTest {

    @Test
    void describeIsStableAndSorted() {
        ActiveProviders snapshot = ActiveProviders.of("c@1", "i@1", "i@1", "i@1");
        assertThat(snapshot.describe()).isEqualTo("admin=i@1, synquest=i@1, synvault=c@1, writer=i@1");
        assertThat(snapshot.byPort()).containsEntry("synvault", "c@1");
    }

    @Test
    void rejectsBareNames() {
        assertThatThrownBy(() -> ActiveProviders.of("cassandra", "i@1", "i@1", "i@1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
