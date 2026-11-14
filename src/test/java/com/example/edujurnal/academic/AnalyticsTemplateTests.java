package com.example.edujurnal.academic;

import com.example.edujurnal.service.PerformanceAnalyticsService.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsTemplateTests {
    private String render(boolean filled) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var servlet = new MockServletContext();
        var request = new MockHttpServletRequest(servlet);
        request.setRequestURI("/analytics");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        var context = new WebContext(exchange, Locale.forLanguageTag("ru"));
        var from = LocalDate.of(2026, 10, 5);
        var value = new Value(filled ? new BigDecimal("2.50") : null, filled ? 6 : 0);
        var points = List.of(
                new Point(from, from.plusDays(6), 3, new BigDecimal("3.00"), true, Trend.NO_DATA, null),
                new Point(from.plusWeeks(1), from.plusDays(13), 3, new BigDecimal("4.00"), true, Trend.GROWTH, new BigDecimal("1.00")),
                new Point(from.plusWeeks(2), from.plusDays(20), 0, null, true, Trend.NO_DATA, null),
                new Point(from.plusWeeks(3), from.plusDays(27), 3, new BigDecimal("2.00"), true, Trend.NO_DATA, null));
        var report = new Report(from, from.plusDays(27), Interval.WEEK, value,
                filled ? List.of(new Average(1, "Математика", value)) : List.of(),
                filled ? List.of(new SubjectDynamics(1, "Математика", points)) : List.of(),
                filled ? List.of(new Attention(1, "Математика", value, "Средний балл < 3", null, null, from, from.plusDays(20), null)) : List.of(),
                List.of(new Attendance(from, from.plusDays(6), filled ? 2 : 0, filled ? 1 : 0, 0, 0,
                        new Value(filled ? new BigDecimal("33.33") : null, filled ? 3 : 0))),
                new Homework(0, 0, 0, filled ? 1 : 0, new Value(filled ? new BigDecimal("100.00") : null, filled ? 1 : 0)));
        var options = List.of(new Option(1, "Тест"));
        context.setVariable("page", new Page(report, options, options, options, options));
        context.setVariable("report", report);
        context.setVariable("filter", new Filter(null, null, null, null, null, null, Interval.WEEK));
        context.setVariable("gradesHasData", filled);
        context.setVariable("attendanceHasData", filled);
        context.setVariable("homeworkHasData", filled);
        context.setVariable("attendanceMax", 2L);
        context.setVariable("homeworkMax", 1L);
        return engine.process("analytics/view", context);
    }

    @Test void rendersEmptyCharts() {
        assertThat(render(false)).contains("Нет данных", "Результаты ДЗ").doesNotContain("<svg");
    }

    @Test void rendersValuesTablesAndBreaksInDynamics() {
        assertThat(render(true)).contains("2.50", "33.33", "100.00", "Математика", "<svg", "<table")
                .contains("Работ учеников: 1", "Одно ДЗ для каждого ученика считается отдельной работой")
                .containsOnlyOnce("stroke-width=\"2\"");
    }
}
