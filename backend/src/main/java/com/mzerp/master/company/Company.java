package com.mzerp.master.company;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "biz_reg_no")
    private String bizRegNo;

    private String industry;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Company() {
    }

    public Company(String name, String bizRegNo, String industry) {
        this.name = name;
        this.bizRegNo = bizRegNo;
        this.industry = industry;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBizRegNo() {
        return bizRegNo;
    }

    public String getIndustry() {
        return industry;
    }
}
