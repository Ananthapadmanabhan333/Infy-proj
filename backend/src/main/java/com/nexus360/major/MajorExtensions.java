package com.nexus360.major;

import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalHandler;
import com.nexus360.mywork.MyWorkContributor;
import com.nexus360.security.Actor;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Major incident and review contributions: the approval handler for type {@code PIR} (post-incident reviews are approved by the PIR
 * approvers through the approval engine) and the My Work section {@code actionItems} (open follow-ups of major incidents and
 * corrective/preventive actions of reviews assigned to me, soonest due first).
 */
@Configuration
@RequiredArgsConstructor
class MajorExtensions {

    private final PirService reviews;
    private final ActionItemService actions;
    private final MajorIncidentRepository majors;
    private final PirRepository pirs;
    private final Clock clock;

    @Bean
    ApprovalHandler pirApprovals() {
        return new ApprovalHandler() {
            @Override
            public Approval.Type type() {
                return Approval.Type.PIR;
            }

            @Override
            public void onDecision(Approval approval, boolean approved, Actor approver) {
                reviews.onApprovalDecision(approval, approved, approver);
            }
        };
    }

    @Bean
    MyWorkContributor actionItemsSection() {
        return new MyWorkContributor() {
            @Override
            public String key() {
                return "actionItems";
            }

            @Override
            public int order() {
                return 150;
            }

            @Override
            public Object contribute(Context ctx) {
                LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
                List<Map<String, Object>> items = actions.openFor(ctx.actor().id(), ctx.limit()).stream().map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("title", a.getTitle());
                    m.put("actionType", a.getActionType());
                    m.put("status", a.getStatus());
                    m.put("dueDate", a.getDueDate() == null ? null : a.getDueDate().toString());
                    m.put("overdue", a.getDueDate() != null && a.getDueDate().isBefore(today));
                    if (a.getPirId() != null) {
                        PostIncidentReview p = pirs.findById(a.getPirId()).orElse(null);
                        m.put("source", "PIR");
                        m.put("sourceId", a.getPirId());
                        m.put("sourceNumber", p == null ? null : p.getNumber());
                        m.put("sourceTitle", p == null ? null : p.getTitle());
                        m.put("link", "/reviews/" + a.getPirId());
                    } else {
                        MajorIncident mi = majors.findById(a.getMajorIncidentId()).orElse(null);
                        m.put("source", "MAJOR_INCIDENT");
                        m.put("sourceId", a.getMajorIncidentId());
                        m.put("sourceNumber", mi == null ? null : mi.getNumber());
                        m.put("sourceTitle", mi == null ? null : mi.getSummary());
                        m.put("link", "/major-incidents/" + a.getMajorIncidentId());
                    }
                    return m;
                }).toList();
                return new Section<>(actions.openCountFor(ctx.actor().id()), items);
            }
        };
    }
}
