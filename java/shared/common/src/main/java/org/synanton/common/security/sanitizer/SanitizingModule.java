package org.synanton.common.security.sanitizer;

import com.fasterxml.jackson.databind.module.SimpleModule;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Jackson module that registers {@link SanitizingStringDeserializer} for {@link String}.
 */
public class SanitizingModule extends SimpleModule {

    @SuppressWarnings("this-escape")
    public SanitizingModule(HtmlSanitizerPolicyFactory factory, MeterRegistry meterRegistry) {
        super("synanton-sanitizing-module");
        addDeserializer(String.class, new SanitizingStringDeserializer(factory.policy(), meterRegistry));
    }
}
