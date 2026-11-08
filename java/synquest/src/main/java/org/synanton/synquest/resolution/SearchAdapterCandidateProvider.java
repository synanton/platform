package org.synanton.synquest.resolution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.synanton.common.resolution.CanonicalEntityLookup;
import org.synanton.common.resolution.ResolutionCandidate;
import org.synanton.common.resolution.ResolutionCandidateProvider;
import org.synanton.common.resolution.ResolutionContext;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.synquest.search.SearchDocument;
import org.synanton.synquest.search.SearchDocumentType;
import org.synanton.synquest.search.SearchFilter;
import org.synanton.synquest.search.SearchQuery;
import org.synanton.synquest.search.SearchResult;
import org.synanton.synquest.service.SearchService;
import org.springframework.stereotype.Component;

/**
 * Thin, stateless candidate provider: entity search over the shared index, then
 * source-of-truth verification in Relix. It never decides if a candidate is
 * correct (that is the 4b.3 policy) and never treats infrastructure failure as
 * "no match".
 */
@Component
public final class SearchAdapterCandidateProvider implements ResolutionCandidateProvider {

    static final int CANDIDATE_LIMIT = 10;

    private final SearchService searchService;
    private final CanonicalEntityLookup entityLookup;

    public SearchAdapterCandidateProvider(SearchService searchService, CanonicalEntityLookup entityLookup) {
        this.searchService = searchService;
        this.entityLookup = entityLookup;
    }

    @Override
    public List<ResolutionCandidate> candidates(ResolutionContext context) {
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()
                || context.surfaceForm() == null || context.surfaceForm().isBlank()) {
            return List.of();
        }
        SearchQuery query = new SearchQuery(context.surfaceForm(), CANDIDATE_LIMIT,
                null, null, null, false, null, null, null, null);
        SearchFilter filter = new SearchFilter(context.tenantId(), SearchDocumentType.ENTITY);
        SearchResult result;
        try {
            result = searchService.search(query, filter);
        } catch (IOException | RuntimeException e) {
            if (e instanceof RetryableProcessingException retryable) {
                throw retryable;
            }
            throw new RetryableProcessingException("Entity candidate search failed", e);
        }

        List<ResolutionCandidate> candidates = new ArrayList<>();
        for (SearchDocument hit : result.documents()) {
            if (hit.documentType() != SearchDocumentType.ENTITY || hit.entityId() == null) {
                continue;
            }
            Optional<String> currentType;
            try {
                currentType = entityLookup.getActiveEntityType(context.tenantId(), hit.entityId());
            } catch (RetryableProcessingException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new RetryableProcessingException(
                        "Canonical entity lookup failed for " + hit.entityId(), e);
            }
            if (currentType.isEmpty()) {
                continue; // stale Lucene projection
            }
            String label = hit.entityLabel() == null ? hit.entityId() : hit.entityLabel();
            candidates.add(new ResolutionCandidate(
                    hit.entityId(), label, currentType.get(), hit.score(),
                    matchedField(context.surfaceForm(), hit), "RRF"));
        }
        return candidates;
    }

    private static String matchedField(String surfaceForm, SearchDocument hit) {
        String query = surfaceForm.toLowerCase(Locale.ROOT);
        String label = hit.entityLabel() == null ? "" : hit.entityLabel().toLowerCase(Locale.ROOT);
        if (!label.isEmpty() && (label.contains(query) || query.contains(label))) {
            return "label";
        }
        if (hit.entityAliases() != null) {
            for (String alias : hit.entityAliases()) {
                String normalized = alias == null ? "" : alias.toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty() && (normalized.contains(query) || query.contains(normalized))) {
                    return "alias";
                }
            }
        }
        String description = hit.entityDescription() == null ? ""
                : hit.entityDescription().toLowerCase(Locale.ROOT);
        if (!description.isEmpty() && description.contains(query)) {
            return "description";
        }
        return "text";
    }
}
