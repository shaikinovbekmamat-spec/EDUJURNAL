package com.example.edujurnal.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter @Setter
public class GenerationForm {
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate start;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate end;
    private int dailyLimit = 6;
    private int daysPerWeek = 5;
    private List<Unavailable> unavailable = new ArrayList<>();
    private List<Load> loads = new ArrayList<>(List.of(new Load()));

    @Getter @Setter
    public static class Load {
        private Long classId;
        private Long subjectId;
        private Long teacherId;
        private Integer weeklyCount;
        private Long roomId;
    }

    public record Unavailable(long teacherId, int weekday, int lessonNumber) {}
}
