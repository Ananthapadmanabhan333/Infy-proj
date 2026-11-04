package com.nexus360.problem;

import com.nexus360.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "problem_history")
public class ProblemHistory extends CreatedEntity {

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false, length = 160)
    private String actorName;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "from_value", length = 200)
    private String fromValue;

    @Column(name = "to_value", length = 200)
    private String toValue;

    @Column(length = 1000)
    private String note;
}
