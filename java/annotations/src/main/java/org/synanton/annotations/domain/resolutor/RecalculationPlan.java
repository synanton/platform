package org.synanton.annotations.domain.resolutor;

import java.util.List;
import java.util.Set;

/** Resolutor's output (design §49): what needs recalculating and which projections it touches. */
public record RecalculationPlan(List<RecalculationWorkItem> workItems, Set<Projection> affectedProjections) {
}
