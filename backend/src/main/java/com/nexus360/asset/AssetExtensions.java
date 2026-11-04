package com.nexus360.asset;

import com.nexus360.common.Csv;
import com.nexus360.mywork.MyWorkContributor.Section;
import com.nexus360.portal.PortalContributor;
import com.nexus360.report.ReportFormat;
import com.nexus360.report.ReportGenerator;
import com.nexus360.report.ReportPdf;
import com.nexus360.report.ReportType;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Asset module contributions to the Release 3 extension points: the employee-portal section {@code myAssets} (hardware I hold and
 * licence seats assigned to me — the caller's own records only) and the ASSETS report (inventory with lifecycle status, holder,
 * warranty/licence end; PDF adds status counts and the warranties/licences ending in the period).
 */
@Configuration
@RequiredArgsConstructor
class AssetExtensions {

    private final AssetService assets;
    private final JdbcTemplate jdbc;

    @Bean
    PortalContributor myAssetsSection() {
        return new PortalContributor() {
            @Override
            public String key() {
                return "myAssets";
            }

            @Override
            public int order() {
                return 120;
            }

            @Override
            public Object contribute(Context ctx) {
                if (ctx.actor().isSystem()) {
                    return Section.empty();
                }
                List<Map<String, Object>> items = assets.mine(ctx.actor().id(), Math.max(ctx.limit(), 10)).stream().map(m -> {
                    Map<String, Object> out = new java.util.LinkedHashMap<>(m);
                    out.replaceAll((k, v) -> v instanceof Timestamp t ? t.toInstant() : v instanceof java.sql.Date d ? d.toLocalDate().toString() : v);
                    return out;
                }).toList();
                return new Section<>(assets.mineCount(ctx.actor().id()), items);
            }
        };
    }

    @Bean
    ReportGenerator assetsReport() {
        return new ReportGenerator() {
            @Override
            public ReportType type() {
                return ReportType.ASSETS;
            }

            @Override
            public String description() {
                return "Asset inventory with lifecycle status, holder and warranty or licence end; warranties and licences ending in the period";
            }

            @Override
            public Set<String> filterKeys() {
                return Set.of("departmentId");
            }

            @Override
            public byte[] generate(Request r) {
                Long dept = r.departmentId();
                List<Map<String, Object>> rows = jdbc.queryForList("select a.asset_tag, a.name, a.asset_type, a.status, a.manufacturer, a.model, "
                        + "a.serial_number, u.full_name as holder, d.code as department, a.location, a.purchase_date, a.purchase_cost, a.currency, "
                        + "a.warranty_expires, a.license_seats, (select count(*) from asset_assignments x where x.asset_id = a.id and x.returned_at is null) "
                        + "as seats_used, a.license_expires, c.name as ci, a.is_demo from assets a left join users u on u.id = a.assigned_to_id "
                        + "left join departments d on d.id = a.department_id left join configuration_items c on c.id = a.ci_id "
                        + "where (cast(? as bigint) is null or a.department_id = ?) order by a.asset_tag limit 10000", dept, dept);
                if (r.format() == ReportFormat.CSV) {
                    List<List<Object>> out = rows.stream().map(x -> Arrays.<Object>asList(x.get("asset_tag"), x.get("name"), x.get("asset_type"),
                            x.get("status"), x.get("manufacturer"), x.get("model"), x.get("serial_number"), x.get("holder"), x.get("department"),
                            x.get("location"), str(x.get("purchase_date")), str(x.get("purchase_cost")), x.get("currency"), str(x.get("warranty_expires")),
                            x.get("license_seats"), x.get("asset_type").equals("SOFTWARE_LICENSE") ? x.get("seats_used") : null, str(x.get("license_expires")),
                            x.get("ci"), x.get("is_demo"))).toList();
                    return Csv.bytes(List.of("asset_tag", "name", "asset_type", "status", "manufacturer", "model", "serial_number", "holder",
                            "department", "location", "purchase_date", "purchase_cost", "currency", "warranty_expires", "license_seats", "seats_used",
                            "license_expires", "ci", "demo"), out);
                }
                Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
                for (Asset.Status s : Asset.Status.values()) {
                    byStatus.put(s.name(), rows.stream().filter(x -> s.name().equals(x.get("status"))).count());
                }
                List<List<String>> ending = rows.stream().filter(x -> endsIn(x.get("warranty_expires"), r.from(), r.to())
                        || endsIn(x.get("license_expires"), r.from(), r.to())).limit(500).map(x -> List.of(str(x.get("asset_tag")), str(x.get("name")),
                        str(x.get("status")), x.get("warranty_expires") != null ? "Warranty " + str(x.get("warranty_expires"))
                                : "Licence " + str(x.get("license_expires")))).toList();
                List<ReportPdf.Table> tables = new ArrayList<>();
                List<List<String>> summary = new ArrayList<>();
                summary.add(List.of("Assets", String.valueOf(rows.size())));
                byStatus.forEach((k, v) -> summary.add(List.of(k, String.valueOf(v))));
                summary.add(List.of("Warranties or licences ending in the period", String.valueOf(ending.size())));
                tables.add(new ReportPdf.Table("Summary", List.of("Indicator", "Value"), new float[]{3, 2}, summary, true));
                tables.add(new ReportPdf.Table("Ending in the period", List.of("Tag", "Name", "Status", "Ends"), new float[]{2, 6, 2, 3}, ending, false));
                tables.add(new ReportPdf.Table("Inventory", List.of("Tag", "Name", "Type", "Status", "Holder"), new float[]{2, 5, 3, 2, 3},
                        rows.stream().limit(500).map(x -> List.of(str(x.get("asset_tag")), str(x.get("name")), str(x.get("asset_type")),
                                str(x.get("status")), str(x.get("holder")))).toList(), false));
                return ReportPdf.render(r.title(), List.of("Period: " + r.from() + " to " + r.to() + " (" + r.zone().getId() + ")",
                        "Filters: department = " + (dept == null ? "all departments" : "#" + dept)), tables);
            }
        };
    }

    private static boolean endsIn(Object date, LocalDate from, LocalDate to) {
        if (!(date instanceof java.sql.Date d)) {
            return false;
        }
        LocalDate x = d.toLocalDate();
        return !x.isBefore(from) && !x.isAfter(to);
    }

    private static String str(Object o) {
        return o == null ? "" : o instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(o);
    }
}
