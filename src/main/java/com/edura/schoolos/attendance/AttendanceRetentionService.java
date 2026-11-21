package com.edura.schoolos.attendance;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Keeps the attendance table limited to the most recent 12 months. */
@Service
public class AttendanceRetentionService {
    private final JdbcTemplate jdbc;

    public AttendanceRetentionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    @Scheduled(cron = "0 15 0 * * *", zone = "Asia/Kolkata")
    public void removeExpiredAttendance() {
        jdbc.update("delete from attendance where attendance_date <= current_date - interval '1 year'");
    }
}
