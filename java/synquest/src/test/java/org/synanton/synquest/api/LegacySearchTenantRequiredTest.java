package org.synanton.synquest.api;

import org.junit.jupiter.api.Test;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.synquest.api.dto.SearchRequest;
import org.synanton.synquest.service.SearchService;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The legacy /search endpoint no longer defaults tenant to "demo": a request
 * without an explicit tenant rejects. The real API (/v1/search) derives the
 * tenant from the verified identity and is unaffected.
 */
class LegacySearchTenantRequiredTest {

    @Test
    void searchWithoutExplicitTenantRejects() {
        SearchController controller = new SearchController(mock(SearchService.class));
        SearchRequest request = new SearchRequest(null, "q",
                null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> controller.search(request, new MockHttpServletRequest()))
                .isInstanceOf(MissingTenantContextException.class);
    }
}
