package com.nexus360.report;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** All {@link ReportGenerator} beans by type (one per type, checked at startup). */
@Component
public class ReportGeneratorRegistry {

    private final Map<ReportType, ReportGenerator> generators = new EnumMap<>(ReportType.class);

    public ReportGeneratorRegistry(List<ReportGenerator> beans) {
        for (ReportGenerator g : beans) {
            if (generators.putIfAbsent(g.type(), g) != null) {
                throw new IllegalStateException("Two report generators for " + g.type() + ": " + generators.get(g.type()).getClass().getName()
                        + " and " + g.getClass().getName());
            }
        }
    }

    public Optional<ReportGenerator> find(ReportType type) {
        return Optional.ofNullable(generators.get(type));
    }

    public boolean available(ReportType type) {
        return generators.containsKey(type);
    }
}
