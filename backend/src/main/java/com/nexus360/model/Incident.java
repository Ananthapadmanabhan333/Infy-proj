package com.nexus360.model;
import jakarta.persistence.*;import jakarta.validation.constraints.NotBlank;import java.time.LocalDateTime;import java.util.UUID;
@Entity @Table(name="incidents", indexes={@Index(name="idx_incident_status",columnList="status"),@Index(name="idx_incident_priority",columnList="priority")})
public class Incident {
 @Id private String id;
 @NotBlank @Column(nullable=false,length=180) private String title;
 @Column(nullable=false,length=20) private String priority="Medium";
 @Column(nullable=false,length=80) private String service="General";
 @Column(nullable=false,length=100) private String assignee="Unassigned";
 @Column(nullable=false,length=30) private String status="Open";
 @Column(length=40) private String sla="4 h";
 private LocalDateTime createdAt;
 @PrePersist void create(){if(id==null||id.isBlank())id="INC-"+UUID.randomUUID().toString().substring(0,8).toUpperCase();if(createdAt==null)createdAt=LocalDateTime.now();}
 public String getId(){return id;} public void setId(String v){id=v;} public String getTitle(){return title;} public void setTitle(String v){title=v;} public String getPriority(){return priority;} public void setPriority(String v){priority=v;} public String getService(){return service;} public void setService(String v){service=v;} public String getAssignee(){return assignee;} public void setAssignee(String v){assignee=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getSla(){return sla;} public void setSla(String v){sla=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
