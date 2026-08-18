package org.synanton.controlplane.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastEngineTest {

    @Test
    void shouldWarnWhenFewerThanSevenDaysRemain() {
        ForecastEngine.Forecast forecast = new ForecastEngine().forecast(930, 10, 1000);
        assertThat(forecast.daysRemaining()).isEqualTo(7.0);
    }
}
