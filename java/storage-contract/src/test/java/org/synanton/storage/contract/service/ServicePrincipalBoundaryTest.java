package org.synanton.storage.contract.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Keeps capability-bearing principals creatable only through issuance. */
class ServicePrincipalBoundaryTest {

    @Test
    void canonicalConstructorIsNotPublic() {
        assertThat(java.util.Arrays.stream(ServicePrincipal.class.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers())))
                .as("ServicePrincipal constructors must not be public; use fromClaims")
                .isEmpty();
    }

    @Test
    void productionCallersOfTheIssuanceFactoryAreRestricted() throws Exception {
        Path javaRoot = Path.of("..").toAbsolutePath().normalize();
        List<String> offenders = new ArrayList<>();
        try (var modules = Files.list(javaRoot)) {
            for (Path module : modules.toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (var sources = Files.walk(main)) {
                    for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String relative = javaRoot.relativize(source).toString();
                        if (relative.contains("security/src/main/java/org/synanton/security/issuance/")
                                || relative.contains("storage-contract/src/main/java/org/synanton/storage/contract/service/")) {
                            continue;
                        }
                        String text = Files.readString(source);
                        if (text.contains("ServicePrincipal.fromClaims")
                                || text.contains("new ServicePrincipal(")) {
                            offenders.add(relative);
                        }
                    }
                }
            }
        }
        assertThat(offenders)
                .as("capability-bearing principal construction outside issuance")
                .isEmpty();
    }
}
