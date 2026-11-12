package org.synanton.relix.adapter.out.graph.memory;

import org.synanton.common.resolution.GraphCapabilities;
import org.synanton.relix.graph.AbstractGraphContractTest;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.GraphReadPort;
import org.synanton.relix.graph.GraphWritePort;

import static org.mockito.Mockito.mock;

class InMemoryGraphContractTest extends AbstractGraphContractTest {

    private final InMemoryGraphConnector connector =
            new InMemoryGraphConnector(mock(GraphLoader.class));

    @Override
    protected GraphReadPort readPort() {
        return connector;
    }

    @Override
    protected GraphWritePort writePort() {
        return connector;
    }

    @Override
    protected GraphCapabilities capabilities() {
        return new GraphCapabilities(false, false, true, true, false);
    }
}
