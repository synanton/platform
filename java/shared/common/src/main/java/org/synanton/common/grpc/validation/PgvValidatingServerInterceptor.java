package org.synanton.common.grpc.validation;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

import java.util.List;

/**
 * gRPC interceptor that applies PGV-style field rules before the service implementation runs.
 * When generated {@code ValidatorImpl} classes are absent, validation is skipped with a warning
 * unless {@code grpc.validation.strict} is enabled.
 */
public class PgvValidatingServerInterceptor implements ServerInterceptor {

    private final MeterRegistry meterRegistry;
    private final PgvRuleCatalogue catalogue;
    private final boolean enabled;
    private final boolean strict;

    public PgvValidatingServerInterceptor(PgvRuleCatalogue catalogue, MeterRegistry meterRegistry) {
        this(catalogue, meterRegistry, true, false);
    }

    public PgvValidatingServerInterceptor(
            PgvRuleCatalogue catalogue,
            MeterRegistry meterRegistry,
            boolean enabled,
            boolean strict
    ) {
        this.catalogue = catalogue;
        this.meterRegistry = meterRegistry != null ? meterRegistry : Metrics.globalRegistry;
        this.enabled = enabled;
        this.strict = strict;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);
        if (!enabled) {
            return delegate;
        }
        String service = call.getMethodDescriptor().getServiceName();
        String method = call.getMethodDescriptor().getBareMethodName();
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                List<PgvFieldViolation> violations = catalogue.validate(service, method, message);
                if (!violations.isEmpty()) {
                    PgvFieldViolation first = violations.getFirst();
                    meterRegistry.counter(
                            "grpc_validation_failed_total",
                            "service", service == null ? "unknown" : service,
                            "method", method == null ? "unknown" : method,
                            "field", first.field(),
                            "error", first.error()
                    ).increment();
                    call.close(
                            Status.INVALID_ARGUMENT.withDescription(first.message()),
                            headers
                    );
                    return;
                }
                super.onMessage(message);
            }
        };
    }

    public boolean isStrict() {
        return strict;
    }
}
