package com.example.edujurnal.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BackupService {

    @Value("${backup.directory:backups}")
    private String backupDirectory;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${backup.pg_dump_path:pg_dump}")
    private String pgDumpPath;

    @Value("${backup.pg_restore_path:pg_restore}")
    private String pgRestorePath;

    public String createBackup() throws IOException, InterruptedException {
        // Создаем директорию для бэкапов если её нет
        Path backupDir = Paths.get(backupDirectory);
        if (!Files.exists(backupDir)) {
            Files.createDirectories(backupDir);
        }

        // Генерируем имя файла с датой и временем
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String backupFileName = "edujournal_backup_" + timestamp + ".sql";
        Path backupFile = backupDir.resolve(backupFileName);

        // Извлекаем имя базы данных из URL
        String dbName = extractDbName(dbUrl);

        // Команда pg_dump
        ProcessBuilder pb = new ProcessBuilder(
            pgDumpPath,
            "-h", extractHost(dbUrl),
            "-p", extractPort(dbUrl),
            "-U", dbUsername,
            "-F", "c", // custom format
            "-f", backupFile.toString(),
            dbName
        );

        // Устанавливаем пароль через переменную окружения
        Map<String, String> env = pb.environment();
        env.put("PGPASSWORD", dbPassword);

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String error = readStream(process.getErrorStream());
            throw new RuntimeException("Ошибка pg_dump: " + error);
        }

        return backupFileName;
    }

    public void restoreBackup(String backupFileName) throws IOException, InterruptedException {
        Path backupFile = Paths.get(backupDirectory).resolve(backupFileName);

        if (!Files.exists(backupFile)) {
            throw new FileNotFoundException("Резервная копия не найдена: " + backupFileName);
        }

        String dbName = extractDbName(dbUrl);

        // ВНИМАНИЕ: Восстановление удалит все текущие данные!
        ProcessBuilder pb = new ProcessBuilder(
            pgRestorePath,
            "-h", extractHost(dbUrl),
            "-p", extractPort(dbUrl),
            "-U", dbUsername,
            "-d", dbName,
            "-c", // clean (drop objects before recreating)
            backupFile.toString()
        );

        Map<String, String> env = pb.environment();
        env.put("PGPASSWORD", dbPassword);

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String error = readStream(process.getErrorStream());
            throw new RuntimeException("Ошибка pg_restore: " + error);
        }
    }

    public List<Map<String, Object>> listBackups() {
        try {
            Path backupDir = Paths.get(backupDirectory);
            if (!Files.exists(backupDir)) {
                return Collections.emptyList();
            }

            return Files.list(backupDir)
                .filter(path -> path.toString().endsWith(".sql"))
                .map(path -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", path.getFileName().toString());
                    try {
                        info.put("size", Files.size(path));
                        info.put("date", Files.getLastModifiedTime(path).toMillis());
                    } catch (IOException e) {
                        info.put("size", 0L);
                        info.put("date", 0L);
                    }
                    return info;
                })
                .sorted((a, b) -> Long.compare((Long)b.get("date"), (Long)a.get("date")))
                .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public Map<String, Object> getLastBackupInfo() {
        List<Map<String, Object>> backups = listBackups();
        return backups.isEmpty() ? null : backups.get(0);
    }

    public void deleteBackup(String backupFileName) throws IOException {
        Path backupFile = Paths.get(backupDirectory).resolve(backupFileName);
        if (!Files.exists(backupFile)) {
            throw new FileNotFoundException("Резервная копия не найдена");
        }
        Files.delete(backupFile);
    }

    private String extractDbName(String jdbcUrl) {
        // jdbc:postgresql://localhost:5432/tkrpo -> tkrpo
        int lastSlash = jdbcUrl.lastIndexOf('/');
        int questionMark = jdbcUrl.indexOf('?', lastSlash);
        if (questionMark > 0) {
            return jdbcUrl.substring(lastSlash + 1, questionMark);
        }
        return jdbcUrl.substring(lastSlash + 1);
    }

    private String extractHost(String jdbcUrl) {
        // jdbc:postgresql://localhost:5432/tkrpo -> localhost
        int doubleSlash = jdbcUrl.indexOf("//");
        int colon = jdbcUrl.indexOf(':', doubleSlash + 2);
        return jdbcUrl.substring(doubleSlash + 2, colon);
    }

    private String extractPort(String jdbcUrl) {
        // jdbc:postgresql://localhost:5432/tkrpo -> 5432
        int doubleSlash = jdbcUrl.indexOf("//");
        int colon = jdbcUrl.indexOf(':', doubleSlash + 2);
        int slash = jdbcUrl.indexOf('/', colon);
        return jdbcUrl.substring(colon + 1, slash);
    }

    private String readStream(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
