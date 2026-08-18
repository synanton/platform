package org.synanton.gateway.acl;

import org.synanton.gateway.domain.Hit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AclInjector {

    public record AclScope(String subjectId, Set<String> groups, Set<String> allowedResourceIds) {}

    public List<String> injectMustClauses(AclScope scope) {
        LinkedHashSet<String> clauses = new LinkedHashSet<>();
        clauses.add("subject:" + scope.subjectId());
        scope.groups().forEach(group -> clauses.add("group:" + group));
        return List.copyOf(clauses);
    }

    public List<Hit> trim(List<Hit> hits, AclScope scope) {
        if (scope.allowedResourceIds() == null || scope.allowedResourceIds().isEmpty()) {
            return hits;
        }
        return hits.stream()
                .filter(hit -> scope.allowedResourceIds().contains(hit.contentRefId()))
                .toList();
    }
}
