package com.example.edujurnal.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ScheduleConfiguration {
    @Bean
    Clock schoolClock() {
        return Clock.system(ZoneId.of("Asia/Bishkek"));
    }
}
