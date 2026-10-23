package com.example.edujurnal.Entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@Table(name = "schedule_school")
@NoArgsConstructor
@AllArgsConstructor
public class School {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;
}