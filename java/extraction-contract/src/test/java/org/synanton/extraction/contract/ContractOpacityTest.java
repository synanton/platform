package org.synanton.extraction.contract;

import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.Test;
import synanton.extraction.v1.ExtractionPayloadProto;
import synanton.extraction.v1.ExtractionServiceProto;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the black-box invariants (§67.2, §67.16) against the compiled descriptors.
 *
 * <p>Reviewers forget; a test does not. This walks every message, field, and enum value in the
 * contract and fails if any of them names a processor, library, or piece of topology. Without it,
 * the first "just add a worker_pool hint for debugging" turns an implementation detail into a
 * contractual dependency that cannot be removed.
 */
class ContractOpacityTest {

    /**
     * Terms that would leak implementation or topology into the contract.
     *
     * <p>{@code page} is deliberately absent: a page number is document structure, not topology.
     */
    private static final List<String> FORBIDDEN_TERMS = List.of(
            // processors and libraries
            "opendataloader", "tika", "tesseract", "poppler", "pdfbox", "whisper", "vllm", "ffmpeg",
            // topology and scheduling internals
            "worker", "pool", "queue_name", "broker", "kafka", "redis", "kubernetes", "k8s",
            "pod", "node_name", "hostname", "replica", "shard", "cluster_name",
            // hardware
            "gpu", "cuda", "device_id", "cpu_core", "accelerator",
            // scheduler coupling
            "equalix");

    private static List<Descriptors.FileDescriptor> contractFiles() {
        return List.of(
                ExtractionServiceProto.getDescriptor(),
                ExtractionPayloadProto.getDescriptor());
    }

    /** Every identifier the contract exposes: messages, fields, enums, enum values, RPCs. */
    private static List<String> allIdentifiers() {
        List<String> identifiers = new ArrayList<>();
        for (Descriptors.FileDescriptor file : contractFiles()) {
            for (Descriptors.ServiceDescriptor service : file.getServices()) {
                identifiers.add(service.getName());
                service.getMethods().forEach(method -> identifiers.add(method.getName()));
            }
            file.getEnumTypes().forEach(enumType -> collectEnum(enumType, identifiers));
            file.getMessageTypes().forEach(message -> collectMessage(message, identifiers));
        }
        return identifiers;
    }

    private static void collectMessage(Descriptors.Descriptor message, List<String> identifiers) {
        identifiers.add(message.getName());
        message.getFields().forEach(field -> identifiers.add(field.getName()));
        message.getEnumTypes().forEach(enumType -> collectEnum(enumType, identifiers));
        message.getNestedTypes().forEach(nested -> collectMessage(nested, identifiers));
    }

    private static void collectEnum(Descriptors.EnumDescriptor enumType, List<String> identifiers) {
        identifiers.add(enumType.getName());
        enumType.getValues().forEach(value -> identifiers.add(value.getName()));
    }

    @Test
    void shouldNotNameAnyProcessorLibraryOrTopologyElement() {
        List<String> offenders = new ArrayList<>();
        for (String identifier : allIdentifiers()) {
            String lower = identifier.toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_TERMS) {
                if (lower.contains(forbidden)) {
                    offenders.add(identifier + " contains '" + forbidden + "'");
                }
            }
        }
        assertThat(offenders)
                .describedAs("the contract must not name a processor, library, or topology element")
                .isEmpty();
    }

    @Test
    void shouldDeclareBothContractFilesInTheSamePackage() {
        assertThat(contractFiles())
                .allSatisfy(file -> assertThat(file.getPackage()).isEqualTo("synanton.extraction.v1"));
    }

    @Test
    void shouldExposeExactlyOneService() {
        // A second service would create a second boundary to keep topology-independent.
        List<Descriptors.ServiceDescriptor> services = contractFiles().stream()
                .flatMap(file -> file.getServices().stream())
                .toList();

        assertThat(services).hasSize(1);
        assertThat(services.getFirst().getName()).isEqualTo("ExtractionService");
    }

    @Test
    void shouldStartEveryEnumWithAZeroUnspecifiedValue() {
        List<String> offenders = new ArrayList<>();
        for (Descriptors.FileDescriptor file : contractFiles()) {
            List<Descriptors.EnumDescriptor> enums = new ArrayList<>(file.getEnumTypes());
            file.getMessageTypes().forEach(message -> enums.addAll(message.getEnumTypes()));

            for (Descriptors.EnumDescriptor enumType : enums) {
                Descriptors.EnumValueDescriptor zero = enumType.findValueByNumber(0);
                if (zero == null || !zero.getName().endsWith("_UNSPECIFIED")) {
                    offenders.add(enumType.getName());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void shouldKeepOptionsTriStateSoUnsetDiffersFromFalse() {
        // §8: "unset - plane decides" must be distinguishable from "explicitly false - do not".
        // A plain proto3 bool cannot express that, so each option field must be optional.
        Descriptors.Descriptor options = ExtractionServiceProto.getDescriptor()
                .findMessageTypeByName("ExtractionOptions");

        assertThat(options).isNotNull();
        assertThat(options.getFields())
                .describedAs("every extraction option must be optional (explicit presence)")
                .allSatisfy(field -> assertThat(field.hasPresence()).isTrue());
    }
}
